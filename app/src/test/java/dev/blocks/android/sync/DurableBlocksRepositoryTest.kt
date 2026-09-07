package dev.blocks.android.sync

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.PendingMutation
import dev.blocks.android.mvp.PickupDraft
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.QueuedCommandStatus
import dev.blocks.android.mvp.QueuedOfflineException
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.TransitionConflictException
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import java.io.IOException
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableBlocksRepositoryTest {
    @Test
    fun offlineCommandReplaysAfterRestartWithOriginalKeyAndIfMatchThenRetiresIt() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val record = ParkingRecord("Here", "Next", "Remember")
        val active = workSession(rowVersion = 3)
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft(record.location, record.nextAction, record.mustNotForget),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK", active.id, "digest", "park-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(reentry = Reentry(active, null), reentryLoaded = true, changeCursor = "8"),
        )
        val service = RecordingService().apply {
            remoteReentry = Reentry(active, null)
            offline = true
        }
        val first = DurableBlocksRepository(service, drafts, sync)
        val firstSession = first.login("tenant-a")

        val firstError = runCatching { first.park(firstSession, active, record, "park-key") }.exceptionOrNull()
        assertTrue(firstError is QueuedOfflineException)
        assertEquals(3, sync.loadSyncState(TENANT_A).commands.single().ifMatch)
        assertTrue(sync.loadSyncState(TENANT_A).commands.single().deliveryUncertain)

        service.offline = false
        val recreated = DurableBlocksRepository(service, drafts, sync)
        val recreatedSession = recreated.login("tenant-a")
        recreated.synchronize(recreatedSession)

        val sent = service.sent.filter { it.operation == OfflineOperation.PARK }
        assertEquals(listOf("park-key", "park-key"), sent.map { it.idempotencyKey })
        assertEquals(listOf(3, 3), sent.map { it.ifMatch })
        assertTrue(sync.loadSyncState(TENANT_A).commands.isEmpty())
        assertTrue("park-key" in sync.loadSyncState(TENANT_A).retiredIdempotencyKeys)
        assertEquals("Core", sync.loadSyncState(TENANT_A).reentry?.workSession?.coreOutcome)
        assertNull(drafts.load(TENANT_A).pendingMutations["parking"])
        assertEquals(ParkingDraft(), drafts.load(TENANT_A).parking)

        recreated.park(recreatedSession, active, record, "park-key")
        assertEquals(2, service.sent.count { it.operation == OfflineOperation.PARK })
    }

    @Test
    fun persistentQueueDrainsStrictlyInStoredOrder() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(
                reentry = Reentry(workSession("session-1", rowVersion = 1), null),
                reentryLoaded = true,
                commands = listOf(
                    QueuedCommand(
                        operation = OfflineOperation.PARK,
                        targetId = "session-1",
                        projectId = PROJECT_ID,
                        idempotencyKey = "first-key",
                        ifMatch = 1,
                        workSession = workSession("session-1", rowVersion = 1),
                        parking = ParkingRecord("One", "Two", "Three"),
                    ),
                    QueuedCommand(
                        operation = OfflineOperation.CLOSE_WITH_OUTCOME,
                        targetId = "session-2",
                        projectId = PROJECT_ID,
                        idempotencyKey = "second-key",
                        ifMatch = 4,
                        workSession = workSession("session-2", rowVersion = 4),
                        outcomeTier = OutcomeTier.CORE,
                    ),
                ),
            ),
        )
        val service = RecordingService()
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")

        repository.synchronize(session)

        assertEquals(
            listOf(OfflineOperation.PARK, OfflineOperation.CLOSE_WITH_OUTCOME),
            service.sent.map { it.operation },
        )
        assertTrue(sync.loadSyncState(TENANT_A).commands.isEmpty())
    }

    @Test
    fun offlineParkAndClosePersistsAndReplaysAsOneAtomicCommand() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val active = workSession(rowVersion = 6)
        val record = ParkingRecord("Boundary", "Restart here", "Keep this")
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft(record.location, record.nextAction, record.mustNotForget),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK_AND_CLOSE", active.id, "digest", "atomic-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(reentry = Reentry(active, null), reentryLoaded = true, changeCursor = "30"),
        )
        val service = RecordingService().apply {
            remoteReentry = Reentry(active, null)
            offline = true
        }
        val first = DurableBlocksRepository(service, drafts, sync)
        val firstSession = first.login("tenant-a")

        assertTrue(
            runCatching {
                first.closeWithContinuation(firstSession, active, record, "atomic-key")
            }.exceptionOrNull() is QueuedOfflineException,
        )
        assertEquals(
            listOf(OfflineOperation.PARK_AND_CLOSE),
            sync.loadSyncState(TENANT_A).commands.map { it.operation },
        )

        service.offline = false
        val recreated = DurableBlocksRepository(service, drafts, sync)
        val result = recreated.synchronize(recreated.login("tenant-a"))

        assertEquals(WorkSessionState.CLOSED, result.reentry.workSession?.state)
        assertEquals(
            listOf(OfflineOperation.PARK_AND_CLOSE, OfflineOperation.PARK_AND_CLOSE),
            service.sent.map { it.operation },
        )
        assertEquals(listOf(6, 6), service.sent.map { it.ifMatch })
        assertTrue(sync.loadSyncState(TENANT_A).commands.isEmpty())
    }

    @Test
    fun ambiguousParkSuccessIsDiscoveredByReentryAndItsOldKeyCannotRunAgain() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val active = workSession(rowVersion = 2)
        val record = ParkingRecord("Boundary", "Restart", "Context")
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft(record.location, record.nextAction, record.mustNotForget),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK", active.id, "digest", "lost-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(reentry = Reentry(active, null), reentryLoaded = true, changeCursor = "20"),
        )
        val service = RecordingService().apply {
            remoteReentry = Reentry(active, null)
            parkMode = MutationMode.LOST_RESPONSE
            eventCounter = 20
        }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")

        val result = repository.park(session, active, record, "lost-key")

        assertEquals(WorkSessionState.PARKED, result.workSession?.state)
        assertTrue(sync.loadSyncState(TENANT_A).commands.isEmpty())
        assertTrue("lost-key" in sync.loadSyncState(TENANT_A).retiredIdempotencyKeys)
        assertNull(drafts.load(TENANT_A).pendingMutations["parking"])
        val sendsBeforeStaleRetry = service.sent.size
        repository.park(session, active, record, "lost-key")
        assertEquals(sendsBeforeStaleRetry, service.sent.size)
    }

    @Test
    fun ambiguousOutcomeCloseIsResolvedByChangeFeedWhileUnknownEventsAdvanceCursor() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val active = workSession(rowVersion = 5)
        drafts.save(
            TENANT_A,
            FormDrafts(
                pendingMutations = mapOf(
                    "close" to PendingMutation("CLOSE_WITH_OUTCOME", active.id, "digest", "close-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(reentry = Reentry(active, null), reentryLoaded = true, changeCursor = "10"),
        )
        val service = RecordingService().apply {
            remoteReentry = Reentry(active, null)
            eventCounter = 11
            events += event("11", "future.event", mapOf("future" to "value"))
            closeOutcomeMode = MutationMode.LOST_RESPONSE
        }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")

        val result = repository.closeWithOutcome(session, active, OutcomeTier.CORE, "close-key")

        assertNull(result.workSession)
        val stored = sync.loadSyncState(TENANT_A)
        assertTrue(stored.commands.isEmpty())
        assertEquals("12", stored.changeCursor)
        assertTrue("close-key" in stored.retiredIdempotencyKeys)
        assertNull(drafts.load(TENANT_A).pendingMutations["close"])
    }

    @Test
    fun conflictKeepsTheOriginalCommandAndIntentForReconciliation() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val submitted = workSession(rowVersion = 1)
        val authoritative = submitted.copy(rowVersion = 2)
        val record = ParkingRecord("Local", "Local next", "Local context")
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft(record.location, record.nextAction, record.mustNotForget),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK", submitted.id, "digest", "conflict-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(reentry = Reentry(submitted, null), reentryLoaded = true),
        )
        val service = RecordingService().apply {
            remoteReentry = Reentry(authoritative, null)
            parkMode = MutationMode.CONFLICT
        }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")

        val error = runCatching {
            repository.park(session, submitted, record, "conflict-key")
        }.exceptionOrNull()

        assertTrue(error is TransitionConflictException)
        val command = sync.loadSyncState(TENANT_A).commands.single()
        assertEquals(QueuedCommandStatus.REQUIRES_RECONCILIATION, command.status)
        assertEquals(1, command.ifMatch)
        assertEquals("conflict-key", drafts.load(TENANT_A).pendingMutations["parking"]?.idempotencyKey)
        assertEquals(record.location, drafts.load(TENANT_A).parking.location)

        val resolved = repository.resolveReconciliation(session)
        assertTrue(sync.loadSyncState(TENANT_A).commands.isEmpty())
        assertTrue("conflict-key" in sync.loadSyncState(TENANT_A).retiredIdempotencyKeys)
        assertNull(drafts.load(TENANT_A).pendingMutations["parking"])
        assertEquals(record.location, drafts.load(TENANT_A).parking.location)
        assertTrue(resolved.status.message.orEmpty().contains("draft was preserved"))
    }

    @Test
    fun cursorSurvivesRestartAndUnknownEventsNeverBlockAdvancement() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        sync.saveSyncState(TENANT_A, TenantSyncState(changeCursor = "4"))
        val service = RecordingService().apply {
            eventCounter = 5
            events += event("5", "future.first", mapOf("unknown" to true))
        }
        val first = DurableBlocksRepository(service, drafts, sync)
        first.synchronize(first.login("tenant-a"))
        assertEquals("5", sync.loadSyncState(TENANT_A).changeCursor)

        service.eventCounter = 6
        service.events += event("6", "future.second", mapOf("another" to 1))
        val recreated = DurableBlocksRepository(service, drafts, sync)
        recreated.synchronize(recreated.login("tenant-a"))

        assertEquals("6", sync.loadSyncState(TENANT_A).changeCursor)
        assertTrue(service.requestedCursors.none { it == null })
        assertTrue("5" in service.requestedCursors)
    }

    @Test
    fun completedChangePagesStayCommittedWhenTheNextPageIsInterrupted() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val service = RecordingService().apply {
            events += (1..101).map { id -> event(id.toString(), "future.event", emptyMap()) }
            eventCounter = 101
            failChangesFromCall = 2
        }
        val first = DurableBlocksRepository(service, drafts, sync)
        first.synchronize(first.login("tenant-a"))

        assertEquals("100", sync.loadSyncState(TENANT_A).changeCursor)

        service.failChangesFromCall = null
        val recreated = DurableBlocksRepository(service, drafts, sync)
        recreated.synchronize(recreated.login("tenant-a"))

        assertEquals("101", sync.loadSyncState(TENANT_A).changeCursor)
        assertTrue("100" in service.requestedCursors)
    }

    @Test
    fun orphanedPreQueueIdentityIsMigratedByRetiringOnlyItsKeyAndKeepingItsDraft() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        drafts.save(
            TENANT_A,
            FormDrafts(
                pickups = mapOf(PROJECT_ID to PickupDraft(PROJECT_ID, "Preserved core", "", "")),
                pendingMutations = mapOf(
                    "pickup" to PendingMutation("PICKUP", PROJECT_ID, "digest", "legacy-key"),
                ),
            ),
        )
        val service = RecordingService()
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")

        val result = repository.synchronize(session)

        assertNull(drafts.load(TENANT_A).pendingMutations["pickup"])
        assertEquals("Preserved core", drafts.load(TENANT_A).pickups[PROJECT_ID]?.coreOutcome)
        assertTrue("legacy-key" in sync.loadSyncState(TENANT_A).retiredIdempotencyKeys)
        assertTrue(result.status.message.orEmpty().contains("pre-queue"))
    }

    @Test
    fun logoutClearsOnlyTheActiveTenantSyncPartition() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(projects = listOf(Project("a-project", "A")), projectsLoaded = true, changeCursor = "7"),
        )
        sync.saveSyncState(
            TENANT_B,
            TenantSyncState(projects = listOf(Project("b-project", "B")), projectsLoaded = true, changeCursor = "12"),
        )
        val service = RecordingService().apply { offline = true }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val tenantA = repository.login("tenant-a")
        assertEquals("a-project", repository.projects(tenantA).single().id)

        repository.clearLocalState(tenantA)
        assertEquals(TenantSyncState(), sync.loadSyncState(TENANT_A))
        assertTrue(runCatching { repository.projects(tenantA) }.isFailure)
        val tenantB = repository.login("tenant-b")

        assertEquals("b-project", repository.projects(tenantB).single().id)
        assertEquals("12", sync.loadSyncState(TENANT_B).changeCursor)
    }

    @Test
    fun directTenantSwitchInvalidatesThePreviousSessionAndReadsOnlyTheNewPartition() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft("Tenant A", "Secret next", "Secret context"),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK", "session-a", "digest", "tenant-a-key"),
                ),
            ),
        )
        drafts.save(TENANT_B, FormDrafts(projectName = "Tenant B draft"))
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(
                projects = listOf(Project("a-project", "Tenant A project")),
                projectsLoaded = true,
                changeCursor = "7",
                retiredIdempotencyKeys = listOf("tenant-a-key"),
            ),
        )
        sync.saveSyncState(
            TENANT_B,
            TenantSyncState(
                projects = listOf(Project("b-project", "Tenant B project")),
                projectsLoaded = true,
                changeCursor = "12",
                retiredIdempotencyKeys = listOf("tenant-b-key"),
            ),
        )
        val service = RecordingService().apply { offline = true }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val tenantA = repository.login("tenant-a")
        assertEquals("a-project", repository.projects(tenantA).single().id)

        val tenantB = repository.login("tenant-b")

        assertTrue(runCatching { repository.projects(tenantA) }.isFailure)
        assertEquals(TenantSyncState(), sync.loadSyncState(TENANT_A))
        assertEquals(FormDrafts(), drafts.load(TENANT_A))
        assertEquals("b-project", repository.projects(tenantB).single().id)
        assertEquals("Tenant B draft", drafts.load(TENANT_B).projectName)
        assertEquals(listOf("tenant-b-key"), sync.loadSyncState(TENANT_B).retiredIdempotencyKeys)
        assertEquals("12", sync.loadSyncState(TENANT_B).changeCursor)
    }

    @Test
    fun membershipRevocationErasesDraftQueueCursorCacheAndInvalidatesTheSession() {
        val drafts = MemoryDraftStore()
        val sync = MemorySyncStore()
        val active = workSession(rowVersion = 9)
        val record = ParkingRecord("Sensitive boundary", "Sensitive next", "Sensitive context")
        drafts.save(
            TENANT_A,
            FormDrafts(
                parking = ParkingDraft(record.location, record.nextAction, record.mustNotForget),
                pendingMutations = mapOf(
                    "parking" to PendingMutation("PARK", active.id, "digest", "revoked-key"),
                ),
            ),
        )
        sync.saveSyncState(
            TENANT_A,
            TenantSyncState(
                projects = listOf(Project(PROJECT_ID, "Sensitive project")),
                projectsLoaded = true,
                reentry = Reentry(active, null),
                reentryLoaded = true,
                changeCursor = "44",
            ),
        )
        val service = RecordingService().apply { remoteReentry = Reentry(active, null) }
        val repository = DurableBlocksRepository(service, drafts, sync)
        val session = repository.login("tenant-a")
        service.membershipRevoked = true

        val error = runCatching {
            repository.park(session, active, record, "revoked-key")
        }.exceptionOrNull()

        assertTrue(error is MembershipRevokedException)
        assertEquals(TenantSyncState(), sync.loadSyncState(TENANT_A))
        assertEquals(FormDrafts(), drafts.load(TENANT_A))
        assertTrue(runCatching { repository.current(session) }.isFailure)
    }

    private class MemoryDraftStore : DraftStore {
        private val values = mutableMapOf<String, FormDrafts>()

        override fun load(tenantId: String): FormDrafts = values[tenantId] ?: FormDrafts()

        override fun save(tenantId: String, drafts: FormDrafts) {
            if (drafts == FormDrafts()) values.remove(tenantId) else values[tenantId] = drafts
        }

        override fun clear(tenantId: String) {
            values.remove(tenantId)
        }
    }

    private class MemorySyncStore : SyncStateStore {
        private val values = mutableMapOf<String, TenantSyncState>()

        override fun loadSyncState(tenantId: String): TenantSyncState = values[tenantId] ?: TenantSyncState()

        override fun saveSyncState(tenantId: String, state: TenantSyncState) {
            if (state == TenantSyncState()) values.remove(tenantId) else values[tenantId] = state
        }

        override fun clearSyncState(tenantId: String) {
            values.remove(tenantId)
        }
    }

    private enum class MutationMode { SUCCESS, OFFLINE, LOST_RESPONSE, CONFLICT }

    private data class SentMutation(
        val operation: OfflineOperation,
        val idempotencyKey: String,
        val ifMatch: Int?,
    )

    private class RecordingService : BlocksRepository {
        var offline = false
        var membershipRevoked = false
        var remoteReentry = Reentry(null, null)
        var parkMode = MutationMode.SUCCESS
        var closeOutcomeMode = MutationMode.SUCCESS
        var eventCounter = 0
        val events = mutableListOf<ChangeEvent>()
        val sent = mutableListOf<SentMutation>()
        val requestedCursors = mutableListOf<String?>()
        var changeCalls = 0
        var failChangesFromCall: Int? = null

        override fun login(identityToken: String): Session = Session(
            if (identityToken == "tenant-b") TENANT_B else TENANT_A,
            identityToken,
        )

        override fun projects(session: Session): List<Project> {
            rejectRevokedMembership()
            if (offline) throw IOException("offline")
            return listOf(Project(PROJECT_ID, "Project"))
        }

        override fun createProject(session: Session, name: String): Project {
            rejectRevokedMembership()
            return Project(PROJECT_ID, name)
        }

        override fun current(session: Session): Reentry {
            rejectRevokedMembership()
            if (offline) throw IOException("offline")
            return remoteReentry
        }

        override fun pickup(session: Session, request: PickupRequest, idempotencyKey: String): PickupResult {
            rejectRevokedMembership()
            sent += SentMutation(OfflineOperation.PICKUP, idempotencyKey, null)
            if (offline) throw IOException("offline")
            val started = WorkSession(
                id = "started-session",
                projectId = request.projectId,
                state = WorkSessionState.ACTIVE,
                coreOutcome = request.coreOutcome,
                fullOutcome = request.fullOutcome,
                polishOutcome = request.polishOutcome,
                rowVersion = 1,
            )
            remoteReentry = Reentry(started, null)
            addEvent("session.started", mapOf("sessionId" to started.id, "projectId" to started.projectId))
            return PickupResult.Started(remoteReentry)
        }

        override fun park(
            session: Session,
            workSession: WorkSession,
            record: ParkingRecord,
            idempotencyKey: String,
        ): Reentry {
            rejectRevokedMembership()
            sent += SentMutation(OfflineOperation.PARK, idempotencyKey, workSession.rowVersion)
            if (offline || parkMode == MutationMode.OFFLINE) throw IOException("offline")
            if (parkMode == MutationMode.CONFLICT) throw TransitionConflictException("row version changed")
            remoteReentry = Reentry(
                workSession.copy(state = WorkSessionState.PARKED, rowVersion = workSession.rowVersion + 1),
                record.copy(id = "parking-${workSession.id}"),
            )
            addEvent(
                "session.parked",
                mapOf("sessionId" to workSession.id, "parkingRecordId" to "parking-${workSession.id}"),
            )
            if (parkMode == MutationMode.LOST_RESPONSE) throw IOException("response lost")
            return remoteReentry
        }

        override fun resume(session: Session, workSession: WorkSession): Reentry {
            rejectRevokedMembership()
            if (offline) throw IOException("offline")
            remoteReentry = Reentry(
                workSession.copy(state = WorkSessionState.ACTIVE, rowVersion = workSession.rowVersion + 1),
                remoteReentry.parking,
            )
            return remoteReentry
        }

        override fun closeWithContinuation(
            session: Session,
            workSession: WorkSession,
            record: ParkingRecord,
            idempotencyKey: String,
        ): Reentry {
            rejectRevokedMembership()
            sent += SentMutation(OfflineOperation.PARK_AND_CLOSE, idempotencyKey, workSession.rowVersion)
            if (offline) throw IOException("offline")
            remoteReentry = Reentry(
                workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
                record.copy(id = "parking-${workSession.id}"),
            )
            return remoteReentry
        }

        override fun closeOnExistingContinuation(
            session: Session,
            workSession: WorkSession,
            parking: ParkingRecord,
            idempotencyKey: String,
        ): Reentry {
            rejectRevokedMembership()
            sent += SentMutation(
                OfflineOperation.CLOSE_EXISTING_CONTINUATION,
                idempotencyKey,
                workSession.rowVersion,
            )
            if (offline) throw IOException("offline")
            remoteReentry = Reentry(
                workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
                parking,
            )
            return remoteReentry
        }

        override fun closeWithOutcome(
            session: Session,
            workSession: WorkSession,
            outcomeTier: OutcomeTier,
            idempotencyKey: String,
        ): Reentry {
            rejectRevokedMembership()
            sent += SentMutation(OfflineOperation.CLOSE_WITH_OUTCOME, idempotencyKey, workSession.rowVersion)
            if (offline || closeOutcomeMode == MutationMode.OFFLINE) throw IOException("offline")
            if (closeOutcomeMode == MutationMode.CONFLICT) throw TransitionConflictException("row version changed")
            remoteReentry = Reentry(null, null)
            addEvent(
                "session.closed",
                mapOf("sessionId" to workSession.id, "outcomeTier" to outcomeTier.wireValue),
            )
            if (closeOutcomeMode == MutationMode.LOST_RESPONSE) throw IOException("response lost")
            return remoteReentry
        }

        override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
            rejectRevokedMembership()
            requestedCursors += cursor
            changeCalls += 1
            failChangesFromCall?.let { if (changeCalls >= it) throw IOException("change feed interrupted") }
            if (offline) throw IOException("offline")
            val cursorValue = cursor?.let(::BigInteger) ?: BigInteger.ZERO
            val available = events.filter { BigInteger(it.eventId) > cursorValue }.take(limit)
            val next = available.lastOrNull()?.eventId ?: cursor
            val hasMore = events.any { BigInteger(it.eventId) > BigInteger(next ?: "0") }
            return ChangePage(available, next, hasMore)
        }

        private fun rejectRevokedMembership() {
            if (membershipRevoked) {
                throw ServiceException(403, ApiError(ApiErrorCode.UNAUTHORIZED, "Membership revoked"))
            }
        }

        private fun addEvent(type: String, payload: Map<String, Any?>) {
            eventCounter += 1
            events += event(eventCounter.toString(), type, payload)
        }
    }

    private companion object {
        const val TENANT_A = "tenant-a"
        const val TENANT_B = "tenant-b"
        const val PROJECT_ID = "project-1"

        fun workSession(id: String = "session-1", rowVersion: Int) = WorkSession(
            id = id,
            projectId = PROJECT_ID,
            state = WorkSessionState.ACTIVE,
            coreOutcome = "Core",
            rowVersion = rowVersion,
        )

        fun event(id: String, type: String, payload: Map<String, Any?>) = ChangeEvent(
            eventId = id,
            eventType = type,
            eventVersion = "0.1",
            tenantId = TENANT_A,
            payload = payload,
        )
    }
}
