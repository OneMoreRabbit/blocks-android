package dev.blocks.android.state

import com.google.gson.Gson
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.DashboardView
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.TagActivationMedium
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateStorageCodecTest {
    @Test
    fun roundTripsTheVersionedQueueCacheCursorAndRetiredKeys() {
        val state = TenantSyncState(
            projects = listOf(Project("project-1", "Project one")),
            projectsLoaded = true,
            reentryLoaded = true,
            commands = listOf(
                QueuedCommand(
                    operation = OfflineOperation.CLOSE_WITH_OUTCOME,
                    targetId = "session-1",
                    projectId = "project-1",
                    idempotencyKey = "retry-key",
                    ifMatch = 7,
                    workSession = WorkSession(
                        id = "session-1",
                        projectId = "project-1",
                        state = WorkSessionState.ACTIVE,
                        coreOutcome = "Core",
                        rowVersion = 7,
                    ),
                    outcomeTier = OutcomeTier.CORE,
                    afterCursor = "40",
                    deliveryUncertain = true,
                ),
            ),
            changeCursor = "41",
            retiredIdempotencyKeys = listOf("old-key"),
            projectUiPreferences = ProjectUiPreferences(DashboardView.TILES, archiveMode = true),
        )

        val rows = SyncStateRowCodec().encode(state)
        val decoded = SyncStateRowCodec().decode(rows)

        assertEquals(8, rows.schemaVersion)
        assertEquals(1, rows.projects.size)
        assertEquals(1, rows.commands.size)
        assertEquals(1, rows.retiredIdempotencyKeys.size)
        assertEquals(state, decoded)
    }

    @Test
    fun migratesTheVersionedLegacyQueueWithoutChangingItsRetryIdentity() {
        val command = QueuedCommand(
            operation = OfflineOperation.CLOSE_WITH_OUTCOME,
            targetId = "session-1",
            projectId = "project-1",
            idempotencyKey = "legacy-retry-key",
            ifMatch = 7,
            workSession = WorkSession(
                id = "session-1",
                projectId = "project-1",
                state = WorkSessionState.ACTIVE,
                coreOutcome = "Core",
                rowVersion = 7,
            ),
            outcomeTier = OutcomeTier.CORE,
        )
        val encoded = Gson().toJson(
            mapOf(
                "schema_version" to 1,
                "commands" to listOf(command),
                "changeCursor" to "22",
            ),
        )

        val decoded = LegacySyncStateJsonCodec().decode(encoded)

        assertEquals(command, decoded.commands.single())
        assertEquals("legacy-retry-key", decoded.commands.single().idempotencyKey)
        assertEquals("22", decoded.changeCursor)
    }

    @Test
    fun migratesUnversionedCacheStateOnlyWhenItContainsNoReplayableCommands() {
        val decoded = LegacySyncStateJsonCodec().decode(
            """{"projects":[],"projectsLoaded":true,"reentryLoaded":true,"commands":[],"changeCursor":"17","retiredIdempotencyKeys":[]}""",
        )

        assertTrue(decoded.projectsLoaded)
        assertTrue(decoded.reentryLoaded)
        assertEquals("17", decoded.changeCursor)
        assertTrue(decoded.commands.isEmpty())
    }

    @Test
    fun refusesAnUnversionedReplayQueueBecauseItsSemanticsAreUnknown() {
        val encoded = """{"commands":[{"operation":"PARK","targetId":"session-1","projectId":"project-1","idempotencyKey":"key"}]}"""

        val error = runCatching { LegacySyncStateJsonCodec().decode(encoded) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsAnUnknownDatabaseRowSchemaWithoutDiscardingIt() {
        val rows = SyncStateRowCodec().encode(TenantSyncState()).copy(schemaVersion = 9)

        val error = runCatching { SyncStateRowCodec().decode(rows) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun schemaFiveSeedsParkingHistoryFromTheCachedNewestRecordWithoutClaimingCompleteness() {
        val project = ProjectSummary(
            id = "project-5",
            tenantId = "tenant-5",
            name = "Pilot project",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 2,
            createdAt = "2026-09-07T10:00:00Z",
            updatedAt = "2026-09-07T10:00:00Z",
        )
        val newest = ProjectParking(
            id = "parking-5",
            projectId = project.id,
            location = "Shelf",
            nextAction = "Open notebook",
            mustNotForget = "Bring cable",
            authoredByUserId = "user-5",
            createdAt = "2026-09-07T10:30:00Z",
        )
        val current = SyncStateRowCodec().encode(
            TenantSyncState(
                projectCache = ProjectCacheState(
                    activeProjects = listOf(project),
                    pages = listOf(
                        ProjectPage(
                            project,
                            ProjectNotes(project.id, "Notes", 1, project.updatedAt),
                            newest,
                        ),
                    ),
                ),
            ),
        )

        val migrated = SyncStateRowCodec().decode(
            current.copy(schemaVersion = 5, parkingRecords = emptyList(), parkingLoadedProjectIds = emptyList()),
        )

        assertEquals(listOf(newest), migrated.projectCache.parkingRecords)
        assertTrue(migrated.projectCache.parkingLoadedProjectIds.isEmpty())
    }

    @Test
    fun roundTripsApi03CacheAndQueueBesideUnchangedLegacyOperations() {
        val legacy = QueuedCommand(
            operation = OfflineOperation.PICKUP,
            targetId = "legacy-project",
            projectId = "legacy-project",
            idempotencyKey = "legacy-key",
            pickup = dev.blocks.android.mvp.PickupRequest("legacy-project", "Legacy core"),
        )
        val project = ProjectSummary(
            id = "project-3",
            tenantId = "tenant-a",
            name = "New project",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 2,
            createdAt = "2026-09-07T10:00:00Z",
            updatedAt = "2026-09-07T10:00:00Z",
        )
        val command = ProjectMutation(
            operationId = "notes-operation",
            type = ProjectMutationType.SAVE_NOTES,
            targetId = project.id,
            ifMatch = 4,
            notesBody = "Unsent notes",
            authoritativeStateConfirmed = true,
        )
        val state = TenantSyncState(
            commands = listOf(legacy),
            changeCursor = "71",
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                parkingRecords = listOf(
                    ProjectParking(
                        id = "parking-3",
                        projectId = project.id,
                        location = "Workbench",
                        nextAction = "Fit bracket",
                        mustNotForget = "Bring screws",
                        authoredByUserId = "user-3",
                        createdAt = "2026-09-07T10:30:00Z",
                        occurredAt = "2026-09-07T10:25:00Z",
                        clientId = "parking-client-3",
                    ),
                ),
                parkingLoadedProjectIds = listOf(project.id),
                commands = listOf(command),
                projectIdMappings = listOf(ProjectIdentityMapping("local-project", project.id)),
                projectsAwaitingNotesBaseline = listOf(project.id),
            ),
            projectDrafts = FormDrafts(projectNotes = mapOf(project.id to "Unsent notes")),
            projectDraftsMigrated = true,
        )

        val decoded = SyncStateRowCodec().decode(SyncStateRowCodec().encode(state))
        assertEquals(
            state,
            decoded.copy(projectCache = decoded.projectCache.copy(projectLinks = emptyList())),
        )
        assertEquals(listOf(project.id), decoded.projectCache.projectLinks.map { it.projectId })
    }

    @Test
    fun roundTripsEncryptedLocalBlockProgrammingAndFrozenOperationOrder() {
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 1).toByte() })
        val block = LocalBlockRecord(
            clientBlockId = "client-block",
            token = token,
            label = "Workbench",
            state = LocalBlockState.ACTIVE,
            intendedProjectId = "project-1",
            boundProjectId = "project-1",
            registrationOperationId = "register-operation",
            initialBindingOperationId = "bind-operation",
            createdAt = "2026-09-12T08:00:00Z",
            verifiedAt = "2026-09-12T08:01:00Z",
            activationMedium = TagActivationMedium.NFC,
        )
        val registration = LocalBlockOperation(
            operationId = "register-operation",
            type = LocalBlockOperationType.REGISTER_BLOCK,
            clientBlockId = block.clientBlockId,
            token = token,
            label = block.label,
            createdAt = block.createdAt,
        )
        val binding = LocalBlockOperation(
            operationId = "bind-operation",
            type = LocalBlockOperationType.BIND_BLOCK,
            clientBlockId = block.clientBlockId,
            projectId = "project-1",
            dependsOnOperationId = registration.operationId,
            createdAt = "2026-09-12T08:01:00Z",
        )
        val state = TenantSyncState(
            projectCache = ProjectCacheState(
                localBlocks = listOf(block),
                localBlockOperations = listOf(registration, binding),
            ),
        )

        val rows = SyncStateRowCodec().encode(state)
        val decoded = SyncStateRowCodec().decode(rows)

        assertEquals(8, rows.schemaVersion)
        assertEquals(state, decoded)
        assertEquals(
            listOf(LocalBlockOperationType.REGISTER_BLOCK, LocalBlockOperationType.BIND_BLOCK),
            decoded.projectCache.localBlockOperations.map { it.type },
        )
    }

    @Test
    fun schemaTwoConflictMigratesFailClosedUntilAuthoritativeStateIsRefreshed() {
        val command = ProjectMutation(
            operationId = "old-conflict",
            type = ProjectMutationType.SAVE_NOTES,
            targetId = "project-3",
            ifMatch = 4,
            notesBody = "Retained notes",
            status = dev.blocks.android.project.ProjectMutationStatus.REQUIRES_RECONCILIATION,
            authoritativeStateConfirmed = true,
        )
        val current = SyncStateRowCodec().encode(
            TenantSyncState(projectCache = ProjectCacheState(commands = listOf(command))),
        )
        val schemaTwo = current.copy(
            schemaVersion = 2,
            projectCommands = current.projectCommands.map {
                JSONObject(it).apply { remove("authoritativeStateConfirmed") }.toString()
            },
        )

        val migrated = SyncStateRowCodec().decode(schemaTwo)

        assertTrue(!migrated.projectCache.commands.single().authoritativeStateConfirmed)
    }

    @Test
    fun pilotSchemaMigrationPreservesLegacyQueueAndCursorWithoutImportingNewRows() {
        val command = QueuedCommand(
            operation = OfflineOperation.PICKUP,
            targetId = "legacy-project",
            projectId = "legacy-project",
            idempotencyKey = "pilot-original-key",
            pickup = dev.blocks.android.mvp.PickupRequest("legacy-project", "Legacy core"),
        )
        val currentRows = SyncStateRowCodec().encode(
            TenantSyncState(commands = listOf(command), changeCursor = "39"),
        )

        val migrated = SyncStateRowCodec().decode(currentRows.copy(schemaVersion = 1))

        assertEquals("pilot-original-key", migrated.commands.single().idempotencyKey)
        assertEquals("39", migrated.changeCursor)
        assertEquals(ProjectCacheState(), migrated.projectCache)
    }
}
