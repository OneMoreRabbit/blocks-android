package dev.blocks.android.mvp

enum class MvpScreen { LOGIN, PROJECTS, PICKUP, PARKING }

data class MvpState(
    val screen: MvpScreen = MvpScreen.LOGIN,
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
    val parking: ParkingRecord? = null,
    val error: String? = null,
)

class MvpController(private val repository: BlocksRepository) {
    var state = MvpState()
        private set
    private var session: Session? = null

    fun login(identityToken: String) {
        if (identityToken.isBlank()) return fail("Enter an identity token.")
        val activeSession = repository.login(identityToken)
        session = activeSession
        state = MvpState(screen = MvpScreen.PROJECTS, projects = repository.projects(activeSession))
    }

    fun createProject(name: String) {
        val activeSession = requireNotNull(session)
        if (name.isBlank()) return fail("Enter a project name.")
        val project = repository.createProject(activeSession, name.trim())
        state = state.copy(projects = repository.projects(activeSession), error = null)
        selectProject(project.id)
    }

    fun selectProject(projectId: String) {
        val activeSession = requireNotNull(session)
        val project = state.projects.single { it.id == projectId }
        state = state.copy(
            screen = MvpScreen.PICKUP,
            selectedProject = project,
            parking = repository.latestParking(activeSession, project.id),
            error = null,
        )
    }

    fun beginParking() {
        checkNotNull(state.selectedProject)
        state = state.copy(screen = MvpScreen.PARKING, error = null)
    }

    fun park(location: String, nextAction: String, mustNotForget: String) {
        if (location.isBlank() || nextAction.isBlank() || mustNotForget.isBlank()) {
            return fail("Complete all three parking lines.")
        }
        val activeSession = requireNotNull(session)
        val project = requireNotNull(state.selectedProject)
        val record = ParkingRecord(location.trim(), nextAction.trim(), mustNotForget.trim())
        repository.park(activeSession, project.id, record)
        state = state.copy(screen = MvpScreen.PICKUP, parking = record, error = null)
    }

    fun chooseAnotherProject() {
        state = state.copy(
            screen = MvpScreen.PROJECTS,
            selectedProject = null,
            parking = null,
            error = null,
        )
    }

    fun reportError(message: String) = fail(message)

    private fun fail(message: String) {
        state = state.copy(error = message)
    }
}
