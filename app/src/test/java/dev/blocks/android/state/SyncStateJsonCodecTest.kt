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
import dev.blocks.android.project.ProjectCover
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
    fun schemaNineDiscardsSingletonNotesAndDraftsWithoutCreatingDiscreteItems() {
        val first = ProjectSummary(
            id = "10000000-0000-4000-8000-000000000001",
            tenantId = "tenant-a",
            name = "First",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 1,
            createdAt = "2026-09-16T09:00:00Z",
            updatedAt = "2026-09-16T09:00:00Z",
        )
        val second = first.copy(id = "20000000-0000-4000-8000-000000000002", name = "Second")
        val body = "A".repeat(20_000)
        val pendingNotes = ProjectMutation(
            operationId = "discard-notes",
            type = ProjectMutationType.SAVE_NOTES,
            targetId = first.id,
            ifMatch = 7,
            notesBody = "Pending edit",
        )
        val pendingRename = ProjectMutation(
            operationId = "keep-rename",
            type = ProjectMutationType.RENAME_PROJECT,
            targetId = first.id,
            ifMatch = 1,
            projectName = "Renamed",
        )
        val state = TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(first, second),
                pages = listOf(
                    ProjectPage(first, ProjectNotes(first.id, body, 7, first.updatedAt), null),
                    ProjectPage(second, ProjectNotes(second.id, "", 1, second.updatedAt), null),
                ),
                commands = listOf(pendingRename, pendingNotes),
                projectsAwaitingNotesBaseline = listOf(first.id),
            ),
            projectDrafts = FormDrafts(projectNotes = mapOf(first.id to "Pending edit")),
            projectDraftsMigrated = true,
        )
        val oldRows = SyncStateRowCodec().encode(state).copy(
            schemaVersion = 9,
            projectNoteItems = emptyList(),
            projectResourceLinks = emptyList(),
            projectItemOperations = emptyList(),
            retiredProjectItemOperations = emptyList(),
        )

        val migrated = SyncStateRowCodec().decode(oldRows)
        assertTrue(migrated.projectCache.noteItems.isEmpty())
        assertTrue(migrated.projectCache.itemOperations.isEmpty())
        assertEquals(listOf(pendingRename), migrated.projectCache.commands)
        assertTrue("discard-notes" in migrated.projectCache.retiredOperationIds)
        assertTrue(migrated.projectCache.projectsAwaitingNotesBaseline.isEmpty())
        assertTrue(migrated.projectDrafts.projectNoteItems.isEmpty())
        assertTrue(migrated.projectDrafts.projectNotes.isEmpty())

        val restarted = SyncStateRowCodec().decode(SyncStateRowCodec().encode(migrated))
        assertTrue(restarted.projectCache.noteItems.isEmpty())
        assertTrue(restarted.projectDrafts.projectNoteItems.isEmpty())
    }

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

        assertEquals(10, rows.schemaVersion)
        assertEquals(1, rows.projects.size)
        assertEquals(1, rows.commands.size)
        assertEquals(1, rows.retiredIdempotencyKeys.size)
        assertEquals(
            state,
            decoded.copy(projectCache = decoded.projectCache.copy(projectLinks = emptyList())),
        )
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
        val rows = SyncStateRowCodec().encode(TenantSyncState()).copy(schemaVersion = 11)

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
                activeProjects = listOf(
                    ProjectSummary(
                        id = "project-1",
                        tenantId = "tenant-a",
                        name = "Workbench",
                        lifecycle = ProjectLifecycle.ACTIVE,
                        rowVersion = 1,
                        createdAt = "2026-09-15T09:00:00Z",
                        updatedAt = "2026-09-15T09:00:00Z",
                    ),
                ),
                localBlocks = listOf(block),
                localBlockOperations = listOf(registration, binding),
                projectCovers = listOf(
                    ProjectCover(
                        coverId = "123e4567-e89b-42d3-a456-426614174000",
                        tenantId = "tenant-a",
                        projectId = "project-1",
                        mediaType = "image/jpeg",
                        displayContentRef = "123e4567-e89b-42d3-a456-426614174000-r1-display.enc",
                        displayWidth = 1200,
                        displayHeight = 675,
                        displayByteSize = 2048,
                        displayDigest = "b".repeat(64),
                        thumbnailContentRef = "123e4567-e89b-42d3-a456-426614174000-r1-thumbnail.enc",
                        thumbnailWidth = 480,
                        thumbnailHeight = 270,
                        thumbnailByteSize = 512,
                        thumbnailDigest = "c".repeat(64),
                        contentDigest = "a".repeat(64),
                        revision = 1,
                        createdAt = "2026-09-15T09:01:00Z",
                        updatedAt = "2026-09-15T09:01:00Z",
                    ),
                ),
            ),
        )

        val rows = SyncStateRowCodec().encode(state)
        val decoded = SyncStateRowCodec().decode(rows)

        assertEquals(10, rows.schemaVersion)
        assertEquals(
            state,
            decoded.copy(projectCache = decoded.projectCache.copy(projectLinks = emptyList())),
        )
        assertEquals(
            listOf(LocalBlockOperationType.REGISTER_BLOCK, LocalBlockOperationType.BIND_BLOCK),
            decoded.projectCache.localBlockOperations.map { it.type },
        )
    }

    @Test
    fun schemaTwoSingletonNotesConflictIsRetiredAtTheDiscreteItemsCutover() {
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

        assertTrue(migrated.projectCache.commands.isEmpty())
        assertTrue("old-conflict" in migrated.projectCache.retiredOperationIds)
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
