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
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.BlockLookup
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.RegisteredBlock
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSyncRepositoryTest {
    @Test
    fun api03ParkingReplaysAfterRestartWithItsOriginalIdentityAndCreatesNoLegacyCommand() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { offline = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        val firstResult = first.queueParking(
            SESSION_A,
            PROJECT_ID,
            ParkingDraft("Here", "Next", "Remember"),
            operationId = "project-park-key",
        )

        assertEquals(1, firstResult.pendingProjectOperations)
        assertEquals(0, firstResult.pendingLegacyOperations)
        assertEquals("project-park-key", state.loadSyncState(TENANT_A).projectCache.commands.single().idempotencyKey)
        assertTrue(state.loadSyncState(TENANT_A).projectCache.commands.single().deliveryUncertain)
        assertEquals("Here", drafts.load(TENANT_A).projectParking[PROJECT_ID]?.location)

        service.offline = false
        val recreated = repository(service, drafts, state)
        recreated.activate(SESSION_A)
        val final = recreated.backgroundSync(SESSION_A)

        assertEquals(listOf("project-park-key", "project-park-key"), service.parkingKeys)
        assertEquals(0, final.pendingProjectOperations)
        assertTrue("project-park-key" in final.cache.retiredOperationIds)
        assertTrue(drafts.load(TENANT_A).projectParking.isEmpty())
        assertTrue(state.loadSyncState(TENANT_A).commands.isEmpty())
        val sendsBeforeReuse = service.parkingKeys.size
        assertTrue(
            runCatching {
                recreated.queueParking(
                    SESSION_A,
                    PROJECT_ID,
                    ParkingDraft("Here", "Next", "Remember"),
                    operationId = "project-park-key",
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(sendsBeforeReuse, service.parkingKeys.size)
    }

    @Test
    fun advancesCursorDurablyAcrossLegacyAndUnknownEventsBeforeRefreshingPages() {
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState().copy(changeCursor = "10"))
        val observer = RecordingLegacyReplay()
        val service = FakeProjectService().apply {
            changePages += ChangePage(
                events = listOf(
                    event("11", "session.closed", mapOf("sessionId" to "legacy-session")),
                    event("12", "future.event", mapOf("future" to true)),
                ),
                nextCursor = "12",
                hasMore = true,
            )
            changePages += ChangePage(
                events = listOf(event("13", "project.notes_saved", mapOf("projectId" to PROJECT_ID))),
                nextCursor = "13",
                hasMore = false,
            )
        }
        val repository = ProjectSyncRepository(service, observer, MemoryDrafts(), state)
        repository.activate(SESSION_A)

        val result = repository.backgroundSync(SESSION_A)

        assertEquals("13", result.cursor)
        assertEquals(listOf("session.closed", "future.event", "project.notes_saved"), observer.observed)
        assertTrue(state.saved.any { it.changeCursor == "12" })
        assertEquals("Server notes", result.cache.pages.single().notes.body)
        assertTrue(result.cache.stalePageIds.isEmpty())
    }

    @Test
    fun unresolvedPilotOperationUsesTheOriginalLegacyEndpointIdentityAfterUpgrade() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        val active = WorkSession(
            id = "legacy-session",
            projectId = PROJECT_ID,
            state = WorkSessionState.ACTIVE,
            coreOutcome = "Legacy core",
            rowVersion = 7,
        )
        state.saveSyncState(
            TENANT_A,
            loadedState().copy(
                reentry = Reentry(active, null),
                reentryLoaded = true,
                commands = listOf(
                    QueuedCommand(
                        operation = OfflineOperation.PARK,
                        targetId = active.id,
                        projectId = active.projectId,
                        idempotencyKey = "original-pilot-key",
                        ifMatch = 7,
                        workSession = active,
                        parking = ParkingRecord("Legacy here", "Legacy next", "Legacy reminder"),
                    ),
                ),
            ),
        )
        val legacy = RecordingLegacyBlocks(active)
        val replayer = LegacyOperationReplayer(legacy, drafts, state)
        val repository = ProjectSyncRepository(FakeProjectService(), replayer, drafts, state)
        repository.activate(SESSION_A)

        val result = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("original-pilot-key"), legacy.keys)
        assertEquals(listOf(7), legacy.versions)
        assertEquals(0, result.pendingLegacyOperations)
        assertTrue("original-pilot-key" in state.loadSyncState(TENANT_A).retiredIdempotencyKeys)
        assertTrue(state.loadSyncState(TENANT_A).projectCache.commands.isEmpty())
    }

    @Test
    fun notesConflictRetainsDraftAndCommandWhileCachingAuthoritativePage() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { notesConflict = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        val result = repository.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Local unsent notes",
            operationId = "notes-operation",
        )

        assertTrue(result.reconciliationRequired)
        val command = result.cache.commands.single()
        assertEquals(ProjectMutationStatus.REQUIRES_RECONCILIATION, command.status)
        assertEquals("Local unsent notes", command.notesBody)
        assertEquals(3, command.ifMatch)
        assertEquals("Local unsent notes", drafts.load(TENANT_A).projectNotes[PROJECT_ID])
        assertEquals("Server notes", result.cache.pages.single().notes.body)
        assertEquals("Local unsent notes", result.conflict?.localNotes)
        assertEquals(4, result.conflict?.authoritativePage?.notes?.rowVersion)
    }

    @Test
    fun conflictCannotRetryFromAStaleCacheUntilAuthoritativeRefreshSucceeds() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply {
            notesConflict = true
            conflictPageUnavailable = true
        }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        val unavailable = repository.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Retained local notes",
            operationId = "notes-operation",
        )

        assertFalse(unavailable.conflict?.canRetry == true)
        assertEquals(null, unavailable.conflict?.authoritativePage)
        assertTrue(
            runCatching {
                repository.retryConflict(SESSION_A, "notes-operation", "unsafe-retry")
            }.exceptionOrNull() is IllegalArgumentException,
        )

        service.conflictPageUnavailable = false
        val refreshed = repository.backgroundSync(SESSION_A)

        assertTrue(refreshed.conflict?.canRetry == true)
        assertEquals(4, refreshed.conflict?.authoritativePage?.notes?.rowVersion)
    }

    @Test
    fun conflictRetryAfterRestartUsesAuthoritativeVersionAndFreshIdentityThenAdvancesQueue() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { notesConflict = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)
        first.queueSaveNotes(SESSION_A, PROJECT_ID, 3, "Local notes", "old-notes-operation")
        first.queueRenameProject(SESSION_A, PROJECT_ID, 1, "Queued rename", "rename-operation")

        service.notesConflict = false
        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val resolved = restarted.retryConflict(SESSION_A, "old-notes-operation", "fresh-notes-operation")

        assertEquals(listOf(4), service.savedNotesVersions)
        assertEquals(listOf("Local notes"), service.savedNotesBodies)
        assertEquals(listOf("Queued rename"), service.renamedNames)
        assertTrue(resolved.cache.commands.isEmpty())
        assertTrue("old-notes-operation" in resolved.cache.retiredOperationIds)
        assertTrue("fresh-notes-operation" in resolved.cache.retiredOperationIds)
        assertTrue("rename-operation" in resolved.cache.retiredOperationIds)
        assertFalse(resolved.reconciliationRequired)
    }

    @Test
    fun lifecycleConflictCanBeDiscardedExplicitlyAndTheFollowingCommandStillRuns() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { projectConflict = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)
        val conflict = repository.queueArchiveProject(SESSION_A, PROJECT_ID, 1, "archive-operation")
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "After archive", "notes-after-archive")

        assertEquals(ProjectMutationType.ARCHIVE_PROJECT, conflict.conflict?.type)
        service.projectConflict = false
        val resolved = repository.discardConflict(SESSION_A, "archive-operation")

        assertTrue(resolved.cache.commands.isEmpty())
        assertEquals(listOf("After archive"), service.savedNotesBodies)
        assertTrue("archive-operation" in resolved.cache.retiredOperationIds)
        assertTrue("notes-after-archive" in resolved.cache.retiredOperationIds)
    }

    @Test
    fun renameConflictPresentsBothNamesAndRetriesWithAFreshOperationIdentity() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { projectConflict = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        val conflict = repository.queueRenameProject(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 1,
            name = "My retained name",
            operationId = "old-rename-operation",
        )

        assertEquals("My retained name", conflict.conflict?.localProjectName)
        assertEquals("Project", conflict.conflict?.authoritativePage?.project?.name)
        service.projectConflict = false
        val resolved = repository.retryConflict(
            SESSION_A,
            "old-rename-operation",
            "fresh-rename-operation",
        )

        assertEquals(listOf("My retained name"), service.renamedNames)
        assertTrue("old-rename-operation" in resolved.cache.retiredOperationIds)
        assertTrue("fresh-rename-operation" in resolved.cache.retiredOperationIds)
        assertTrue(resolved.cache.commands.isEmpty())
    }

    @Test
    fun authoritativeDiscoveryRetiresAmbiguousSuccessBeforeSamePayloadCanUseANewIdentity() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { notesAmbiguousAfterSuccess = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        val ambiguous = first.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Accepted despite lost response",
            operationId = "ambiguous-operation",
        )
        assertTrue(ambiguous.cache.commands.single().deliveryUncertain)
        assertEquals(listOf("Accepted despite lost response"), service.savedNotesBodies)

        val discovered = state.loadSyncState(TENANT_A)
        state.saveSyncState(
            TENANT_A,
            discovered.copy(
                projectCache = discovered.projectCache.copy(
                    pages = listOf(
                        page().copy(
                            notes = page().notes.copy(body = "Accepted despite lost response", rowVersion = 5),
                        ),
                    ),
                ),
            ),
        )
        service.notesAmbiguousAfterSuccess = false
        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val reconciled = restarted.backgroundSync(SESSION_A)

        assertEquals(1, service.savedNotesBodies.size)
        assertTrue("ambiguous-operation" in reconciled.cache.retiredOperationIds)
        assertTrue(
            runCatching {
                restarted.queueSaveNotes(
                    SESSION_A,
                    PROJECT_ID,
                    5,
                    "Accepted despite lost response",
                    "ambiguous-operation",
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        val later = restarted.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            5,
            "Accepted despite lost response",
            "later-distinct-operation",
        )
        assertTrue(later.cache.commands.isEmpty())
        assertEquals(2, service.savedNotesBodies.size)
    }

    @Test
    fun acknowledgingAnOlderNotesWriteDoesNotDiscardTheNewerDraft() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { offline = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 3, "First edit", "notes-one")
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "Second edit", "notes-two")
        service.offline = false
        service.notesFailureAfterSuccessfulSaves = 1

        val partiallySynced = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("First edit"), service.savedNotesBodies)
        assertEquals("Second edit", drafts.load(TENANT_A).projectNotes[PROJECT_ID])
        assertEquals(listOf("notes-two"), partiallySynced.cache.commands.map { it.operationId })

        service.notesFailureAfterSuccessfulSaves = null
        val complete = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("First edit", "Second edit"), service.savedNotesBodies)
        assertTrue(drafts.load(TENANT_A).projectNotes.isEmpty())
        assertTrue(complete.cache.commands.isEmpty())
    }

    @Test
    fun unavailableAuthenticationRetainsQueuedWorkUntilAForegroundCredentialRefresh() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { authenticationRequired = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        val error = runCatching {
            repository.queueSaveNotes(
                SESSION_A,
                PROJECT_ID,
                rowVersion = 3,
                body = "Keep until sign-in refresh",
                operationId = "auth-refresh-key",
            )
        }.exceptionOrNull()

        assertTrue(error is ServiceException && error.status == 401)
        val deferred = repository.snapshot(SESSION_A)
        assertEquals(listOf("auth-refresh-key"), deferred.cache.commands.map { it.operationId })
        assertFalse(deferred.reconciliationRequired)
        assertEquals("Keep until sign-in refresh", drafts.load(TENANT_A).projectNotes[PROJECT_ID])

        service.authenticationRequired = false
        val completed = repository.backgroundSync(SESSION_A.copy(identityToken = "fresh-token"))

        assertTrue(completed.cache.commands.isEmpty())
        assertTrue("auth-refresh-key" in completed.cache.retiredOperationIds)
        assertTrue(drafts.load(TENANT_A).projectNotes.isEmpty())
    }

    @Test
    fun tenantSwitchAndConfirmedMembershipRevocationEraseOnlyTheDepartedPartition() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        state.saveSyncState(TENANT_B, loadedState(TENANT_B))
        drafts.save(TENANT_A, FormDrafts(projectNotes = mapOf(PROJECT_ID to "Tenant A")))
        drafts.save(TENANT_B, FormDrafts(projectNotes = mapOf(PROJECT_ID to "Tenant B")))
        val service = FakeProjectService()
        val repository = repository(service, drafts, state)

        repository.activate(SESSION_A)
        repository.activate(SESSION_B)

        assertEquals(TenantSyncState(), state.loadSyncState(TENANT_A))
        assertEquals(FormDrafts(), drafts.load(TENANT_A))
        assertFalse(state.loadSyncState(TENANT_B) == TenantSyncState())
        assertEquals("Tenant B", drafts.load(TENANT_B).projectNotes[PROJECT_ID])

        service.membershipRevoked = true
        val error = runCatching { repository.activeProjects(SESSION_B) }.exceptionOrNull()

        assertTrue(error is MembershipRevokedException)
        assertEquals(TenantSyncState(), state.loadSyncState(TENANT_B))
        assertEquals(FormDrafts(), drafts.load(TENANT_B))
    }

    private fun repository(service: FakeProjectService, drafts: MemoryDrafts, state: MemoryState) =
        ProjectSyncRepository(service, NoOpLegacyQueueReplay, drafts, state)

    private fun loadedState(tenantId: String = TENANT_A) = TenantSyncState(
        projectCache = ProjectCacheState(
            activeProjects = listOf(project(tenantId = tenantId)),
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            pages = listOf(page(tenantId)),
            blocksLoaded = true,
        ),
    )

    private class MemoryState : SyncStateStore {
        private val values = mutableMapOf<String, TenantSyncState>()
        val saved = mutableListOf<TenantSyncState>()
        override fun loadSyncState(tenantId: String) = values[tenantId] ?: TenantSyncState()
        override fun saveSyncState(tenantId: String, state: TenantSyncState) {
            values[tenantId] = state
            saved += state
        }
        override fun clearSyncState(tenantId: String) {
            values.remove(tenantId)
        }
    }

    private class MemoryDrafts : DraftStore {
        private val values = mutableMapOf<String, FormDrafts>()
        override fun load(tenantId: String) = values[tenantId] ?: FormDrafts()
        override fun save(tenantId: String, drafts: FormDrafts) {
            values[tenantId] = drafts
        }
        override fun clear(tenantId: String) {
            values.remove(tenantId)
        }
    }

    private class RecordingLegacyReplay : LegacyQueueReplay {
        val observed = mutableListOf<String>()
        override fun observe(state: TenantSyncState, event: ChangeEvent): TenantSyncState {
            observed += event.eventType
            return state
        }
        override fun replay(session: Session, initial: TenantSyncState) = initial
    }

    private class FakeProjectService : ProjectService {
        var offline = false
        var notesConflict = false
        var projectConflict = false
        var conflictPageUnavailable = false
        var notesAmbiguousAfterSuccess = false
        var membershipRevoked = false
        var authenticationRequired = false
        var notesFailureAfterSuccessfulSaves: Int? = null
        val parkingKeys = mutableListOf<String>()
        val savedNotesBodies = mutableListOf<String>()
        val savedNotesVersions = mutableListOf<Int>()
        val renamedNames = mutableListOf<String>()
        val changePages = ArrayDeque<ChangePage>()

        override fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary> {
            failIfNeeded()
            return if (lifecycle == ProjectLifecycle.ACTIVE) listOf(project(tenantId = session.tenantId)) else emptyList()
        }

        override fun createProject(session: Session, name: String, idempotencyKey: String) =
            project(name = name, tenantId = session.tenantId)

        override fun projectPage(session: Session, projectId: String): ProjectPage {
            failIfNeeded()
            if (conflictPageUnavailable) throw IOException("authoritative page unavailable")
            return page(session.tenantId)
        }

        override fun renameProject(session: Session, projectId: String, rowVersion: Int, name: String): ProjectSummary {
            failIfNeeded()
            if (projectConflict) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "Project changed"))
            renamedNames += name
            return project(name = name, rowVersion = rowVersion + 1, tenantId = session.tenantId)
        }

        override fun archiveProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary {
            failIfNeeded()
            if (projectConflict) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "Project changed"))
            return project(
                lifecycle = ProjectLifecycle.ARCHIVED,
                rowVersion = rowVersion + 1,
                tenantId = session.tenantId,
            )
        }

        override fun reopenProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary {
            failIfNeeded()
            if (projectConflict) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "Project changed"))
            return project(rowVersion = rowVersion + 1, tenantId = session.tenantId)
        }

        override fun notes(session: Session, projectId: String) = page(session.tenantId).notes

        override fun saveNotes(session: Session, projectId: String, rowVersion: Int, body: String): ProjectNotes {
            failIfNeeded()
            if (notesConflict) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "Notes changed"))
            notesFailureAfterSuccessfulSaves?.let { successfulLimit ->
                if (savedNotesBodies.size >= successfulLimit) throw IOException("offline")
            }
            savedNotesBodies += body
            savedNotesVersions += rowVersion
            if (notesAmbiguousAfterSuccess) throw IOException("response lost after acceptance")
            return ProjectNotes(projectId, body, rowVersion + 1, NOW)
        }

        override fun addParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            idempotencyKey: String,
        ): ProjectParking {
            parkingKeys += idempotencyKey
            failIfNeeded()
            return ProjectParking(
                id = "parking-id",
                projectId = projectId,
                location = parking.location,
                nextAction = parking.nextAction,
                mustNotForget = parking.mustNotForget,
                authoredByUserId = "user-id",
                createdAt = NOW,
                supersedesId = supersedesId,
            )
        }

        override fun parking(session: Session, projectId: String) = listOfNotNull(page(session.tenantId).newestParking)
        override fun blocks(session: Session): List<PhysicalBlock> {
            failIfNeeded()
            return emptyList()
        }
        override fun registerBlock(session: Session, label: String, idempotencyKey: String) =
            RegisteredBlock(PhysicalBlock("block-id", label, "registered"), "tag-token")
        override fun bindBlock(session: Session, blockId: String, projectId: String, idempotencyKey: String) =
            BlockBinding("binding-id", blockId, projectId)
        override fun unbindBlock(session: Session, blockId: String) = Unit
        override fun lookupBlock(session: Session, tagToken: String) = BlockLookup("block-id", "Block", PROJECT_ID)
        override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
            failIfNeeded()
            return if (changePages.isEmpty()) ChangePage(emptyList(), cursor, false) else changePages.removeFirst()
        }

        private fun failIfNeeded() {
            if (membershipRevoked) throw ServiceException(403, ApiError(ApiErrorCode.UNAUTHORIZED, "Revoked"))
            if (authenticationRequired) {
                throw ServiceException(401, ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required"))
            }
            if (offline) throw IOException("offline")
        }
    }

    private class RecordingLegacyBlocks(private val active: WorkSession) : BlocksRepository {
        val keys = mutableListOf<String>()
        val versions = mutableListOf<Int>()
        private var reentry = Reentry(active, null)
        override fun login(identityToken: String) = SESSION_A
        override fun projects(session: Session) = emptyList<Project>()
        override fun createProject(session: Session, name: String) = error("Not used")
        override fun current(session: Session) = reentry
        override fun pickup(session: Session, request: PickupRequest, idempotencyKey: String) = error("Not used")
        override fun park(
            session: Session,
            workSession: WorkSession,
            record: ParkingRecord,
            idempotencyKey: String,
        ): Reentry {
            keys += idempotencyKey
            versions += workSession.rowVersion
            reentry = Reentry(
                workSession.copy(state = WorkSessionState.PARKED, rowVersion = workSession.rowVersion + 1),
                record.copy(id = "legacy-parking"),
            )
            return reentry
        }
        override fun resume(session: Session, workSession: WorkSession) = error("Not used")
        override fun closeWithContinuation(
            session: Session,
            workSession: WorkSession,
            record: ParkingRecord,
            idempotencyKey: String,
        ) = error("Not used")
        override fun closeOnExistingContinuation(
            session: Session,
            workSession: WorkSession,
            parking: ParkingRecord,
            idempotencyKey: String,
        ) = error("Not used")
        override fun closeWithOutcome(
            session: Session,
            workSession: WorkSession,
            outcomeTier: OutcomeTier,
            idempotencyKey: String,
        ) = error("Not used")
    }

    companion object {
        private const val TENANT_A = "00000000-0000-0000-0000-00000000000a"
        private const val TENANT_B = "00000000-0000-0000-0000-00000000000b"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000003"
        private const val NOW = "2026-09-07T10:00:00Z"
        private val SESSION_A = Session(TENANT_A, "token-a")
        private val SESSION_B = Session(TENANT_B, "token-b")

        private fun project(
            name: String = "Project",
            lifecycle: ProjectLifecycle = ProjectLifecycle.ACTIVE,
            rowVersion: Int = 1,
            tenantId: String = TENANT_A,
        ) = ProjectSummary(PROJECT_ID, tenantId, name, lifecycle, rowVersion, NOW, NOW)

        private fun page(tenantId: String = TENANT_A) = ProjectPage(
            project = project(tenantId = tenantId),
            notes = ProjectNotes(PROJECT_ID, "Server notes", 4, NOW),
            newestParking = null,
        )

        private fun event(id: String, type: String, payload: Map<String, Any?>) =
            ChangeEvent(id, type, "0.1", TENANT_A, payload)
    }
}
