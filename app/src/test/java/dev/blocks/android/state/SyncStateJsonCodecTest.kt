package dev.blocks.android.state

import com.google.gson.Gson
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
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
        )

        val rows = SyncStateRowCodec().encode(state)
        val decoded = SyncStateRowCodec().decode(rows)

        assertEquals(1, rows.schemaVersion)
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
        val rows = SyncStateRowCodec().encode(TenantSyncState()).copy(schemaVersion = 2)

        val error = runCatching { SyncStateRowCodec().decode(rows) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
