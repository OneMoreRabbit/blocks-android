package dev.blocks.android.mvp

import java.util.UUID

enum class MvpScreen { LOGIN, PROJECTS, PICKUP, PARKING }

data class MvpState(
    val screen: MvpScreen = MvpScreen.LOGIN,
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
    val workSession: WorkSession? = null,
    val parking: ParkingRecord? = null,
    val parkingAction: ParkingAction = ParkingAction.PARK,
    val drafts: FormDrafts = FormDrafts(),
    val error: String? = null,
)

class MvpController(
    private val repository: BlocksRepository,
    private val draftStore: DraftStore = NoOpDraftStore,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) {
    var state = MvpState(drafts = draftStore.load())
        private set
    private var session: Session? = null

    fun login(identityToken: String) {
        if (identityToken.isBlank()) return fail("Enter an identity token.")
        val activeSession = repository.login(identityToken)
        val projects = repository.projects(activeSession)
        val reentry = repository.current(activeSession)
        val reentryProject = projects.firstOrNull { it.id == reentry.workSession?.projectId }
        session = activeSession
        state = state.copy(
            screen = if (reentryProject == null) MvpScreen.PROJECTS else MvpScreen.PICKUP,
            projects = projects,
            selectedProject = reentryProject,
            workSession = reentry.workSession,
            parking = reentry.parking,
            error = null,
        )
    }

    fun createProject(name: String) {
        val activeSession = requireNotNull(session)
        if (name.isBlank()) return fail("Enter a project name.")
        val project = repository.createProject(activeSession, name.trim())
        updateProjectNameDraft("")
        state = state.copy(projects = repository.projects(activeSession), error = null)
        selectProject(project.id)
    }

    fun selectProject(projectId: String) {
        val activeSession = requireNotNull(session)
        val project = state.projects.single { it.id == projectId }
        val reentry = repository.current(activeSession)
        val appliesToProject = reentry.workSession?.projectId == project.id
        val pickupDraft = state.drafts.pickup.let {
            if (it.projectId == project.id) it else PickupDraft(projectId = project.id)
        }
        persistDrafts(state.drafts.copy(pickup = pickupDraft))
        state = state.copy(
            screen = MvpScreen.PICKUP,
            selectedProject = project,
            workSession = reentry.workSession.takeIf { appliesToProject },
            parking = reentry.parking.takeIf { appliesToProject },
            error = null,
        )
    }

    fun pickup(coreOutcome: String, fullOutcome: String, polishOutcome: String) {
        val project = requireNotNull(state.selectedProject)
        val requested = PickupDraft(project.id, coreOutcome, fullOutcome, polishOutcome)
        val existing = state.drafts.pickup
        val idempotencyKey = existing.idempotencyKey.takeIf { existing.samePayloadAs(requested) && it.isNotBlank() }
            ?: newIdempotencyKey()
        val draft = requested.copy(idempotencyKey = idempotencyKey)
        persistDrafts(state.drafts.copy(pickup = draft))
        if (coreOutcome.isBlank()) return fail("Enter the core outcome.")
        val result = repository.pickup(
            session = requireNotNull(session),
            request = PickupRequest(
                projectId = project.id,
                coreOutcome = coreOutcome.trim(),
                fullOutcome = fullOutcome.trim().ifBlank { null },
                polishOutcome = polishOutcome.trim().ifBlank { null },
            ),
            idempotencyKey = idempotencyKey,
        )
        when (result) {
            is PickupResult.Started -> {
                updatePickupDraft(PickupDraft())
                applyReentry(result.reentry, project)
            }
            is PickupResult.ExistingSession -> {
                val existingProject = state.projects.firstOrNull {
                    it.id == result.reentry.workSession?.projectId
                }
                applyReentry(result.reentry, existingProject ?: project)
                fail("Another session is already active. Showing it; your pickup draft is retained.")
            }
        }
    }

    fun beginParking(action: ParkingAction = ParkingAction.PARK) {
        check(state.workSession?.state == WorkSessionState.ACTIVE)
        state = state.copy(screen = MvpScreen.PARKING, parkingAction = action, error = null)
    }

    fun park(location: String, nextAction: String, mustNotForget: String) {
        val requested = ParkingDraft(location, nextAction, mustNotForget)
        val existing = state.drafts.parking
        val idempotencyKey = existing.idempotencyKey.takeIf { existing.samePayloadAs(requested) && it.isNotBlank() }
            ?: newIdempotencyKey()
        persistDrafts(state.drafts.copy(parking = requested.copy(idempotencyKey = idempotencyKey)))
        if (location.isBlank() || nextAction.isBlank() || mustNotForget.isBlank()) {
            return fail("Complete all three parking lines.")
        }
        val activeSession = requireNotNull(session)
        val workSession = requireNotNull(state.workSession)
        val project = requireNotNull(state.selectedProject)
        val record = ParkingRecord(location.trim(), nextAction.trim(), mustNotForget.trim())
        val reentry = when (state.parkingAction) {
            ParkingAction.PARK -> repository.park(
                activeSession,
                workSession,
                record,
                idempotencyKey,
            )
            ParkingAction.PARK_AND_CLOSE -> repository.closeWithContinuation(
                activeSession,
                workSession,
                record,
                idempotencyKey,
            )
        }
        updateParkingDraft(ParkingDraft())
        applyReentry(reentry, project)
    }

    fun resume() {
        val reentry = repository.resume(requireNotNull(session), requireNotNull(state.workSession))
        applyReentry(reentry, requireNotNull(state.selectedProject))
    }

    fun closeOnExistingContinuation() {
        val reentry = repository.closeOnExistingContinuation(
            requireNotNull(session),
            requireNotNull(state.workSession),
            requireNotNull(state.parking),
            newIdempotencyKey(),
        )
        applyReentry(reentry, requireNotNull(state.selectedProject))
    }

    fun closeWithOutcome(tier: OutcomeTier) {
        val reentry = repository.closeWithOutcome(
            requireNotNull(session),
            requireNotNull(state.workSession),
            tier,
            newIdempotencyKey(),
        )
        applyReentry(reentry, requireNotNull(state.selectedProject))
    }

    fun chooseAnotherProject() {
        state = state.copy(
            screen = MvpScreen.PROJECTS,
            selectedProject = null,
            workSession = null,
            parking = null,
            error = null,
        )
    }

    fun reportError(message: String) = fail(message)

    fun updateProjectNameDraft(value: String) {
        persistDrafts(state.drafts.copy(projectName = value))
    }

    fun updatePickupDraft(value: PickupDraft) {
        val current = state.drafts.pickup
        val updated = value.copy(
            idempotencyKey = current.idempotencyKey.takeIf { current.samePayloadAs(value) }.orEmpty(),
        )
        persistDrafts(state.drafts.copy(pickup = updated))
    }

    fun updateParkingDraft(value: ParkingDraft) {
        val current = state.drafts.parking
        val updated = value.copy(
            idempotencyKey = current.idempotencyKey.takeIf { current.samePayloadAs(value) }.orEmpty(),
        )
        persistDrafts(state.drafts.copy(parking = updated))
    }

    fun logout() {
        session = null
        state = MvpState(drafts = state.drafts)
    }

    private fun applyReentry(reentry: Reentry, project: Project) {
        val authoritativeProject = state.projects.firstOrNull { it.id == reentry.workSession?.projectId } ?: project
        state = state.copy(
            screen = MvpScreen.PICKUP,
            selectedProject = authoritativeProject,
            workSession = reentry.workSession,
            parking = reentry.parking,
            error = null,
        )
    }

    private fun persistDrafts(drafts: FormDrafts) {
        draftStore.save(drafts)
        state = state.copy(drafts = drafts)
    }

    private fun fail(message: String) {
        state = state.copy(error = message)
    }

    private fun PickupDraft.samePayloadAs(other: PickupDraft) =
        projectId == other.projectId &&
            coreOutcome == other.coreOutcome &&
            fullOutcome == other.fullOutcome &&
            polishOutcome == other.polishOutcome

    private fun ParkingDraft.samePayloadAs(other: ParkingDraft) =
        location == other.location &&
            nextAction == other.nextAction &&
            mustNotForget == other.mustNotForget
}
