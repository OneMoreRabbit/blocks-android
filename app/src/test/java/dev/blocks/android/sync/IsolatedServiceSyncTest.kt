package dev.blocks.android.sync

import com.google.gson.JsonParser
import dev.blocks.android.api.ApiResponse
import dev.blocks.android.api.ProjectApiClient
import dev.blocks.android.api.ServiceProjectRepository
import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectItemAcknowledgement
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectService
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opt-in disposable Service gate. Ordinary unit CI runs the configuration preflight; setting
 * BLOCKS_TESTINT_DIR runs the same repository queue against the isolated, synthetic test API.
 * No token, tenant, or owner data is committed to the repository.
 */
class IsolatedServiceSyncTest {
    @Test
    fun androidQueueCreatesProjectAndNoteForIndependentClient() {
        val directory = System.getenv("BLOCKS_TESTINT_DIR")?.let(Path::of)
        if (directory == null) {
            assertFalse("Integration credentials must not live in test resources", Files.exists(Path.of("src/test/resources/synthetic-user-1.jwt")))
            return
        }

        val base = URI("https://blocks-testint-tls:8443/")
        val http = trustedClient(Path.of(System.getenv("BLOCKS_TESTINT_CA") ?: error("Missing estate CA path")))
        val token1 = Files.readString(directory.resolve("synthetic-user-1.jwt")).trim()
        val token2 = Files.readString(directory.resolve("synthetic-user-2.jwt")).trim()
        val tenant1 = exchangeTenant(http, base, token1)
        val tenant2 = exchangeTenant(http, base, token2)
        assertFalse(tenant1 == tenant2)
        val session = Session(tenant1, token1)
        val firstTransport = ProjectApiClient(base, httpClient = http)
        val firstService = ServiceProjectRepository(firstTransport)
        val state = MemoryState()
        val drafts = MemoryDrafts()
        var repository = ProjectSyncRepository(
            firstService, NoOpLegacyQueueReplay, drafts, state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        val projectClientId = UUID.randomUUID().toString()
        val noteClientId = UUID.randomUUID().toString()
        val projectName = "Blocks integration 2026-11-12"
        val noteBody = "Android to Service to Web disposable sync check"
        repository.queueCreateProject(session, projectName, UUID.randomUUID().toString(), projectClientId)
        repository.addNoteItem(session, projectClientId, noteBody, UUID.randomUUID().toString(), noteClientId)
        assertEquals(1, state.loadSyncState(tenant1).projectCache.commands.size)
        assertEquals(1, state.loadSyncState(tenant1).projectCache.itemOperations.size)

        // A new repository instance exercises durable operation identity reuse before delivery.
        repository = ProjectSyncRepository(
            firstService, NoOpLegacyQueueReplay, drafts, state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        val delivered = repository.backgroundSync(session)
        assertEquals(0, delivered.pendingProjectOperations)
        assertEquals(0, delivered.pendingItemOperations)
        val project = delivered.cache.activeProjects.single { it.clientId == projectClientId }
        val note = delivered.cache.noteItems.single { it.body == noteBody && it.projectId == project.id }

        val independent = ServiceProjectRepository(ProjectApiClient(base, httpClient = http))
        assertTrue(independent.listProjects(session, ProjectLifecycle.ACTIVE).any { it.id == project.id && it.name == projectName })
        assertEquals(noteBody, independent.readNote(session, project.id, note.id).body)
        assertEquals(1, independent.listProjects(session, ProjectLifecycle.ACTIVE).count { it.clientId == projectClientId })
        val forbidden = ProjectApiClient(base, httpClient = http).projectPage(token2, tenant1, project.id)
        assertTrue(
            "Another synthetic account must not see this Project",
            forbidden is ApiResponse.Failure && forbidden.status in setOf(403, 404),
        )
        println("TESTINT_PROJECT_ID=${project.id} TESTINT_NOTE_ID=${note.id} TESTINT_PROJECT_CLIENT_ID=$projectClientId")

        // Deliver once but lose the response locally; replay after repository reconstruction
        // must return the same Service Note, not append a duplicate or discard the local edit.
        val retryNoteClientId = UUID.randomUUID().toString()
        val retryOperationId = UUID.randomUUID().toString()
        val retryBody = "Disposable reconnect and retry proof"
        var firstAcknowledgement: ProjectItemAcknowledgement.Note? = null
        var loseResponse = true
        var offlinePull = true
        var offlineEdit = false
        val lostResponseService = object : ProjectService by firstService {
            override fun createNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note {
                val acknowledgement = firstService.createNote(session, operation)
                if (loseResponse) {
                    firstAcknowledgement = acknowledgement
                    loseResponse = false
                    throw IOException("Synthetic response lost after Service accepted Note")
                }
                return acknowledgement
            }

            override fun readNote(session: Session, projectId: String, noteId: String) =
                if (offlinePull) throw IOException("Synthetic readback unavailable")
                else firstService.readNote(session, projectId, noteId)

            override fun changes(session: Session, cursor: String?, limit: Int) =
                if (offlinePull) throw IOException("Synthetic change feed unavailable")
                else firstService.changes(session, cursor, limit)

            override fun editNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note =
                if (offlineEdit) throw IOException("Synthetic edit transport unavailable")
                else firstService.editNote(session, operation)
        }
        repository = ProjectSyncRepository(
            lostResponseService, NoOpLegacyQueueReplay, drafts, state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        repository.addNoteItem(session, project.id, retryBody, retryOperationId, retryNoteClientId)
        val uncertain = repository.backgroundSync(session)
        assertEquals(1, uncertain.pendingItemOperations)
        assertTrue(uncertain.syncDeferred)
        assertEquals(retryOperationId, state.loadSyncState(tenant1).projectCache.itemOperations.single().operationId)
        offlinePull = false
        repository = ProjectSyncRepository(
            lostResponseService, NoOpLegacyQueueReplay, drafts, state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        val replayed = repository.backgroundSync(session)
        assertEquals(0, replayed.pendingItemOperations)
        val retried = replayed.cache.noteItems.single { it.body == retryBody && it.projectId == project.id }
        assertEquals(firstAcknowledgement?.itemId, retried.id)
        assertEquals(retryBody, independent.readNote(session, project.id, retried.id).body)
        println("TESTINT_RETRY_NOTE_ID=${retried.id} TESTINT_RETRY_OPERATION_ID=$retryOperationId")

        val editedBody = "Disposable offline edit converged after reconnect"
        offlineEdit = true
        repository.editNoteItem(session, retried.id, editedBody, UUID.randomUUID().toString())
        val offline = repository.backgroundSync(session)
        assertEquals(1, offline.pendingItemOperations)
        assertTrue(offline.syncDeferred)
        offlineEdit = false
        repository = ProjectSyncRepository(
            lostResponseService, NoOpLegacyQueueReplay, drafts, state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        val converged = repository.backgroundSync(session)
        assertEquals(0, converged.pendingItemOperations)
        assertEquals(editedBody, independent.readNote(session, project.id, retried.id).body)
    }

    private fun exchangeTenant(http: OkHttpClient, base: URI, token: String): String {
        val request = Request.Builder()
            .url(base.resolve("/v1/session").toString())
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody())
            .build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Synthetic session exchange failed: HTTP ${response.code}" }
            JsonParser.parseString(response.body.string()).asJsonObject.get("personal_tenant_id").asString
        }
    }

    private fun trustedClient(ca: Path): OkHttpClient {
        val certificate = Files.newInputStream(ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("estate-test-ca", certificate)
        }
        val managerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
        val manager = managerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, manager).build()
    }

    private class MemoryState : SyncStateStore {
        private val values = mutableMapOf<String, TenantSyncState>()
        override fun loadSyncState(tenantId: String): TenantSyncState = values[tenantId] ?: TenantSyncState()
        override fun saveSyncState(tenantId: String, state: TenantSyncState) { values[tenantId] = state }
        override fun clearSyncState(tenantId: String) { values.remove(tenantId) }
    }

    private class MemoryDrafts : DraftStore {
        private val values = mutableMapOf<String, FormDrafts>()
        override fun load(tenantId: String): FormDrafts = values[tenantId] ?: FormDrafts()
        override fun save(tenantId: String, drafts: FormDrafts) { values[tenantId] = drafts }
        override fun clear(tenantId: String) { values.remove(tenantId) }
    }
}
