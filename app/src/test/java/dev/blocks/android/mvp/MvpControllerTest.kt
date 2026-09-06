package dev.blocks.android.mvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MvpControllerTest {
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

        controller.chooseAnotherProject()
        controller.selectProject(controller.state.projects.last().id)
        assertEquals("API client boundary exists", controller.state.parking?.location)
    }

    @Test
    fun requiresExactlyThreeNonBlankParkingAnswers() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject(controller.state.projects.single().id)
        controller.pickup("Core", "", "")
        controller.beginParking()
        controller.park("Here", "Do the next thing", "")
        assertEquals(MvpScreen.PARKING, controller.state.screen)
        assertEquals("Complete all three parking lines.", controller.state.error)
    }

    @Test
    fun blankLoginDoesNotCreateSession() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login(" ")
        assertEquals(MvpScreen.LOGIN, controller.state.screen)
        assertEquals("Enter an identity token.", controller.state.error)
    }

    @Test
    fun validationFailureRetainsParkingDraftAcrossControllerRecreation() {
        val drafts = RecordingDraftStore()
        val first = MvpController(InMemoryBlocksRepository(), drafts)
        first.login("token")
        first.selectProject(first.state.projects.single().id)
        first.pickup("Core", "", "")
        first.beginParking()
        first.park("At the API", "Retry the request", "")

        assertEquals("At the API", first.state.drafts.parking.location)
        assertEquals("Retry the request", first.state.drafts.parking.nextAction)
        assertEquals("", first.state.drafts.parking.mustNotForget)

        val recreated = MvpController(InMemoryBlocksRepository(), drafts)
        assertEquals(first.state.drafts, recreated.state.drafts)
    }

    @Test
    fun successfulSubmissionClearsOnlyTheAcknowledgedDraft() {
        val drafts = RecordingDraftStore()
        val controller = MvpController(InMemoryBlocksRepository(), drafts)
        controller.login("token")
        controller.updateProjectNameDraft("New project")
        controller.createProject("New project")
        assertEquals("", controller.state.drafts.projectName)

        controller.pickup("Core", "", "")
        assertEquals(PickupDraft(), controller.state.drafts.pickup)
        controller.beginParking()
        controller.park("Here", "Next", "Remember")
        assertEquals(ParkingDraft(), controller.state.drafts.parking)
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
    fun oneActiveSessionConflictShowsWinnerAndRetainsPickupIntent() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Existing work", "", "")
        controller.chooseAnotherProject()
        controller.createProject("Second project")
        val requestedProjectId = requireNotNull(controller.state.selectedProject).id

        controller.pickup("New work", "Full", "")

        assertEquals("first-project", controller.state.selectedProject?.id)
        assertEquals("New work", controller.state.drafts.pickup.coreOutcome)
        assertEquals(requestedProjectId, controller.state.drafts.pickup.projectId)
        assertEquals(WorkSessionState.ACTIVE, controller.state.workSession?.state)
    }

    @Test
    fun unchangedFailedPickupReusesItsDurableIdempotencyKeyAndEditingRotatesIt() {
        val repository = InMemoryBlocksRepository().apply {
            pickupFailure = IllegalStateException("offline")
        }
        val keys = ArrayDeque(listOf("first-key", "second-key"))
        val controller = MvpController(repository, RecordingDraftStore()) { keys.removeFirst() }
        controller.login("token")
        controller.selectProject("first-project")

        runCatching { controller.pickup("Core", "Full", "") }
        runCatching { controller.pickup("Core", "Full", "") }
        controller.updatePickupDraft(controller.state.drafts.pickup.copy(coreOutcome = "Changed core"))
        runCatching { controller.pickup("Changed core", "Full", "") }

        assertEquals(listOf("first-key", "first-key", "second-key"), repository.pickupIdempotencyKeys)
        assertEquals("second-key", controller.state.drafts.pickup.idempotencyKey)
    }

    @Test
    fun unchangedFailedParkingReusesItsDurableIdempotencyKeyAndEditingRotatesIt() {
        val repository = InMemoryBlocksRepository()
        val keys = ArrayDeque(listOf("pickup-key", "parking-key", "changed-key"))
        val controller = MvpController(repository, RecordingDraftStore()) { keys.removeFirst() }
        controller.login("token")
        controller.selectProject("first-project")
        controller.pickup("Core", "", "")
        controller.beginParking()
        repository.parkingFailure = IllegalStateException("offline")

        runCatching { controller.park("Here", "Next", "Remember") }
        runCatching { controller.park("Here", "Next", "Remember") }
        controller.updateParkingDraft(controller.state.drafts.parking.copy(nextAction = "Changed next"))
        runCatching { controller.park("Here", "Changed next", "Remember") }

        assertEquals(listOf("parking-key", "parking-key", "changed-key"), repository.parkingIdempotencyKeys)
        assertEquals("changed-key", controller.state.drafts.parking.idempotencyKey)
    }

    @Test
    fun logoutClearsAuthenticatedControllerContext() {
        val controller = MvpController(InMemoryBlocksRepository())
        controller.login("token")
        controller.logout()
        assertEquals(MvpScreen.LOGIN, controller.state.screen)
        assertEquals(null, controller.state.selectedProject)
    }

    private class RecordingDraftStore : DraftStore {
        private var value = FormDrafts()
        override fun load(): FormDrafts = value
        override fun save(drafts: FormDrafts) {
            value = drafts
        }
    }
}
