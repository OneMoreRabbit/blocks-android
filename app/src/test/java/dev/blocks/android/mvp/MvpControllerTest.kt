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
}
