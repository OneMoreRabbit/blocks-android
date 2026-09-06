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

        controller.beginParking()
        controller.park("Here", "Next", "Remember")
        assertEquals(ParkingDraft(), controller.state.drafts.parking)
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
