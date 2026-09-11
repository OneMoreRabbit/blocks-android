package dev.blocks.android.sync

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.IncompatibleServiceApiException
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
import dev.blocks.android.project.ProjectIdentityMapping
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSyncRepositoryTest {
    @Test
    fun emptyCacheJourneyIsImmediatelyUsableOfflineAndConvergesInDependencyOrderAfterRestart() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        val service = FakeProjectService().apply { offline = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        val created = first.queueCreateProject(SESSION_A, "Offline project", "create-project-key")
        val provisionalId = created.cache.activeProjects.single().id
        assertEquals("create-project-key", provisionalId)
        assertEquals("Offline project", first.projectPage(SESSION_A, provisionalId).project.name)
        state.saveSyncState(
            TENANT_A,
            state.loadSyncState(TENANT_A).copy(
                projectCache = state.loadSyncState(TENANT_A).projectCache.copy(
                    blocks = listOf(PhysicalBlock("local-block", "Desk", "bound", provisionalId)),
                    blocksLoaded = true,
                ),
            ),
        )
        first.queueSaveNotes(SESSION_A, provisionalId, 0, "Offline notes", "save-notes-key")
        first.queueParking(
            SESSION_A,
            provisionalId,
            ParkingDraft("Workbench", "Fit bracket", "Bring screws"),
            operationId = "append-parking-key",
        )

        assertTrue(service.calls.isEmpty())
        val local = first.snapshot(SESSION_A)
        assertEquals("Offline notes", local.cache.pages.single().notes.body)
        assertEquals("Workbench", local.cache.pages.single().newestParking?.location)
        assertEquals(
            listOf(null, "create-project-key", "save-notes-key"),
            local.cache.commands.map { it.dependsOnOperationId },
        )

        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        assertEquals("Offline notes", restarted.projectPage(SESSION_A, provisionalId).notes.body)
        assertEquals(3, restarted.snapshot(SESSION_A).pendingProjectOperations)

        service.offline = false
        val converged = restarted.backgroundSync(SESSION_A)

        assertEquals(
            listOf("create", "page", "notes", "parking"),
            service.calls.filter { it in setOf("create", "page", "notes", "parking") }.take(4),
        )
        assertEquals(listOf("create-project-key"), service.createClientIds)
        assertEquals(listOf("append-parking-key"), service.parkingClientIds)
        assertNotNull(service.parkingOccurredAt.single())
        assertTrue(converged.cache.commands.isEmpty())
        assertEquals(PROJECT_ID, converged.cache.pages.single().project.id)
        assertEquals("Offline notes", converged.cache.pages.single().notes.body)
        assertEquals("parking-1", converged.cache.pages.single().newestParking?.id)
        assertEquals(
            listOf(ProjectIdentityMapping("create-project-key", PROJECT_ID)),
            converged.cache.projectIdMappings,
        )
        val identityRewrite = state.saved.first {
            it.projectCache.projectIdMappings.any { mapping -> mapping.provisionalId == provisionalId } &&
                it.projectCache.commands.isNotEmpty()
        }
        assertTrue(identityRewrite.projectCache.pages.all { it.project.id == PROJECT_ID })
        assertTrue(identityRewrite.projectCache.commands.all {
            it.targetId == PROJECT_ID && (it.projectId == null || it.projectId == PROJECT_ID)
        })
        assertEquals(PROJECT_ID, identityRewrite.projectCache.blocks.single().boundProjectId)
        assertEquals("Offline notes", identityRewrite.projectDrafts.projectNotes[PROJECT_ID])
        assertEquals("Workbench", identityRewrite.projectDrafts.projectParking[PROJECT_ID]?.location)
        assertTrue(provisionalId !in identityRewrite.projectDrafts.projectNotes)
        assertTrue(provisionalId !in identityRewrite.projectDrafts.projectParking)
        assertTrue(restarted.projectDrafts(SESSION_A).projectNotes.isEmpty())
        assertTrue(restarted.projectDrafts(SESSION_A).projectParking.isEmpty())
        assertTrue(
            listOf("create-project-key", "save-notes-key", "append-parking-key")
                .all { it in converged.cache.retiredOperationIds },
        )
    }

    @Test
    fun olderRouteApiRejectingApi04FieldsDefersWithoutRollingBackTheEmptyCacheJourney() {
        val state = MemoryState()
        val service = FakeProjectService().apply { rejectApi04Mutation = true }
        val repository = repository(service, MemoryDrafts(), state)
        repository.activate(SESSION_A)

        repository.queueCreateProject(SESSION_A, "Saved locally", "old-route-create-key")
        repository.queueSaveNotes(
            SESSION_A,
            "old-route-create-key",
            0,
            "Saved old-route notes",
            "old-route-notes-key",
        )
        repository.queueParking(
            SESSION_A,
            "old-route-create-key",
            ParkingDraft("Old route shelf", "Keep working", "Retain local state"),
            operationId = "old-route-parking-key",
        )
        val deferred = repository.backgroundSync(SESSION_A)

        assertTrue(deferred.cache.activeProjects.any { it.name == "Saved locally" })
        val localPage = repository.projectPage(SESSION_A, "old-route-create-key")
        assertEquals("Saved locally", localPage.project.name)
        assertEquals("Saved old-route notes", localPage.notes.body)
        assertEquals("Old route shelf", localPage.newestParking?.location)
        assertEquals(
            listOf("old-route-create-key", "old-route-notes-key", "old-route-parking-key"),
            deferred.cache.commands.map { it.operationId },
        )
        assertTrue(deferred.cache.commands.none { it.deliveryUncertain })
        assertFalse(deferred.reconciliationRequired)
        assertEquals(listOf("list-active", "create"), service.calls.take(2))
    }

    @Test
    fun noEndpointAndTimeoutKeepTheCompleteEmptyCacheJourneyLocallyUsableAndQuietlyPending() {
        listOf(
            ConnectException("no endpoint"),
            SocketTimeoutException("request timed out"),
        ).forEachIndexed { index, transportFailure ->
            val state = MemoryState()
            val service = FakeProjectService().apply { deferredFailure = transportFailure }
            val repository = repository(service, MemoryDrafts(), state)
            repository.activate(SESSION_A)
            val createId = "failure-$index-create"

            repository.queueCreateProject(SESSION_A, "Locally useful $index", createId)
            repository.queueSaveNotes(SESSION_A, createId, 0, "Offline notes $index", "failure-$index-notes")
            repository.queueParking(
                SESSION_A,
                createId,
                ParkingDraft("Shelf $index", "Continue $index", "Remember $index"),
                operationId = "failure-$index-parking",
            )
            val deferred = repository.backgroundSync(SESSION_A)

            assertEquals(3, deferred.pendingProjectOperations)
            assertEquals("Locally useful $index", deferred.cache.activeProjects.single().name)
            assertEquals("Offline notes $index", repository.projectPage(SESSION_A, createId).notes.body)
            assertEquals("Shelf $index", repository.projectPage(SESSION_A, createId).newestParking?.location)
            assertTrue(deferred.cache.commands.none { it.deliveryUncertain })
            assertTrue("create" !in service.calls)
        }
    }

    @Test
    fun everyOrdinaryMutationPersistsItsProjectionAndDependencyBeforeTransport() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService().apply { offline = true }
        val repository = repository(service, MemoryDrafts(), state)
        repository.activate(SESSION_A)
        val savesBeforeMutations = state.saved.size

        repository.queueRenameProject(SESSION_A, PROJECT_ID, 1, "Renamed", "rename-local")
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "Local notes", "notes-local")
        repository.queueParking(
            SESSION_A,
            PROJECT_ID,
            ParkingDraft("Bench", "Tighten clamp", "Keep shim"),
            operationId = "parking-local",
        )
        repository.queueArchiveProject(SESSION_A, PROJECT_ID, 1, "archive-local")
        val reopened = repository.queueReopenProject(SESSION_A, PROJECT_ID, 1, "reopen-local")

        assertTrue(service.calls.isEmpty())
        assertEquals(5, state.saved.size - savesBeforeMutations)
        assertEquals("Renamed", reopened.cache.activeProjects.single().name)
        assertEquals("Local notes", reopened.cache.pages.single().notes.body)
        assertEquals("Bench", reopened.cache.pages.single().newestParking?.location)
        assertEquals(
            listOf(
                ProjectMutationType.RENAME_PROJECT,
                ProjectMutationType.SAVE_NOTES,
                ProjectMutationType.ADD_PARKING,
                ProjectMutationType.ARCHIVE_PROJECT,
                ProjectMutationType.REOPEN_PROJECT,
            ),
            reopened.cache.commands.map { it.type },
        )
        assertEquals(
            listOf(null, "rename-local", "notes-local", "parking-local", "archive-local"),
            reopened.cache.commands.map { it.dependsOnOperationId },
        )
    }

    @Test
    fun lostCreateResponseReplaysTheOriginalIdentityAndReconcilesWithoutDuplicateProject() {
        val state = MemoryState()
        val service = FakeProjectService().apply { createResponseLostAfterSuccess = true }
        val first = repository(service, MemoryDrafts(), state)
        first.activate(SESSION_A)
        first.queueCreateProject(SESSION_A, "One project", "stable-create-key")

        val ambiguous = first.backgroundSync(SESSION_A)
        assertTrue(ambiguous.cache.commands.single().deliveryUncertain)
        assertEquals(1, service.projectCreateCount)
        service.expireIdempotencyResults()

        val restarted = repository(service, MemoryDrafts(), state)
        restarted.activate(SESSION_A)
        val completed = restarted.backgroundSync(SESSION_A)

        assertEquals(listOf("stable-create-key", "stable-create-key"), service.createKeys)
        assertEquals(listOf("stable-create-key", "stable-create-key"), service.createClientIds)
        assertEquals(1, service.projectCreateCount)
        assertTrue(completed.cache.commands.isEmpty())
        assertEquals(PROJECT_ID, completed.cache.projectIdMappings.single().serverId)
    }

    @Test
    fun archivedSummaryCanBeReopenedLocallyBeforeItsPageHasEverBeenCached() {
        val state = MemoryState()
        val archived = project(lifecycle = ProjectLifecycle.ARCHIVED)
        state.saveSyncState(
            TENANT_A,
            TenantSyncState(
                projectCache = ProjectCacheState(
                    archivedProjects = listOf(archived),
                    archivedProjectsLoaded = true,
                ),
            ),
        )
        val service = FakeProjectService().apply { offline = true }
        val repository = repository(service, MemoryDrafts(), state)
        repository.activate(SESSION_A)

        val reopened = repository.queueReopenProject(
            SESSION_A,
            PROJECT_ID,
            archived.rowVersion,
            "reopen-offline-key",
        )

        assertEquals(listOf(PROJECT_ID), reopened.cache.activeProjects.map { it.id })
        assertTrue(reopened.cache.archivedProjects.isEmpty())
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun failedBeforeDeliveryWithIdenticalNewestParkingRetriesOriginalIdentityAndAppendsOnce() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        val initial = loadedState()
        state.saveSyncState(
            TENANT_A,
            initial.copy(
                projectCache = initial.projectCache.copy(
                    pages = listOf(
                        page().copy(
                            newestParking = projectParking(
                                id = "existing-identical-parking",
                                draft = ParkingDraft("Here", "Next", "Remember"),
                            ),
                        ),
                    ),
                ),
            ),
        )
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
        assertFalse(state.loadSyncState(TENANT_A).projectCache.commands.single().deliveryUncertain)
        assertTrue(service.parkingKeys.isEmpty())
        assertEquals("Here", first.projectDrafts(SESSION_A).projectParking[PROJECT_ID]?.location)

        first.backgroundSync(SESSION_A)
        assertFalse(state.loadSyncState(TENANT_A).projectCache.commands.single().deliveryUncertain)

        service.offline = false
        val recreated = repository(service, drafts, state)
        recreated.activate(SESSION_A)
        val final = recreated.backgroundSync(SESSION_A)

        assertEquals(listOf("project-park-key"), service.parkingKeys)
        assertEquals(1, service.parkingAppendCount)
        assertEquals("parking-1", final.cache.pages.single().newestParking?.id)
        assertEquals(0, final.pendingProjectOperations)
        assertTrue("project-park-key" in final.cache.retiredOperationIds)
        assertTrue(recreated.projectDrafts(SESSION_A).projectParking.isEmpty())
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
    fun responseLostAfterParkingSuccessRetriesSameIdentityAndDoesNotAppendTwice() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { parkingResponseLostAfterSuccess = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        val ambiguous = first.queueParking(
            SESSION_A,
            PROJECT_ID,
            ParkingDraft("Here", "Next", "Remember"),
            operationId = "lost-parking-response-key",
        )

        assertEquals(1, ambiguous.pendingProjectOperations)
        assertFalse(ambiguous.cache.commands.single().deliveryUncertain)
        val attempted = first.backgroundSync(SESSION_A)
        assertTrue(attempted.cache.commands.single().deliveryUncertain)
        assertEquals(1, service.parkingAppendCount)
        assertEquals(listOf("lost-parking-response-key"), service.parkingKeys)
        service.expireIdempotencyResults()

        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val replayed = restarted.backgroundSync(SESSION_A)

        assertEquals(listOf("lost-parking-response-key", "lost-parking-response-key"), service.parkingKeys)
        assertEquals(
            listOf("lost-parking-response-key", "lost-parking-response-key"),
            service.parkingClientIds,
        )
        assertEquals(1, service.parkingAppendCount)
        assertEquals("parking-1", replayed.cache.pages.single().newestParking?.id)
        assertTrue(replayed.cache.commands.isEmpty())
        assertTrue("lost-parking-response-key" in replayed.cache.retiredOperationIds)
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

        repository.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Local unsent notes",
            operationId = "notes-operation",
        )

        val result = repository.backgroundSync(SESSION_A)
        assertTrue(result.reconciliationRequired)
        val command = result.cache.commands.single()
        assertEquals(ProjectMutationStatus.REQUIRES_RECONCILIATION, command.status)
        assertEquals("Local unsent notes", command.notesBody)
        assertEquals(3, command.ifMatch)
        assertEquals("Local unsent notes", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])
        assertEquals("Local unsent notes", result.cache.pages.single().notes.body)
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

        repository.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Retained local notes",
            operationId = "notes-operation",
        )

        val unavailable = repository.backgroundSync(SESSION_A)
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
        first.backgroundSync(SESSION_A)
        first.queueRenameProject(SESSION_A, PROJECT_ID, 1, "Queued rename", "rename-operation")

        service.notesConflict = false
        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        restarted.retryConflict(SESSION_A, "old-notes-operation", "fresh-notes-operation")
        val resolved = restarted.backgroundSync(SESSION_A)

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
        repository.queueArchiveProject(SESSION_A, PROJECT_ID, 1, "archive-operation")
        val conflict = repository.backgroundSync(SESSION_A)
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "After archive", "notes-after-archive")

        assertEquals(ProjectMutationType.ARCHIVE_PROJECT, conflict.conflict?.type)
        service.projectConflict = false
        repository.discardConflict(SESSION_A, "archive-operation")
        val resolved = repository.backgroundSync(SESSION_A)

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

        repository.queueRenameProject(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 1,
            name = "My retained name",
            operationId = "old-rename-operation",
        )

        val conflict = repository.backgroundSync(SESSION_A)
        assertEquals("My retained name", conflict.conflict?.localProjectName)
        assertEquals("Project", conflict.conflict?.authoritativePage?.project?.name)
        service.projectConflict = false
        repository.retryConflict(
            SESSION_A,
            "old-rename-operation",
            "fresh-rename-operation",
        )

        val resolved = repository.backgroundSync(SESSION_A)
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

        first.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Accepted despite lost response",
            operationId = "ambiguous-operation",
        )
        val ambiguous = first.backgroundSync(SESSION_A)
        assertTrue(ambiguous.cache.commands.single().deliveryUncertain)
        assertEquals(listOf("Accepted despite lost response"), service.savedNotesBodies)

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

        restarted.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            5,
            "Accepted despite lost response",
            "later-distinct-operation",
        )
        val later = restarted.backgroundSync(SESSION_A)
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
        assertEquals("Second edit", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])
        assertEquals(listOf("notes-two"), partiallySynced.cache.commands.map { it.operationId })

        service.notesFailureAfterSuccessfulSaves = null
        val complete = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("First edit", "Second edit"), service.savedNotesBodies)
        assertTrue(repository.projectDrafts(SESSION_A).projectNotes.isEmpty())
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

        repository.queueSaveNotes(
            SESSION_A,
            PROJECT_ID,
            rowVersion = 3,
            body = "Keep until sign-in refresh",
            operationId = "auth-refresh-key",
        )
        val error = runCatching { repository.backgroundSync(SESSION_A) }.exceptionOrNull()

        assertTrue(error is ServiceException && error.status == 401)
        val deferred = repository.snapshot(SESSION_A)
        assertEquals(listOf("auth-refresh-key"), deferred.cache.commands.map { it.operationId })
        assertFalse(deferred.reconciliationRequired)
        assertEquals("Keep until sign-in refresh", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])

        service.authenticationRequired = false
        val completed = repository.backgroundSync(SESSION_A.copy(identityToken = "fresh-token"))

        assertTrue(completed.cache.commands.isEmpty())
        assertTrue("auth-refresh-key" in completed.cache.retiredOperationIds)
        assertTrue(repository.projectDrafts(SESSION_A).projectNotes.isEmpty())
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
        assertEquals("Tenant B", repository.projectDrafts(SESSION_B).projectNotes[PROJECT_ID])

        service.membershipRevoked = true
        val error = runCatching { repository.backgroundSync(SESSION_B) }.exceptionOrNull()

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
        var parkingResponseLostAfterSuccess = false
        var membershipRevoked = false
        var authenticationRequired = false
        var incompatibleApi = false
        var rejectApi04Mutation = false
        var deferredFailure: IOException? = null
        var createResponseLostAfterSuccess = false
        var notesFailureAfterSuccessfulSaves: Int? = null
        var serverNotesBody = "Server notes"
        var serverNotesVersion = 4
        val parkingKeys = mutableListOf<String>()
        var parkingAppendCount = 0
            private set
        private val parkingOutcomes = mutableMapOf<String, ProjectParking>()
        private val lostParkingResponses = mutableSetOf<String>()
        val savedNotesBodies = mutableListOf<String>()
        val savedNotesVersions = mutableListOf<Int>()
        val renamedNames = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val createKeys = mutableListOf<String>()
        val createClientIds = mutableListOf<String?>()
        val parkingClientIds = mutableListOf<String?>()
        val parkingOccurredAt = mutableListOf<String?>()
        var projectCreateCount = 0
            private set
        private val createOutcomes = mutableMapOf<String, ProjectSummary>()
        private val projectsByClientId = mutableMapOf<String, ProjectSummary>()
        private val parkingByClientId = mutableMapOf<String, ProjectParking>()
        private val lostCreateResponses = mutableSetOf<String>()
        val changePages = ArrayDeque<ChangePage>()

        override fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary> {
            calls += "list-${lifecycle.wireValue}"
            if (incompatibleApi) throw IncompatibleServiceApiException()
            failIfNeeded()
            return if (lifecycle == ProjectLifecycle.ACTIVE) listOf(project(tenantId = session.tenantId)) else emptyList()
        }

        override fun createProject(
            session: Session,
            name: String,
            clientId: String?,
            idempotencyKey: String,
        ): ProjectSummary {
            calls += "create"
            createKeys += idempotencyKey
            createClientIds += clientId
            if (rejectApi04Mutation) {
                throw ServiceException(422, ApiError(ApiErrorCode.INVALID_REQUEST, "Unknown client_id"))
            }
            if (incompatibleApi) throw IncompatibleServiceApiException()
            failIfNeeded()
            val outcome = createOutcomes.getOrPut(idempotencyKey) {
                clientId?.let { stableClientId ->
                    projectsByClientId.getOrPut(stableClientId) {
                        projectCreateCount += 1
                        project(name = name, tenantId = session.tenantId).copy(clientId = stableClientId)
                    }
                } ?: run {
                    projectCreateCount += 1
                    project(name = name, tenantId = session.tenantId)
                }
            }
            if (createResponseLostAfterSuccess && lostCreateResponses.add(idempotencyKey)) {
                throw IOException("response lost after project acceptance")
            }
            return outcome
        }

        override fun projectPage(session: Session, projectId: String): ProjectPage {
            calls += "page"
            failIfNeeded()
            if (conflictPageUnavailable) throw IOException("authoritative page unavailable")
            return page(session.tenantId).copy(
                notes = page(session.tenantId).notes.copy(body = serverNotesBody, rowVersion = serverNotesVersion),
            )
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
            calls += "notes"
            failIfNeeded()
            if (notesConflict) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "Notes changed"))
            notesFailureAfterSuccessfulSaves?.let { successfulLimit ->
                if (savedNotesBodies.size >= successfulLimit) throw IOException("offline")
            }
            savedNotesBodies += body
            savedNotesVersions += rowVersion
            serverNotesBody = body
            serverNotesVersion = rowVersion + 1
            if (notesAmbiguousAfterSuccess) throw IOException("response lost after acceptance")
            return ProjectNotes(projectId, body, rowVersion + 1, NOW)
        }

        override fun addParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            clientId: String?,
            occurredAt: String?,
            idempotencyKey: String,
        ): ProjectParking {
            calls += "parking"
            parkingKeys += idempotencyKey
            parkingClientIds += clientId
            parkingOccurredAt += occurredAt
            if (rejectApi04Mutation) {
                throw ServiceException(422, ApiError(ApiErrorCode.INVALID_REQUEST, "Unknown client_id"))
            }
            failIfNeeded()
            val outcome = parkingOutcomes.getOrPut(idempotencyKey) {
                clientId?.let { stableClientId ->
                    parkingByClientId.getOrPut(stableClientId) {
                        parkingAppendCount += 1
                        projectParking(
                            id = "parking-$parkingAppendCount",
                            draft = parking,
                            supersedesId = supersedesId,
                        ).copy(clientId = stableClientId, occurredAt = occurredAt ?: NOW)
                    }
                } ?: run {
                    parkingAppendCount += 1
                    projectParking(
                        id = "parking-$parkingAppendCount",
                        draft = parking,
                        supersedesId = supersedesId,
                    )
                }
            }
            if (parkingResponseLostAfterSuccess && lostParkingResponses.add(idempotencyKey)) {
                throw IOException("response lost after parking acceptance")
            }
            return outcome
        }

        override fun parking(session: Session, projectId: String) = listOfNotNull(page(session.tenantId).newestParking)
        override fun blocks(session: Session): List<PhysicalBlock> {
            calls += "blocks"
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
            calls += "changes"
            failIfNeeded()
            return if (changePages.isEmpty()) ChangePage(emptyList(), cursor, false) else changePages.removeFirst()
        }

        fun expireIdempotencyResults() {
            createOutcomes.clear()
            parkingOutcomes.clear()
        }

        private fun failIfNeeded() {
            if (membershipRevoked) throw ServiceException(403, ApiError(ApiErrorCode.UNAUTHORIZED, "Revoked"))
            if (authenticationRequired) {
                throw ServiceException(401, ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required"))
            }
            deferredFailure?.let { throw it }
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

        private fun projectParking(
            id: String,
            draft: ParkingDraft,
            supersedesId: String? = null,
        ) = ProjectParking(
            id = id,
            projectId = PROJECT_ID,
            location = draft.location,
            nextAction = draft.nextAction,
            mustNotForget = draft.mustNotForget,
            authoredByUserId = "user-id",
            createdAt = NOW,
            supersedesId = supersedesId,
        )

        private fun event(id: String, type: String, payload: Map<String, Any?>) =
            ChangeEvent(id, type, "0.1", TENANT_A, payload)
    }
}
