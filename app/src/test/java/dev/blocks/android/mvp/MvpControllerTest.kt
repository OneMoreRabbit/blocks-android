package dev.blocks.android.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MvpControllerTest {
    @Test
    fun membershipRevocationClearsTheActiveInMemoryTenantAndRequiresLogin() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("development-token")
        assertTrue(controller.state.screen != MvpScreen.LOGIN)

        val requiresReauthentication = controller.reportRepositoryError(
            MembershipRevokedException(IllegalStateException("forbidden")),
        )

        assertTrue(requiresReauthentication)
        assertEquals(MvpScreen.LOGIN, controller.state.screen)
        assertNull(controller.state.selectedProject)
        assertNull(controller.state.workSession)
        assertEquals(FormDrafts(), controller.state.drafts)
    }

    @Test
    fun completesLoginProjectPickupAndParkingLoop() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("development-token")
        assertEquals(MvpScreen.PROJECTS, controller.state.screen)

        controller.createProject("Atlas delivery")
        assertEquals(MvpScreen.PICKUP, controller.state.screen)
        assertEquals("Atlas delivery", controller.state.selectedProject?.name)
        assertNull(controller.state.parking)

        controller.pickup("Generate the API client", "Wire the online loop", "Polish the UI")
        assertEquals(WorkSessionState.ACTIVE, controller.state.workSession?.state)

        controller.beginParking()
        controller.park(
            "API client boundary exists",
            "Generate models from the delivered OpenAPI file",
            "Keep generated DTOs behind the repository boundary",
        )
        assertEquals(MvpScreen.PICKUP, controller.state.screen)
        assertEquals("Generate models from the delivered OpenAPI file", controller.state.parking?.nextAction)
    }

    @Test
    fun validationTargetsTheFirstRequiredFieldAndRetainsEveryValue() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.createProject(" ")
        assertEquals(FormField.PROJECT_NAME, controller.state.invalidField)

        controller.selectProject("first-project")
        controller.pickup("", "Full remains", "Polish remains")
        assertEquals(FormField.PICKUP_CORE, controller.state.invalidField)
        assertEquals("Full remains", controller.pickupDraft("first-project").fullOutcome)

        controller.pickup("Core", "Full remains", "Polish remains")
        controller.beginParking()
        controller.park("Here", "Do the next thing", "")
        assertEquals(FormField.PARKING_MUST_NOT_FORGET, controller.state.invalidField)
        assertEquals("Here", controller.state.drafts.parking.location)
        assertEquals("Do the next thing", controller.state.drafts.parking.nextAction)
    }

    @Test
    fun blankLoginDoesNotCreateSession() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login(" ")
        assertEquals(MvpScreen.LOGIN, controller.state.screen)
        assertEquals("Enter an identity token.", controller.state.error)
    }

    @Test
    fun sameTenantColdRestoreLoadsItsEncryptedBoundaryOnlyAfterLogin() {
        val drafts = RecordingDraftStore()
        val repository = InMemoryBlocksRepository()
        val first = MvpController(repository, drafts)
        assertEquals(FormDrafts(), first.state.drafts)
        assertTrue(drafts.loadedTenants.isEmpty())

        first.login("token")
        first.selectProject("first-project")
        first.updatePickupDraft(PickupDraft("first-project", "Core", "Full", "Polish"))

        val recreated = MvpController(repository, drafts)
        assertEquals(FormDrafts(), recreated.state.drafts)
        recreated.login("token")

        assertEquals("Core", recreated.pickupDraft("first-project").coreOutcome)
        assertEquals(listOf("development-tenant", "development-tenant"), drafts.loadedTenants)
    }

    @Test
    fun logoutClearsTenantDraftsAndPendingSecretsBeforeAnotherTenantSignsIn() {
        val drafts = RecordingDraftStore()
        val repository = InMemoryBlocksRepository { token -> if (token == "tenant-a-token") "tenant-a" else "tenant-b" }
        repository.pickupFailure = IllegalStateException("ambiguous timeout")
        val controller = MvpController(repository, drafts) { "tenant-a-operation-secret" }

        controller.login("tenant-a-token")
        controller.selectProject("first-project")
        controller.updateProjectNameDraft("Tenant A project")
        runCatching { controller.pickup("Tenant A outcome", "", "") }
        assertTrue(controller.state.drafts.pendingMutations.isNotEmpty())

        controller.logout()
        repository.pickupFailure = null
        controller.login("tenant-b-token")

        assertEquals(FormDrafts(), controller.state.drafts)
        assertEquals(listOf("tenant-a"), drafts.clearedTenants)
        assertEquals(FormDrafts(), drafts.load("tenant-a"))
        assertTrue(controller.state.drafts.pendingMutations.isEmpty())
        assertEquals(listOf("tenant-a"), repository.clearedLocalTenants)
    }

    @Test
    fun projectSelectionPreservesSeparatePickupDrafts() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.updatePickupDraft(PickupDraft("first-project", "First core", "", ""))
        controller.chooseAnotherProject()
        controller.createProject("Second project")
        val secondProjectId = requireNotNull(controller.state.selectedProject).id
        controller.updatePickupDraft(PickupDraft(secondProjectId, "Second core", "", ""))

        controller.chooseAnotherProject()
        controller.selectProject("first-project")
        assertEquals("First core", controller.pickupDraft("first-project").coreOutcome)
        controller.chooseAnotherProject()
        controller.selectProject(secondProjectId)
        assertEquals("Second core", controller.pickupDraft(secondProjectId).coreOutcome)
    }

    @Test
    fun successfulSubmissionClearsOnlyTheAcknowledgedDraftAndIdentity() {
        val drafts = RecordingDraftStore()
        val controller = MvpController(InMemoryBlocksRepository(), drafts)
        controller.login("token")
        controller.updateProjectNameDraft("New project")
        controller.createProject("New project")
        assertEquals("", controller.state.drafts.projectName)

        val projectId = requireNotNull(controller.state.selectedProject).id
        controller.pickup("Core", "", "")
        assertNull(controller.state.drafts.pickups[projectId])
        assertTrue(controller.state.drafts.pendingMutations.isEmpty())
        controller.beginParking()
        controller.park("Here", "Next", "Remember")
        assertEquals(ParkingDraft(), controller.state.drafts.parking)
        assertTrue(controller.state.drafts.pendingMutations.isEmpty())
    }

    @Test
    fun restoresNewestParkingFromAClosedSessionAtLogin() {
        val repository = InMemoryBlocksRepository().apply {
            setReentry(
                Reentry(
                    WorkSession(
                        id = "closed-session",
                        projectId = "first-project",
                        state = WorkSessionState.CLOSED,
                        coreOutcome = "Core",
                        rowVersion = 2,
                    ),
                    ParkingRecord("At the boundary", "Start here", "Keep context"),
                ),
            )
        }

        val controller = MvpController(repository)
        controller.login("token")

        assertEquals(MvpScreen.PICKUP, controller.state.screen)
        assertEquals(WorkSessionState.CLOSED, controller.state.workSession?.state)
        assertEquals("Start here", controller.state.parking?.nextAction)
    }

    @Test
    fun supportsResumeAndCloseOnAnExistingContinuation() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "", "")
        controller.beginParking()
        controller.park("Here", "Continue", "Remember")

        controller.resume()
        assertEquals(WorkSessionState.ACTIVE, controller.state.workSession?.state)
        controller.beginParking()
        controller.park("Later", "Restart", "New detail")
        controller.closeOnExistingContinuation()

        assertEquals(WorkSessionState.CLOSED, controller.state.workSession?.state)
        assertEquals("Restart", controller.state.parking?.nextAction)
    }

    @Test
    fun atomicallyParksAndClosesWithExactlyOneContinuation() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "", "")
        controller.beginParking(ParkingAction.PARK_AND_CLOSE)
        controller.park("Here", "Continue", "Remember")

        assertEquals(WorkSessionState.CLOSED, controller.state.workSession?.state)
        assertEquals("Here", controller.state.parking?.location)
    }

    @Test
    fun closesWithAReachedOutcomeWithoutInventingAContinuation() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "Full", "")

        controller.closeWithOutcome(OutcomeTier.CORE)

        assertNull(controller.state.workSession)
        assertNull(controller.state.parking)
        assertEquals(MvpScreen.PICKUP, controller.state.screen)
    }

    @Test
    fun oneActiveSessionConflictShowsWinnerAndRetainsPickupIntentPerProject() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Existing work", "", "")
        controller.chooseAnotherProject()
        controller.createProject("Second project")
        val requestedProjectId = requireNotNull(controller.state.selectedProject).id

        controller.pickup("New work", "Full", "")

        assertEquals("first-project", controller.state.selectedProject?.id)
        assertEquals("New work", controller.pickupDraft(requestedProjectId).coreOutcome)
        assertEquals(WorkSessionState.ACTIVE, controller.state.workSession?.state)
    }

    @Test
    fun unchangedFailedPickupReusesDurableIdentityAndPayloadEditRotatesIt() {
        val repository = InMemoryBlocksRepository().apply {
            pickupFailure = IllegalStateException("offline")
        }
        val keys = ArrayDeque(listOf("first-key", "second-key"))
        val controller = MvpController(repository, RecordingDraftStore()) { keys.removeFirst() }
        controller.login("token")
        controller.selectProject("first-project")

        runCatching { controller.pickup("Core", "Full", "") }
        runCatching { controller.pickup("Core", "Full", "") }
        controller.updatePickupDraft(PickupDraft("first-project", "Changed core", "Full", ""))
        runCatching { controller.pickup("Changed core", "Full", "") }

        assertEquals(listOf("first-key", "first-key", "second-key"), repository.pickupIdempotencyKeys)
        assertEquals("second-key", controller.state.drafts.pendingMutations.getValue("pickup").idempotencyKey)
    }

    @Test
    fun pickupTargetChangeRotatesIdentityWhileBothProjectDraftsRemain() {
        val repository = InMemoryBlocksRepository().apply {
            pickupFailure = IllegalStateException("offline")
        }
        var nextKey = 0
        val controller = MvpController(repository, RecordingDraftStore()) { "key-${++nextKey}" }
        controller.login("token")
        controller.selectProject("first-project")
        runCatching { controller.pickup("Same payload", "", "") }
        controller.chooseAnotherProject()
        controller.createProject("Second project")
        val secondProjectId = requireNotNull(controller.state.selectedProject).id
        runCatching { controller.pickup("Same payload", "", "") }

        assertNotEquals(repository.pickupIdempotencyKeys[0], repository.pickupIdempotencyKeys[1])
        assertEquals("Same payload", controller.pickupDraft("first-project").coreOutcome)
        assertEquals("Same payload", controller.pickupDraft(secondProjectId).coreOutcome)
    }

    @Test
    fun pickupRetryIdentitySurvivesSameTenantProcessRecreation() {
        val repository = InMemoryBlocksRepository().apply {
            pickupFailure = IllegalStateException("ambiguous timeout")
        }
        val drafts = RecordingDraftStore()
        val first = MvpController(repository, drafts) { "durable-pickup-key" }
        first.login("token")
        first.selectProject("first-project")
        runCatching { first.pickup("Core", "Full", "") }

        val recreated = MvpController(repository, drafts) { error("A retry key should already exist") }
        recreated.login("token")
        recreated.selectProject("first-project")
        runCatching { recreated.pickup("Core", "Full", "") }

        assertEquals(listOf("durable-pickup-key", "durable-pickup-key"), repository.pickupIdempotencyKeys)
    }

    @Test
    fun parkingRetryIdentityIncludesActionTargetAndPayload() {
        val repository = InMemoryBlocksRepository()
        var nextKey = 0
        val controller = MvpController(repository, RecordingDraftStore()) { "key-${++nextKey}" }
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "", "")
        repository.parkingFailure = IllegalStateException("offline")
        controller.beginParking(ParkingAction.PARK)

        runCatching { controller.park("Here", "Next", "Remember") }
        runCatching { controller.park("Here", "Next", "Remember") }
        controller.updateParkingDraft(ParkingDraft("Here", "Changed next", "Remember"))
        runCatching { controller.park("Here", "Changed next", "Remember") }
        controller.beginParking(ParkingAction.PARK_AND_CLOSE)
        repository.closeContinuationFailure = IllegalStateException("offline")
        runCatching { controller.park("Here", "Changed next", "Remember") }

        assertEquals(3, repository.parkingIdempotencyKeys.size)
        assertEquals(repository.parkingIdempotencyKeys[0], repository.parkingIdempotencyKeys[1])
        assertNotEquals(repository.parkingIdempotencyKeys[1], repository.parkingIdempotencyKeys[2])
        assertNotEquals(repository.parkingIdempotencyKeys[2], repository.closeContinuationIdempotencyKeys.single())
    }

    @Test
    fun parkingRetryIdentitySurvivesSameTenantProcessRecreation() {
        val repository = InMemoryBlocksRepository()
        val drafts = RecordingDraftStore()
        var nextKey = 0
        val first = MvpController(repository, drafts) { "key-${++nextKey}" }
        first.login("token")
        first.selectProject("first-project")
        first.pickup("Core", "", "")
        repository.parkingFailure = IllegalStateException("ambiguous timeout")
        first.beginParking()
        runCatching { first.park("Here", "Next", "Remember") }
        val durableKey = repository.parkingIdempotencyKeys.single()

        val recreated = MvpController(repository, drafts) { error("A retry key should already exist") }
        recreated.login("token")
        recreated.beginParking()
        runCatching { recreated.park("Here", "Next", "Remember") }

        assertEquals(listOf(durableKey, durableKey), repository.parkingIdempotencyKeys)
    }

    @Test
    fun bothCloseFormsRetainRetriesAndRotateForChangedActionOrPayload() {
        val repository = InMemoryBlocksRepository()
        var nextKey = 0
        val controller = MvpController(repository, RecordingDraftStore()) { "key-${++nextKey}" }
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "Full", "")
        controller.beginParking()
        controller.park("Here", "Next", "Remember")
        repository.closeExistingFailure = IllegalStateException("timeout")

        runCatching { controller.closeOnExistingContinuation() }
        runCatching { controller.closeOnExistingContinuation() }
        repository.closeOutcomeFailure = IllegalStateException("timeout")
        runCatching { controller.closeWithOutcome(OutcomeTier.CORE) }
        runCatching { controller.closeWithOutcome(OutcomeTier.CORE) }
        runCatching { controller.closeWithOutcome(OutcomeTier.FULL) }

        assertEquals(
            repository.closeExistingIdempotencyKeys[0],
            repository.closeExistingIdempotencyKeys[1],
        )
        assertNotEquals(
            repository.closeExistingIdempotencyKeys.last(),
            repository.closeOutcomeIdempotencyKeys.first(),
        )
        assertEquals(repository.closeOutcomeIdempotencyKeys[0], repository.closeOutcomeIdempotencyKeys[1])
        assertNotEquals(repository.closeOutcomeIdempotencyKeys[1], repository.closeOutcomeIdempotencyKeys[2])
    }

    @Test
    fun bothCloseFormRetryIdentitiesSurviveSameTenantProcessRecreation() {
        val existingRepository = InMemoryBlocksRepository()
        val existingDrafts = RecordingDraftStore()
        var existingKey = 0
        val firstExisting = MvpController(existingRepository, existingDrafts) { "existing-${++existingKey}" }
        firstExisting.login("token")
        firstExisting.selectProject("first-project")
        firstExisting.pickup("Core", "", "")
        firstExisting.beginParking()
        firstExisting.park("Here", "Next", "Remember")
        existingRepository.closeExistingFailure = IllegalStateException("ambiguous timeout")
        runCatching { firstExisting.closeOnExistingContinuation() }
        val durableExistingKey = existingRepository.closeExistingIdempotencyKeys.single()

        val recreatedExisting = MvpController(existingRepository, existingDrafts) {
            error("An existing-continuation retry key should already exist")
        }
        recreatedExisting.login("token")
        runCatching { recreatedExisting.closeOnExistingContinuation() }
        assertEquals(
            listOf(durableExistingKey, durableExistingKey),
            existingRepository.closeExistingIdempotencyKeys,
        )

        val outcomeRepository = InMemoryBlocksRepository()
        val outcomeDrafts = RecordingDraftStore()
        var outcomeKey = 0
        val firstOutcome = MvpController(outcomeRepository, outcomeDrafts) { "outcome-${++outcomeKey}" }
        firstOutcome.login("token")
        firstOutcome.selectProject("first-project")
        firstOutcome.pickup("Core", "", "")
        outcomeRepository.closeOutcomeFailure = IllegalStateException("ambiguous timeout")
        runCatching { firstOutcome.closeWithOutcome(OutcomeTier.CORE) }
        val durableOutcomeKey = outcomeRepository.closeOutcomeIdempotencyKeys.single()

        val recreatedOutcome = MvpController(outcomeRepository, outcomeDrafts) {
            error("An outcome retry key should already exist")
        }
        recreatedOutcome.login("token")
        runCatching { recreatedOutcome.closeWithOutcome(OutcomeTier.CORE) }
        assertEquals(listOf(durableOutcomeKey, durableOutcomeKey), outcomeRepository.closeOutcomeIdempotencyKeys)
    }

    @Test
    fun transitionConflictRereadsAuthoritativeStateAndRetainsLocalIntent() {
        val repository = InMemoryBlocksRepository()
        val controller = MvpController(repository, RecordingDraftStore()) { "parking-retry-key" }
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "", "")
        val active = requireNotNull(controller.state.workSession)
        repository.parkingFailure = TransitionConflictException("row version changed")
        repository.currentOverride = Reentry(
            active.copy(state = WorkSessionState.PARKED, rowVersion = 7),
            ParkingRecord("Remote", "Remote next", "Remote reminder", id = "remote-parking"),
        )

        controller.beginParking()
        controller.park("Local", "Local next", "Local reminder")

        assertEquals(7, controller.state.workSession?.rowVersion)
        assertEquals("Remote", controller.state.parking?.location)
        assertEquals("Local", controller.state.drafts.parking.location)
        assertEquals("parking-retry-key", controller.state.drafts.pendingMutations.getValue("parking").idempotencyKey)
        assertEquals(TransitionIntent.PARK, controller.state.reconciliation?.intent)
        assertEquals("row version changed", controller.state.reconciliation?.serviceMessage)
    }

    private class RecordingDraftStore : DraftStore {
        private val values = mutableMapOf<String, FormDrafts>()
        val loadedTenants = mutableListOf<String>()
        val clearedTenants = mutableListOf<String>()

        override fun load(tenantId: String): FormDrafts {
            loadedTenants += tenantId
            return values[tenantId] ?: FormDrafts()
        }

        override fun save(tenantId: String, drafts: FormDrafts) {
            if (drafts == FormDrafts()) values.remove(tenantId) else values[tenantId] = drafts
        }

        override fun clear(tenantId: String) {
            values.remove(tenantId)
            clearedTenants += tenantId
        }
    }
}
