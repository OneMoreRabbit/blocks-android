package dev.blocks.android.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineBackupExporterTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state by lazy { AndroidSyncStateStore(context) }
    private val drafts by lazy { AndroidEncryptedDraftStore(context) }
    private val accepted by lazy { AndroidEncryptedAcceptedTenantContinuityStore(context) }
    private val media by lazy { AndroidEncryptedProjectCoverMediaStore(context) }

    @Before
    @After
    fun clean() {
        listOf(TENANT_A, TENANT_B).forEach { tenant ->
            runCatching { state.clearSyncState(tenant) }
            runCatching { drafts.clear(tenant) }
            runCatching { media.eraseTenant(tenant) }
        }
        accepted.clear()
    }

    @Test
    fun offlineExportContainsOpenAccountQueueDraftsAndMediaButReportsLockedAccount() {
        val display = "test-display-data".toByteArray()
        val thumbnail = "test-thumbnail-data".toByteArray()
        val content = ProjectCoverContent(
            PROJECT, display, thumbnail, 1200, 675, 480, 270,
            digest(display), digest(thumbnail), digest(display, thumbnail), NOW,
        )
        val cover = media.stage(TENANT_A, PROJECT, content, null)
        content.clear()
        val project = ProjectSummary(PROJECT, TENANT_A, "Private workshop", ProjectLifecycle.ACTIVE, 2, NOW, NOW)
        val parking = ProjectParking(PARK, PROJECT, "Garage", "Fit shelf", "Charger", "author", NOW)
        val pending = ProjectMutation(
            operationId = OPERATION,
            type = ProjectMutationType.ADD_PARKING,
            targetId = PROJECT,
            projectId = PROJECT,
            idempotencyKey = OPERATION,
            clientResourceId = PARK,
            parking = ParkingDraft(parking.location, parking.nextAction, parking.mustNotForget),
            localCreatedAt = NOW,
        )
        val tagToken = OpaqueTagToken.fromBytes(ByteArray(32) { it.toByte() })
        val localBlock = LocalBlockRecord(
            clientBlockId = BLOCK, token = tagToken, label = "Workshop tag",
            state = LocalBlockState.ACTIVE, intendedProjectId = PROJECT,
            boundProjectId = PROJECT, createdAt = NOW,
        )
        val tagOperation = LocalBlockOperation(
            operationId = BLOCK_OPERATION, type = LocalBlockOperationType.REGISTER_BLOCK,
            clientBlockId = BLOCK, token = tagToken, label = "Workshop tag", createdAt = NOW,
        )
        state.saveSyncState(TENANT_A, TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project), activeProjectsLoaded = true,
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT, "Legacy note", 2, NOW), parking)),
                parkingRecords = listOf(parking), parkingLoadedProjectIds = listOf(PROJECT),
                noteItems = listOf(ProjectNoteItem(NOTE, PROJECT, "Offline note", false, "author", NOW, NOW)),
                resourceLinks = listOf(ProjectResourceLink(LINK, PROJECT, "Plans", "https://example.invalid", NOW, NOW)),
                projectCovers = listOf(cover), commands = listOf(pending),
                localBlocks = listOf(localBlock), localBlockOperations = listOf(tagOperation),
            ),
            changeCursor = "cursor-17",
        ))
        state.saveSyncState(TENANT_B, TenantSyncState())
        drafts.save(TENANT_A, FormDrafts(projectName = "Unsent name"))
        accepted.save(AcceptedTenantContinuity(TENANT_B, AccountIdentity("https://accounts.google.com", "b")))
        accepted.save(AcceptedTenantContinuity(TENANT_A, AccountIdentity("https://accounts.google.com", "a")))
        val before = state.loadSyncState(TENANT_A)

        val payload = OfflineBackupExporter(state, drafts, accepted, media).capture(TENANT_A, "0.10.2")
        val archive = PortableBackupCodec.encode(payload)
        val restored = PortableBackupCodec.decode(archive)
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "disposable-offline-backup.blocksbackup")
        output.writeBytes(archive)
        File(requireNotNull(context.getExternalFilesDir(null)), "disposable-offline-backup-expected.json")
            .writeText(Gson().toJson(payload))

        assertEquals(payload, restored)
        assertEquals(before, state.loadSyncState(TENANT_A))
        assertEquals("Unsent name", restored.drafts.projectName)
        assertEquals("cursor-17", restored.state.changeCursor)
        assertTrue(restored.state.projectCommands.single().contains(OPERATION))
        assertEquals(2, restored.coverMedia.size)
        assertEquals(1, restored.excludedKnownOtherAccountPartitions)
        assertEquals(1, restored.state.localBlocks.size)
        assertTrue(restored.state.localBlocks.single().contains(tagToken.encoded))
        assertTrue(restored.state.localBlockOperations.single().contains(tagToken.encoded))
        assertTrue(String(archive, Charsets.ISO_8859_1).contains("Private workshop"))
        assertTrue(String(archive, Charsets.ISO_8859_1).contains("Unsent name"))
        assertTrue(runCatching { OfflineBackupExporter(state, drafts, accepted, media).capture(TENANT_B, "0.10.2") }.isFailure)
        archive.fill(0)
    }

    private fun digest(vararg values: ByteArray): String = MessageDigest.getInstance("SHA-256").run {
        values.forEach(::update)
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TENANT_A = "11000000-0000-4000-8000-000000000001"
        const val TENANT_B = "11000000-0000-4000-8000-000000000002"
        const val PROJECT = "21000000-0000-4000-8000-000000000001"
        const val PARK = "31000000-0000-4000-8000-000000000001"
        const val NOTE = "41000000-0000-4000-8000-000000000001"
        const val LINK = "51000000-0000-4000-8000-000000000001"
        const val OPERATION = "61000000-0000-4000-8000-000000000001"
        const val BLOCK = "71000000-0000-4000-8000-000000000001"
        const val BLOCK_OPERATION = "81000000-0000-4000-8000-000000000001"
        const val NOW = "2026-11-12T10:00:00Z"
    }
}
