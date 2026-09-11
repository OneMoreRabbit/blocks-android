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
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.DashboardView
import dev.blocks.android.project.ProjectUiPreferences
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

        assertEquals(5, rows.schemaVersion)
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
        val rows = SyncStateRowCodec().encode(TenantSyncState()).copy(schemaVersion = 6)

        val error = runCatching { SyncStateRowCodec().decode(rows) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
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
                commands = listOf(command),
                projectIdMappings = listOf(ProjectIdentityMapping("local-project", project.id)),
                projectsAwaitingNotesBaseline = listOf(project.id),
            ),
            projectDrafts = FormDrafts(projectNotes = mapOf(project.id to "Unsent notes")),
            projectDraftsMigrated = true,
        )

        assertEquals(state, SyncStateRowCodec().decode(SyncStateRowCodec().encode(state)))
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
