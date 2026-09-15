package dev.blocks.android.sync

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.IncompatibleServiceApiException
import dev.blocks.android.api.ServiceException
import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverMediaStore
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
import dev.blocks.android.project.ProjectCoverContent
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
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagProgrammingFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSyncRepositoryTest {
    @Test
    fun legacyProjectGetsOneStableCollisionCheckedKeyWithoutCreatingWireOperations() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val first = repository(FakeProjectService(), MemoryDrafts(), state)
        first.activate(SESSION_A)

        val link = first.projectLink(SESSION_A, PROJECT_ID)
        assertNotNull(java.util.UUID.fromString(link.key.encoded))
        assertTrue(first.snapshot(SESSION_A).cache.localBlockOperations.isEmpty())

        val restarted = repository(FakeProjectService(), MemoryDrafts(), state)
        restarted.activate(SESSION_A)
        assertEquals(link, restarted.projectLink(SESSION_A, PROJECT_ID))
        assertEquals(PROJECT_ID, restarted.resolveProjectLinkLocally(SESSION_A, link.key))
    }

    @Test
    fun directTagPlanIsTransientAndVerifiedOverwriteCommitsNewAndPriorAssociationsAtomically() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val repository = repository(FakeProjectService(), MemoryDrafts(), state)
        repository.activate(SESSION_A)
        val firstToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 41).toByte() })
        val first = repository.prepareDirectTagWrite(
            SESSION_A,
            PROJECT_ID,
            "Project",
            "direct-first",
            firstToken,
            "register-first",
            "bind-first",
        )

        assertTrue(repository.snapshot(SESSION_A).cache.localBlocks.isEmpty())
        assertTrue(state.loadSyncState(TENANT_A).projectCache.localBlocks.isEmpty())
        val savesBeforeFirstVerification = state.saved.size
        repository.commitVerifiedDirectTagWrite(SESSION_A, first, replacedToken = null)
        assertEquals(savesBeforeFirstVerification + 1, state.saved.size)

        val secondToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 73).toByte() })
        val second = repository.prepareDirectTagWrite(
            SESSION_A,
            PROJECT_ID,
            "Project",
            "direct-second",
            secondToken,
            "register-second",
            "bind-second",
        )
        val beforeReplacement = state.saved.size
        val replaced = repository.commitVerifiedDirectTagWrite(SESSION_A, second, firstToken)

        assertEquals(beforeReplacement + 1, state.saved.size)
        assertEquals(LocalBlockState.UNBOUND, replaced.cache.localBlocks.single { it.token == firstToken }.state)
        assertEquals(LocalBlockState.ACTIVE, replaced.cache.localBlocks.single { it.token == secondToken }.state)
        assertEquals(
            listOf(
                LocalBlockOperationType.REGISTER_BLOCK,
                LocalBlockOperationType.BIND_BLOCK,
                LocalBlockOperationType.UNBIND_BLOCK,
                LocalBlockOperationType.REGISTER_BLOCK,
                LocalBlockOperationType.BIND_BLOCK,
            ),
            replaced.cache.localBlockOperations.map { it.type },
        )
    }

    @Test
    fun verifiedPhysicalClearRemovesOnlyThePresentedTagAssociationAndRemainsTenantPartitioned() {
        val state = MemoryState().apply {
            saveSyncState(TENANT_A, loadedState(TENANT_A))
            saveSyncState(TENANT_B, loadedState(TENANT_B))
        }
        val repository = repository(FakeProjectService(), MemoryDrafts(), state)
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 91).toByte() })
        repository.activate(SESSION_A)
        val plan = repository.prepareDirectTagWrite(
            SESSION_A,
            PROJECT_ID,
            "Project",
            "clear-block",
            token,
            "clear-register",
            "clear-bind",
        )
        repository.commitVerifiedDirectTagWrite(SESSION_A, plan, null)

        repository.activate(SESSION_B)
        val untouchedB = repository.commitVerifiedDirectTagClear(SESSION_B, token, "tenant-b-clear")
        assertTrue(untouchedB.cache.localBlocks.isEmpty())
        repository.activate(SESSION_A)
        val cleared = repository.commitVerifiedDirectTagClear(SESSION_A, token, "tenant-a-clear")

        assertEquals(LocalBlockState.UNBOUND, cleared.cache.localBlocks.single().state)
        assertEquals(null, cleared.cache.localBlocks.single().boundProjectId)
        assertEquals(LocalBlockOperationType.UNBIND_BLOCK, cleared.cache.localBlockOperations.last().type)
        assertEquals("tenant-a-clear", cleared.cache.localBlockOperations.last().operationId)
        assertTrue(state.loadSyncState(TENANT_B).projectCache.localBlocks.isEmpty())
    }

    @Test
    fun pendingNfcProgrammingSurvivesAmbiguityAndRestartWithoutMintingAnotherToken() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService()
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { it.toByte() })
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        first.beginTagProgramming(
            SESSION_A,
            PROJECT_ID,
            "Desk block",
            clientBlockId = "client-block",
            token = token,
            registrationOperationId = "register-operation",
            bindingOperationId = "bind-operation",
        )
        first.markTagWriteAttempt(SESSION_A, "client-block")

        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val pending = restarted.resolveTagLocally(SESSION_A, token)
        assertTrue(pending is LocalTagResolution.PendingProgramming)
        assertEquals(LocalBlockState.WRITE_UNCERTAIN, (pending as LocalTagResolution.PendingProgramming).block.state)
        assertEquals(token, pending.block.token)

        val completed = restarted.completeTagProgramming(
            SESSION_A,
            "client-block",
            token,
            TagActivationMedium.NFC,
        )

        assertEquals(LocalBlockState.ACTIVE, completed.cache.localBlocks.single().state)
        assertEquals(PROJECT_ID, completed.cache.localBlocks.single().boundProjectId)
        assertEquals(
            listOf(LocalBlockOperationType.REGISTER_BLOCK, LocalBlockOperationType.BIND_BLOCK),
            completed.cache.localBlockOperations.map { it.type },
        )
        assertEquals(listOf(null, "register-operation"), completed.cache.localBlockOperations.map { it.dependsOnOperationId })
        assertEquals(2, completed.pendingBlockOperations)
    }

    @Test
    fun everyProgrammingFailureRetainsTheFrozenTokenAndOperationIdentitiesUntilVerified() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 1).toByte() })
        val first = repository(FakeProjectService(), drafts, state)
        first.activate(SESSION_A)
        first.beginTagProgramming(
            SESSION_A,
            PROJECT_ID,
            "Workshop block",
            "stable-client-block",
            token,
            "stable-register",
            "stable-bind",
        )

        listOf(
            TagProgrammingFailure.UNSUPPORTED,
            TagProgrammingFailure.READ_ONLY,
            TagProgrammingFailure.TOO_SMALL,
            TagProgrammingFailure.FOREIGN_CONTENT,
        ).forEach { failure ->
            val retained = first.recordTagProgrammingFailure(SESSION_A, "stable-client-block", failure)
            assertEquals(LocalBlockState.AWAITING_WRITE, retained.state)
            assertEquals(token, retained.token)
            assertEquals("stable-register", retained.registrationOperationId)
            assertEquals("stable-bind", retained.initialBindingOperationId)
            assertTrue(state.loadSyncState(TENANT_A).projectCache.localBlockOperations.isEmpty())
        }
        first.markTagWriteAttempt(SESSION_A, "stable-client-block")
        first.recordTagProgrammingFailure(
            SESSION_A,
            "stable-client-block",
            TagProgrammingFailure.VERIFICATION_MISMATCH,
        )

        val restarted = repository(FakeProjectService(), drafts, state)
        restarted.activate(SESSION_A)
        val pending = (restarted.resolveTagLocally(SESSION_A, token) as LocalTagResolution.PendingProgramming).block
        assertEquals(LocalBlockState.WRITE_UNCERTAIN, pending.state)
        assertEquals(token, pending.token)
        assertEquals("stable-register", pending.registrationOperationId)
        assertEquals("stable-bind", pending.initialBindingOperationId)

        val completed = restarted.completeTagProgramming(
            SESSION_A,
            "stable-client-block",
            token,
            TagActivationMedium.NFC,
        )
        assertEquals(listOf("stable-register", "stable-bind"), completed.cache.localBlockOperations.map { it.operationId })
    }

    @Test
    fun localTagResolutionIsTenantPartitionedAndUnbindReassignKeepsOrderedIntent() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply {
            saveSyncState(TENANT_A, loadedState(TENANT_A))
            saveSyncState(TENANT_B, loadedState(TENANT_B))
        }
        val repository = repository(FakeProjectService(), drafts, state)
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 2).toByte() })
        repository.activate(SESSION_A)
        repository.beginTagProgramming(
            SESSION_A,
            PROJECT_ID,
            "Portable block",
            "tenant-a-block",
            token,
            "register-a",
            "bind-a",
        )
        repository.completeTagProgramming(SESSION_A, "tenant-a-block", token, TagActivationMedium.QR)

        repository.activate(SESSION_B)
        assertTrue(repository.resolveTagLocally(SESSION_B, token) is LocalTagResolution.Unknown)

        repository.activate(SESSION_A)
        assertTrue(repository.resolveTagLocally(SESSION_A, token) is LocalTagResolution.Bound)
        repository.unbindLocalBlock(SESSION_A, "tenant-a-block", "unbind-a")
        assertTrue(repository.resolveTagLocally(SESSION_A, token) is LocalTagResolution.Unbound)
        val reassigned = repository.assignLocalBlock(SESSION_A, "tenant-a-block", PROJECT_ID, "rebind-a")

        assertTrue(repository.resolveTagLocally(SESSION_A, token) is LocalTagResolution.Bound)
        assertEquals(
            listOf("register-a", "bind-a", "unbind-a", "rebind-a"),
            reassigned.cache.localBlockOperations.map { it.operationId },
        )
        assertEquals(
            listOf(null, "register-a", "bind-a", "unbind-a"),
            reassigned.cache.localBlockOperations.map { it.dependsOnOperationId },
        )
        assertFalse(state.loadSyncState(TENANT_B).projectCache.localBlocks.any { it.token == token })
    }

    @Test
    fun api04BackgroundSyncNeverTransmitsLocalBlockOperations() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService()
        val repository = repository(service, drafts, state)
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 3).toByte() })
        repository.activate(SESSION_A)
        repository.beginTagProgramming(
            SESSION_A,
            PROJECT_ID,
            "Offline block",
            "offline-block",
            token,
            "offline-register",
            "offline-bind",
        )
        repository.completeTagProgramming(SESSION_A, "offline-block", token, TagActivationMedium.NFC)

        val synced = repository.backgroundSync(SESSION_A)

        assertEquals(2, synced.pendingBlockOperations)
        assertEquals(listOf("changes"), service.calls)
        assertEquals(listOf("offline-register", "offline-bind"), synced.cache.localBlockOperations.map { it.operationId })
    }

    @Test
    fun authenticatedUnknownLookupCachesTheAuthorizedResultBeforeOfflineReentry() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService()
        val repository = repository(service, drafts, state)
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 4).toByte() })
        repository.activate(SESSION_A)

        val online = repository.resolveTagThroughService(SESSION_A, token)
        service.offline = true
        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val offline = restarted.resolveTagLocally(SESSION_A, token)

        assertTrue(online is LocalTagResolution.Bound)
        assertTrue(offline is LocalTagResolution.Bound)
        assertEquals(PROJECT_ID, (offline as LocalTagResolution.Bound).projectId)
        assertEquals(listOf("lookup", "page"), service.calls)
        assertEquals("block-id", offline.block.serverBlockId)
    }

    @Test
    fun provisionalProjectAcknowledgementAtomicallyRemapsLocalBlockAndBindingIntent() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        val service = FakeProjectService()
        val repository = repository(service, drafts, state)
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 5).toByte() })
        repository.activate(SESSION_A)
        repository.queueCreateProject(SESSION_A, "Local project", "create-project", "local-project")
        repository.saveProjectCover(SESSION_A, cover("local-project"))
        val localCover = repository.projectCover(SESSION_A, "local-project")!!
        repository.beginTagProgramming(
            SESSION_A,
            "local-project",
            "Project block",
            "project-block",
            token,
            "register-project-block",
            "bind-project-block",
        )
        repository.completeTagProgramming(SESSION_A, "project-block", token, TagActivationMedium.NFC)

        val synced = repository.backgroundSync(SESSION_A)

        assertEquals(PROJECT_ID, synced.cache.localBlocks.single().boundProjectId)
        assertEquals(PROJECT_ID, synced.cache.localBlocks.single().intendedProjectId)
        assertEquals(PROJECT_ID, synced.cache.localBlockOperations.single {
            it.type == LocalBlockOperationType.BIND_BLOCK
        }.projectId)
        assertEquals(PROJECT_ID, synced.cache.projectCovers.single().projectId)
        assertEquals(localCover.coverId, synced.cache.projectCovers.single().coverId)
        assertEquals(localCover.displayContentRef, synced.cache.projectCovers.single().displayContentRef)
        assertEquals(localCover.thumbnailContentRef, synced.cache.projectCovers.single().thumbnailContentRef)
        assertEquals(listOf("list-active", "create", "changes", "list-archived", "blocks"), service.calls)
    }

    @Test
    fun projectCoverCommitsAtomicallyAndSurvivesRestartUntilExplicitRemoval() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val media = InMemoryProjectCoverMediaStore()
        val first = repository(FakeProjectService(), MemoryDrafts(), state, media)
        first.activate(SESSION_A)

        val content = cover(PROJECT_ID)
        val expectedDigest = content.contentDigest
        val committed = first.saveProjectCover(SESSION_A, content)

        assertEquals(expectedDigest, committed.cache.projectCovers.single().contentDigest)
        assertEquals(2, media.objectCount(TENANT_A))
        val restarted = repository(FakeProjectService(), MemoryDrafts(), state, media)
        restarted.activate(SESSION_A)
        assertEquals(expectedDigest, restarted.projectCover(SESSION_A, PROJECT_ID)?.contentDigest)

        restarted.removeProjectCover(SESSION_A, PROJECT_ID)
        assertEquals(null, restarted.projectCover(SESSION_A, PROJECT_ID))
        assertTrue(state.loadSyncState(TENANT_A).projectCache.projectCovers.isEmpty())
        assertEquals(0, media.objectCount(TENANT_A))
    }

    @Test
    fun coverReplacementIsTwoPhaseAndOrdinaryLocalWritesDoNotTouchMediaObjects() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val media = InMemoryProjectCoverMediaStore()
        val repository = repository(FakeProjectService(), MemoryDrafts(), state, media)
        repository.activate(SESSION_A)

        repository.saveProjectCover(SESSION_A, cover(PROJECT_ID))
        val first = repository.projectCover(SESSION_A, PROJECT_ID)!!
        val objectCount = media.objectCount(TENANT_A)
        val mediaCalls = media.stageCalls + media.readCalls + media.retireCalls

        repository.updateProjectDrafts(SESSION_A) { it.copy(projectName = "Local draft") }
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "New notes", "notes-with-cover")
        repository.backgroundSync(SESSION_A)

        assertEquals(objectCount, media.objectCount(TENANT_A))
        assertEquals(mediaCalls, media.stageCalls + media.readCalls + media.retireCalls)

        repository.saveProjectCover(SESSION_A, cover(PROJECT_ID))
        val replacement = repository.projectCover(SESSION_A, PROJECT_ID)!!
        assertEquals(first.coverId, replacement.coverId)
        assertEquals(first.revision + 1, replacement.revision)
        assertEquals(2, media.objectCount(TENANT_A))
    }

    @Test
    fun failedCoverMetadataCommitRetiresStagedObjectsAndKeepsPriorCover() {
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val media = InMemoryProjectCoverMediaStore()
        val repository = repository(FakeProjectService(), MemoryDrafts(), state, media)
        repository.activate(SESSION_A)
        repository.saveProjectCover(SESSION_A, cover(PROJECT_ID))
        val first = repository.projectCover(SESSION_A, PROJECT_ID)!!
        state.failNextSave = true

        val error = runCatching { repository.saveProjectCover(SESSION_A, cover(PROJECT_ID)) }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(first, repository.projectCover(SESSION_A, PROJECT_ID))
        assertEquals(2, media.objectCount(TENANT_A))
    }

    @Test
    fun tenantErasureRemovesOnlyThatTenantsCoverObjects() {
        val state = MemoryState().apply {
            saveSyncState(TENANT_A, loadedState(TENANT_A))
            saveSyncState(TENANT_B, loadedState(TENANT_B))
        }
        val media = InMemoryProjectCoverMediaStore()
        val repository = repository(FakeProjectService(), MemoryDrafts(), state, media)
        repository.activate(SESSION_A)
        repository.saveProjectCover(SESSION_A, cover(PROJECT_ID))
        repository.deactivate(SESSION_A)
        repository.activate(SESSION_B)
        repository.saveProjectCover(SESSION_B, cover(PROJECT_ID))

        repository.clearLocalState(SESSION_B)

        assertEquals(2, media.objectCount(TENANT_A))
        assertEquals(0, media.objectCount(TENANT_B))
        assertEquals(1, media.eraseCalls)
    }

    @Test
    fun localParkingHistoryIsImmediateRestartSafeAndProgressivelyReadableNewestFirst() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService().apply { offline = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        repeat(23) { index ->
            repository.queueParking(
                SESSION_A,
                PROJECT_ID,
                ParkingDraft("Place $index", "Action $index", "Remember $index"),
                operationId = "parking-operation-$index",
                clientId = "parking-client-$index",
            )
        }

        val restarted = repository(service, drafts, state)
        restarted.activate(SESSION_A)
        val first = restarted.parkingHistory(SESSION_A, PROJECT_ID, offset = 0, limit = 10)
        val second = restarted.parkingHistory(SESSION_A, PROJECT_ID, offset = 10, limit = 10)
        val third = restarted.parkingHistory(SESSION_A, PROJECT_ID, offset = 20, limit = 10)

        assertEquals((22 downTo 13).map { "parking-client-$it" }, first.records.map { it.id })
        assertEquals((12 downTo 3).map { "parking-client-$it" }, second.records.map { it.id })
        assertEquals((2 downTo 0).map { "parking-client-$it" }, third.records.map { it.id })
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)
        assertEquals("Place 22", restarted.projectPage(SESSION_A, PROJECT_ID).newestParking?.location)
    }

    @Test
    fun slowBackgroundApiCannotBlockOrOverwriteAnImmediateLocalCommit() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService().apply {
            changesEntered = CountDownLatch(1)
            allowChanges = CountDownLatch(1)
        }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)
        var backgroundFailure: Throwable? = null
        val background = Thread {
            runCatching { repository.backgroundSync(SESSION_A) }
                .exceptionOrNull()
                ?.let { backgroundFailure = it }
        }
        background.start()
        assertTrue(service.changesEntered.await(2, TimeUnit.SECONDS))

        var localResult: dev.blocks.android.project.BackgroundSyncResult? = null
        val local = Thread {
            localResult = repository.queueCreateProject(
                SESSION_A,
                "Created while API waits",
                operationId = "concurrent-create-operation",
                clientId = "concurrent-create-client",
            )
        }
        local.start()
        local.join(500)

        assertFalse("Local encrypted commit waited for background transport", local.isAlive)
        assertTrue(localResult?.cache?.activeProjects?.any { it.name == "Created while API waits" } == true)
        service.allowChanges.countDown()
        background.join(2_000)
        assertFalse("Background sync did not complete", background.isAlive)
        assertEquals(null, backgroundFailure)
        val durable = state.loadSyncState(TENANT_A).projectCache
        assertTrue(durable.activeProjects.any { it.name == "Created while API waits" })
        assertTrue(durable.commands.any { it.operationId == "concurrent-create-operation" })
    }

    @Test
    fun concurrentDependentEditIsAtomicallyRemappedWhenProvisionalCreateIsAcknowledged() {
        val drafts = MemoryDrafts()
        val state = MemoryState().apply { saveSyncState(TENANT_A, loadedState()) }
        val service = FakeProjectService().apply {
            createEntered = CountDownLatch(1)
            allowCreate = CountDownLatch(1)
        }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)
        repository.queueCreateProject(
            SESSION_A,
            "Concurrent project",
            operationId = "create-concurrent",
            clientId = "client-concurrent",
        )

        var backgroundFailure: Throwable? = null
        val background = Thread {
            runCatching { repository.backgroundSync(SESSION_A) }
                .exceptionOrNull()
                ?.let { backgroundFailure = it }
        }
        background.start()
        assertTrue(service.createEntered.await(2, TimeUnit.SECONDS))

        repository.queueSaveNotes(
            SESSION_A,
            "client-concurrent",
            rowVersion = 0,
            body = "Saved while create waits",
            operationId = "notes-concurrent",
        )
        service.allowCreate.countDown()
        background.join(2_000)

        assertFalse("Background reconciliation did not complete", background.isAlive)
        assertEquals(null, backgroundFailure)
        val durable = state.loadSyncState(TENANT_A).projectCache
        assertEquals(listOf(ProjectIdentityMapping("client-concurrent", PROJECT_ID)), durable.projectIdMappings)
        assertTrue(durable.commands.isEmpty())
        assertTrue(durable.pages.none { it.project.id == "client-concurrent" })
        assertTrue(durable.parkingRecords.none { it.projectId == "client-concurrent" })
        assertEquals("Saved while create waits", durable.pages.single { it.project.id == PROJECT_ID }.notes.body)
        assertEquals(listOf("Saved while create waits"), service.savedNotesBodies)
    }

    @Test
    fun emptyCacheJourneyIsImmediatelyUsableOfflineAndConvergesInDependencyOrderAfterRestart() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        val service = FakeProjectService().apply { offline = true }
        val first = repository(service, drafts, state)
        first.activate(SESSION_A)

        val created = first.queueCreateProject(SESSION_A, "Offline project", "create-project-key")
        val provisionalId = created.cache.activeProjects.single().id
        val localProjectLink = first.projectLink(SESSION_A, provisionalId)
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
        first.updateProjectDrafts(SESSION_A) {
            it.copy(
                projectNotes = mapOf(provisionalId to "Newer notes draft"),
                projectParking = mapOf(
                    provisionalId to ParkingDraft("Packing bench", "Label parts", "Keep the receipt"),
                ),
            )
        }

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
        assertEquals(localProjectLink, restarted.projectLink(SESSION_A, provisionalId))
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
        assertEquals(localProjectLink, restarted.projectLink(SESSION_A, PROJECT_ID))
        assertEquals(PROJECT_ID, restarted.resolveProjectLinkLocally(SESSION_A, localProjectLink.key))
        assertTrue(converged.cache.localBlockOperations.isEmpty())
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
        assertEquals("Newer notes draft", identityRewrite.projectDrafts.projectNotes[PROJECT_ID])
        assertEquals("Packing bench", identityRewrite.projectDrafts.projectParking[PROJECT_ID]?.location)
        assertTrue(provisionalId !in identityRewrite.projectDrafts.projectNotes)
        assertTrue(provisionalId !in identityRewrite.projectDrafts.projectParking)
        assertEquals("Newer notes draft", restarted.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])
        assertEquals("Packing bench", restarted.projectDrafts(SESSION_A).projectParking[PROJECT_ID]?.location)
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
        assertTrue(first.projectDrafts(SESSION_A).projectParking.isEmpty())

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
        val repository = ProjectSyncRepository(
            service,
            observer,
            MemoryDrafts(),
            state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
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
        val repository = ProjectSyncRepository(
            FakeProjectService(),
            replayer,
            drafts,
            state,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
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
        assertTrue(repository.projectDrafts(SESSION_A).projectNotes.isEmpty())
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
    fun remoteAcknowledgementsDoNotDiscardANewerUnsavedDraft() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        val service = FakeProjectService().apply { offline = true }
        val repository = repository(service, drafts, state)
        repository.activate(SESSION_A)

        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 3, "First edit", "notes-one")
        repository.queueSaveNotes(SESSION_A, PROJECT_ID, 4, "Second edit", "notes-two")
        repository.updateProjectDrafts(SESSION_A) {
            it.copy(projectNotes = mapOf(PROJECT_ID to "Third unsaved edit"))
        }
        service.offline = false
        service.notesFailureAfterSuccessfulSaves = 1

        val partiallySynced = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("First edit"), service.savedNotesBodies)
        assertEquals("Third unsaved edit", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])
        assertEquals(listOf("notes-two"), partiallySynced.cache.commands.map { it.operationId })

        service.notesFailureAfterSuccessfulSaves = null
        val complete = repository.backgroundSync(SESSION_A)

        assertEquals(listOf("First edit", "Second edit"), service.savedNotesBodies)
        assertEquals("Third unsaved edit", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])
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
        assertTrue(repository.projectDrafts(SESSION_A).projectNotes.isEmpty())

        service.authenticationRequired = false
        val completed = repository.backgroundSync(SESSION_A.copy(identityToken = "fresh-token"))

        assertTrue(completed.cache.commands.isEmpty())
        assertTrue("auth-refresh-key" in completed.cache.retiredOperationIds)
        assertTrue(repository.projectDrafts(SESSION_A).projectNotes.isEmpty())
    }

    @Test
    fun tenantSwitchRetainsBothPartitionsAndConfirmedMembershipRevocationErasesOnlyTheAffectedOne() {
        val drafts = MemoryDrafts()
        val state = MemoryState()
        state.saveSyncState(TENANT_A, loadedState())
        state.saveSyncState(TENANT_B, loadedState(TENANT_B))
        drafts.save(TENANT_A, FormDrafts(projectNotes = mapOf(PROJECT_ID to "Tenant A")))
        drafts.save(TENANT_B, FormDrafts(projectNotes = mapOf(PROJECT_ID to "Tenant B")))
        val service = FakeProjectService()
        val repository = repository(service, drafts, state)

        repository.activate(SESSION_A)
        val tenantALink = repository.projectLink(SESSION_A, PROJECT_ID)
        repository.activate(SESSION_B)
        val tenantBLink = repository.projectLink(SESSION_B, PROJECT_ID)

        assertFalse(tenantALink == tenantBLink)
        assertEquals(null, repository.resolveProjectLinkLocally(SESSION_B, tenantALink.key))
        assertFalse(state.loadSyncState(TENANT_A) == TenantSyncState())
        assertEquals("Tenant A", state.loadSyncState(TENANT_A).projectDrafts.projectNotes[PROJECT_ID])
        assertFalse(state.loadSyncState(TENANT_B) == TenantSyncState())
        assertEquals("Tenant B", repository.projectDrafts(SESSION_B).projectNotes[PROJECT_ID])

        repository.deactivate(SESSION_B)
        repository.activate(SESSION_A)
        assertEquals(PROJECT_ID, repository.resolveProjectLinkLocally(SESSION_A, tenantALink.key))
        assertEquals("Tenant A", repository.projectDrafts(SESSION_A).projectNotes[PROJECT_ID])

        service.membershipRevoked = true
        repository.activate(SESSION_B)
        val error = runCatching { repository.backgroundSync(SESSION_B) }.exceptionOrNull()

        assertTrue(error is MembershipRevokedException)
        assertEquals(TenantSyncState(), state.loadSyncState(TENANT_B))
        assertEquals(FormDrafts(), drafts.load(TENANT_B))
        assertFalse(state.loadSyncState(TENANT_A) == TenantSyncState())
        assertEquals("Tenant A", state.loadSyncState(TENANT_A).projectDrafts.projectNotes[PROJECT_ID])
    }

    private fun repository(
        service: FakeProjectService,
        drafts: MemoryDrafts,
        state: MemoryState,
        media: ProjectCoverMediaStore = InMemoryProjectCoverMediaStore(),
    ) = ProjectSyncRepository(service, NoOpLegacyQueueReplay, drafts, state, coverMediaStore = media)

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
        var failNextSave = false
        override fun loadSyncState(tenantId: String) = values[tenantId] ?: TenantSyncState()
        override fun saveSyncState(tenantId: String, state: TenantSyncState) {
            if (failNextSave) {
                failNextSave = false
                throw IllegalStateException("simulated metadata commit failure")
            }
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
        lateinit var changesEntered: CountDownLatch
        lateinit var allowChanges: CountDownLatch
        lateinit var createEntered: CountDownLatch
        lateinit var allowCreate: CountDownLatch

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
            if (::createEntered.isInitialized) {
                createEntered.countDown()
                check(allowCreate.await(2, TimeUnit.SECONDS)) { "Timed out waiting to release create" }
            }
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

        override fun parking(session: Session, projectId: String) =
            (parkingOutcomes.values + listOfNotNull(page(session.tenantId).newestParking))
                .distinctBy { it.clientId ?: it.id }
        override fun blocks(session: Session): List<PhysicalBlock> {
            calls += "blocks"
            failIfNeeded()
            return emptyList()
        }
        override fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock {
            calls += "register"
            return RegisteredBlock(PhysicalBlock("block-id", label, "registered"), "tag-token")
        }
        override fun bindBlock(session: Session, blockId: String, projectId: String, idempotencyKey: String): BlockBinding {
            calls += "bind"
            return BlockBinding("binding-id", blockId, projectId)
        }
        override fun unbindBlock(session: Session, blockId: String) {
            calls += "unbind"
        }
        override fun lookupBlock(session: Session, tagToken: String): BlockLookup {
            calls += "lookup"
            failIfNeeded()
            return BlockLookup("block-id", "Block", PROJECT_ID)
        }
        override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
            calls += "changes"
            failIfNeeded()
            if (::changesEntered.isInitialized) {
                changesEntered.countDown()
                check(allowChanges.await(2, TimeUnit.SECONDS)) { "Timed out waiting to release changes" }
            }
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

        private fun cover(projectId: String): ProjectCoverContent {
            val display = "display".toByteArray()
            val thumbnail = "thumbnail".toByteArray()
            return ProjectCoverContent(
            projectId = projectId,
            displayJpeg = display,
            thumbnailJpeg = thumbnail,
            displayWidth = 1200,
            displayHeight = 675,
            thumbnailWidth = 480,
            thumbnailHeight = 270,
            displayDigest = digest(display),
            thumbnailDigest = digest(thumbnail),
            contentDigest = digest(display, thumbnail),
            importedAt = NOW,
        )
        }

        private fun digest(vararg values: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach(digest::update)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

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
