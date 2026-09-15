package dev.blocks.android.state

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import dev.blocks.android.tag.TagActivationMedium
import java.security.KeyStore
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNormalizedSyncStateStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidSyncStateStore
        get() = AndroidSyncStateStore(context)

    @Before
    @After
    fun clear() {
        listOf(TENANT, FAILED_TENANT, OTHER_TENANT).forEach { store.clearSyncState(it) }
    }

    @Test
    fun migratesReleasedEncryptedStateThenRetiresOnlyThatSnapshotAndKey() {
        val released = state(TENANT)
        val other = state(OTHER_TENANT, projectId = OTHER_PROJECT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(TENANT, released)
        LegacyEncryptedSyncStateStore(context).saveSyncState(OTHER_TENANT, other)

        val migrated = store.loadSyncState(TENANT)

        assertEquals(released, migrated)
        assertTrue(normalizedDatabase(TENANT).isFile)
        assertFalse(releasedDatabase(TENANT).exists())
        assertFalse(keyStore().containsAlias(releasedKeyAlias(TENANT)))
        assertTrue(releasedDatabase(OTHER_TENANT).isFile)
        assertTrue(keyStore().containsAlias(releasedKeyAlias(OTHER_TENANT)))
        assertEquals(other, LegacyEncryptedSyncStateStore(context).loadSyncState(OTHER_TENANT))
    }

    @Test
    fun failedCutoverLeavesReleasedSnapshotAndKeyRetryable() {
        val released = state(FAILED_TENANT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(FAILED_TENANT, released)
        val failing = AndroidSyncStateStore(context) { _, _ -> error("simulated cutover check") }

        assertTrue(runCatching { failing.loadSyncState(FAILED_TENANT) }.isFailure)
        assertFalse(normalizedDatabase(FAILED_TENANT).exists())
        assertTrue(releasedDatabase(FAILED_TENANT).isFile)
        assertTrue(keyStore().containsAlias(releasedKeyAlias(FAILED_TENANT)))
        assertEquals(released, store.loadSyncState(FAILED_TENANT))
    }

    @Test
    fun notesSubmitTouchesOnlyNotesAndQueueRowsInOneTransaction() {
        val initial = state(TENANT).copy(projectCache = state(TENANT).projectCache.copy(commands = emptyList()))
        store.saveSyncState(TENANT, initial)
        installAuditTriggers(TENANT)
        val command = ProjectMutation(
            operationId = "notes-operation",
            type = ProjectMutationType.SAVE_NOTES,
            targetId = PROJECT,
            ifMatch = 2,
            notesBody = "Changed notes",
            localCreatedAt = NOW,
        )
        val updated = initial.copy(
            projectCache = initial.projectCache.copy(
                pages = initial.projectCache.pages.map { page ->
                    if (page.project.id == PROJECT) {
                        page.copy(notes = page.notes.copy(body = "Changed notes", updatedAt = NOW))
                    } else {
                        page
                    }
                },
                commands = listOf(command),
            ),
        )

        store.saveProjectMutation(TENANT, initial, updated, ProjectMutationType.SAVE_NOTES)

        assertEquals(updated, store.loadSyncState(TENANT))
        assertEquals(
            setOf(AndroidSyncStateStore.TABLE_PROJECT_NOTES, AndroidSyncStateStore.TABLE_PROJECT_COMMANDS),
            auditedTables(TENANT),
        )
    }

    @Test
    fun ordinaryRowsAreSandboxPlaintextWhileLegacyTagTokenRemainsProtected() {
        val state = state(TENANT)
        store.saveSyncState(TENANT, state)

        val bytes = normalizedDatabase(TENANT).readBytes()
        assertTrue(bytes.containsBytes("Workshop shelves".toByteArray()))
        assertTrue(bytes.containsBytes("Bring the charger".toByteArray()))
        assertFalse(bytes.containsBytes(TAG_TOKEN.encoded.toByteArray()))
        assertTrue(keyStore().containsAlias(secretKeyAlias(TENANT)))
    }

    @Test
    fun tenantPartitionsRemainIndependentAndClearIsScoped() {
        val first = state(TENANT)
        val second = state(OTHER_TENANT, projectId = OTHER_PROJECT)
        store.saveSyncState(TENANT, first)
        store.saveSyncState(OTHER_TENANT, second)

        store.clearSyncState(TENANT)

        assertEquals(TenantSyncState(), store.loadSyncState(TENANT))
        assertEquals(second, store.loadSyncState(OTHER_TENANT))
        assertFalse(normalizedDatabase(TENANT).exists())
        assertTrue(normalizedDatabase(OTHER_TENANT).exists())
    }

    private fun state(tenantId: String, projectId: String = PROJECT): TenantSyncState {
        val project = ProjectSummary(
            id = projectId,
            tenantId = tenantId,
            name = "Workshop shelves",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 3,
            createdAt = NOW,
            updatedAt = NOW,
            clientId = projectId,
        )
        val parking = ProjectParking(
            id = "$projectId-parking",
            projectId = projectId,
            location = "Garage bench",
            nextAction = "Fit the bracket",
            mustNotForget = "Bring the charger",
            authoredByUserId = "local-user",
            createdAt = NOW,
        )
        val block = LocalBlockRecord(
            clientBlockId = "$projectId-block",
            token = TAG_TOKEN,
            label = "Workshop block",
            state = LocalBlockState.ACTIVE,
            intendedProjectId = projectId,
            boundProjectId = projectId,
            registrationOperationId = "$projectId-register",
            initialBindingOperationId = "$projectId-bind",
            createdAt = NOW,
            verifiedAt = NOW,
            activationMedium = TagActivationMedium.NFC,
        )
        val archivedId = "3${projectId.drop(1)}"
        val archived = project.copy(
            id = archivedId,
            name = "Archived supplies",
            lifecycle = ProjectLifecycle.ARCHIVED,
            archivedAt = NOW,
            clientId = archivedId,
        )
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                archivedProjects = listOf(archived),
                archivedProjectsLoaded = true,
                pages = listOf(
                    ProjectPage(project, ProjectNotes(projectId, "Measure twice", 2, NOW), parking),
                    ProjectPage(archived, ProjectNotes(archivedId, "Stored safely", 1, NOW), null),
                ),
                parkingRecords = listOf(parking),
                parkingLoadedProjectIds = listOf(projectId),
                localBlocks = listOf(block),
                localBlockOperations = listOf(
                    LocalBlockOperation(
                        operationId = "$projectId-register",
                        type = LocalBlockOperationType.REGISTER_BLOCK,
                        clientBlockId = block.clientBlockId,
                        token = TAG_TOKEN,
                        label = block.label,
                        createdAt = NOW,
                    ),
                    LocalBlockOperation(
                        operationId = "$projectId-bind",
                        type = LocalBlockOperationType.BIND_BLOCK,
                        clientBlockId = block.clientBlockId,
                        projectId = projectId,
                        dependsOnOperationId = "$projectId-register",
                        createdAt = NOW,
                    ),
                ),
                projectLinks = listOf(
                    ProjectLinkIdentity(projectId, ProjectKey.parse(projectId)!!),
                    ProjectLinkIdentity(archivedId, ProjectKey.parse(archivedId)!!),
                ),
                projectCovers = listOf(
                    ProjectCover(
                        coverId = "123e4567-e89b-42d3-a456-426614174071",
                        tenantId = tenantId,
                        projectId = projectId,
                        mediaType = "image/jpeg",
                        displayContentRef = "display.enc",
                        displayWidth = 1200,
                        displayHeight = 800,
                        displayByteSize = 2048,
                        displayDigest = "a".repeat(64),
                        thumbnailContentRef = "thumbnail.enc",
                        thumbnailWidth = 320,
                        thumbnailHeight = 213,
                        thumbnailByteSize = 512,
                        thumbnailDigest = "b".repeat(64),
                        contentDigest = "c".repeat(64),
                        revision = 1,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                ),
                commands = listOf(
                    ProjectMutation(
                        operationId = "$projectId-pending-notes",
                        type = ProjectMutationType.SAVE_NOTES,
                        targetId = projectId,
                        ifMatch = 2,
                        notesBody = "Pending note",
                        localCreatedAt = NOW,
                    ),
                ),
                provisionalProjectIds = listOf(projectId),
                projectIdMappings = listOf(ProjectIdentityMapping("4${projectId.drop(1)}", projectId)),
            ),
            projectDrafts = FormDrafts(),
            projectDraftsMigrated = true,
            changeCursor = "71",
        )
    }

    private fun installAuditTriggers(tenantId: String) {
        SQLiteDatabase.openDatabase(normalizedDatabase(tenantId).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE write_audit (table_name TEXT NOT NULL)")
            AndroidSyncStateStore.STATE_TABLES.forEachIndexed { index, table ->
                listOf("INSERT", "UPDATE", "DELETE").forEachIndexed { operationIndex, operation ->
                    db.execSQL(
                        "CREATE TRIGGER audit_${index}_$operationIndex AFTER $operation ON $table " +
                            "BEGIN INSERT INTO write_audit(table_name) VALUES ('$table'); END",
                    )
                }
            }
        }
    }

    private fun auditedTables(tenantId: String): Set<String> =
        SQLiteDatabase.openDatabase(normalizedDatabase(tenantId).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT DISTINCT table_name FROM write_audit", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun normalizedDatabase(tenantId: String) = context.getDatabasePath(
        "${AndroidSyncStateStore.DATABASE_PREFIX}${digest(tenantId)}.db",
    )
    private fun releasedDatabase(tenantId: String) = context.getDatabasePath("blocks_sync_${digest(tenantId)}.db")
    private fun releasedKeyAlias(tenantId: String) = "blocks-sync-db-v1-${digest(tenantId)}"
    private fun secretKeyAlias(tenantId: String) = "${AndroidSyncStateStore.SECRET_KEY_PREFIX}${digest(tenantId)}"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun ByteArray.containsBytes(target: ByteArray): Boolean =
        indices.any { start -> start + target.size <= size && copyOfRange(start, start + target.size).contentEquals(target) }

    private companion object {
        const val TENANT = "00000000-0000-0000-0000-000000000071"
        const val FAILED_TENANT = "00000000-0000-0000-0000-000000000072"
        const val OTHER_TENANT = "00000000-0000-0000-0000-000000000073"
        const val PROJECT = "123e4567-e89b-12d3-a456-426614174071"
        const val OTHER_PROJECT = "223e4567-e89b-12d3-a456-426614174073"
        const val NOW = "2026-09-15T12:00:00Z"
        val TAG_TOKEN = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 41).toByte() })
    }
}
