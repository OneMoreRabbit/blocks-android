package dev.blocks.android.mvp

import java.security.MessageDigest
import java.util.UUID

enum class MvpScreen { LOGIN, PROJECTS, PICKUP, PARKING }

enum class FormField {
    PROJECT_NAME,
    PICKUP_CORE,
    PARKING_LOCATION,
    PARKING_NEXT_ACTION,
    PARKING_MUST_NOT_FORGET,
}

enum class TransitionIntent(val description: String) {
    PARK("park"),
    PARK_AND_CLOSE("park and close"),
    RESUME("resume"),
    CLOSE_EXISTING_CONTINUATION("close with the existing continuation"),
    CLOSE_CORE("close at core"),
    CLOSE_FULL("close at full"),
    CLOSE_POLISH("close at polish"),
}

data class Reconciliation(
    val intent: TransitionIntent,
    val serviceMessage: String,
)

data class MvpState(
    val screen: MvpScreen = MvpScreen.LOGIN,
    val projects: List<Project> = emptyList(),
    val selectedProject: Project? = null,
    val workSession: WorkSession? = null,
    val parking: ParkingRecord? = null,
    val parkingAction: ParkingAction = ParkingAction.PARK,
    val drafts: FormDrafts = FormDrafts(),
    val syncStatus: SyncStatus = SyncStatus(),
    val invalidField: FormField? = null,
    val reconciliation: Reconciliation? = null,
    val error: String? = null,
)

class MvpController(
    private val repository: BlocksRepository,
    private val draftStore: DraftStore = NoOpDraftStore,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) {
    var state = MvpState()
        private set
    private var session: Session? = null

    fun login(identityToken: String) {
        if (identityToken.isBlank()) return fail("Enter an identity token.")
        val activeSession = repository.login(identityToken)
        val projects = repository.projects(activeSession)
        val sync = repository.synchronize(activeSession)
        val tenantDrafts = draftStore.load(activeSession.tenantId)
        val reentry = sync.reentry
        val reentryProject = projects.firstOrNull { it.id == reentry.workSession?.projectId }
        session = activeSession
        state = MvpState(
            screen = if (reentryProject == null) MvpScreen.PROJECTS else MvpScreen.PICKUP,
            projects = projects,
            selectedProject = reentryProject,
            workSession = reentry.workSession,
            parking = reentry.parking,
            drafts = tenantDrafts,
            syncStatus = sync.status,
        )
    }

    fun createProject(name: String) {
        val activeSession = requireNotNull(session)
        if (name.isBlank()) return fail("Enter a project name.", FormField.PROJECT_NAME)
        val project = repository.createProject(activeSession, name.trim())
        updateProjectNameDraft("")
        state = state.copy(projects = repository.projects(activeSession), error = null, invalidField = null)
        selectProject(project.id)
    }

    fun selectProject(projectId: String) {
        val activeSession = requireNotNull(session)
        val project = state.projects.single { it.id == projectId }
        val reentry = repository.current(activeSession)
        val appliesToProject = reentry.workSession?.projectId == project.id
        state = state.copy(
            screen = MvpScreen.PICKUP,
            selectedProject = project,
            workSession = reentry.workSession.takeIf { appliesToProject },
            parking = reentry.parking.takeIf { appliesToProject },
            error = null,
            invalidField = null,
            reconciliation = null,
        )
    }

    fun pickupDraft(projectId: String): PickupDraft =
        state.drafts.pickups[projectId] ?: PickupDraft(projectId = projectId)

    fun pickup(coreOutcome: String, fullOutcome: String, polishOutcome: String) {
        val project = requireNotNull(state.selectedProject)
        val requested = PickupDraft(project.id, coreOutcome, fullOutcome, polishOutcome)
        persistPickupDraft(requested)
        if (coreOutcome.isBlank()) return fail("Enter the core outcome.", FormField.PICKUP_CORE)

        val request = PickupRequest(
            projectId = project.id,
            coreOutcome = coreOutcome.trim(),
            fullOutcome = fullOutcome.trim().ifBlank { null },
            polishOutcome = polishOutcome.trim().ifBlank { null },
        )
        val idempotencyKey = mutationKey(
            slot = PICKUP_MUTATION,
            operation = "PICKUP",
            targetId = project.id,
            payload = listOf(
                request.coreOutcome,
                request.fullOutcome.orEmpty(),
                request.polishOutcome.orEmpty(),
                request.boundaryAt.orEmpty(),
            ),
        )
        when (val result = repository.pickup(requireNotNull(session), request, idempotencyKey)) {
            is PickupResult.Started -> {
                persistDrafts(
                    state.drafts.copy(
                        pickups = state.drafts.pickups - project.id,
                        pendingMutations = state.drafts.pendingMutations - PICKUP_MUTATION,
                    ),
                )
                applyReentry(result.reentry, project)
            }
            is PickupResult.ExistingSession -> {
                val activeSession = requireNotNull(session)
                val sync = repository.synchronize(activeSession)
                val existingProject = state.projects.firstOrNull {
                    it.id == sync.reentry.workSession?.projectId
                }
                applyReentry(sync.reentry, existingProject ?: project)
                state = state.copy(syncStatus = sync.status)
                fail("Another session is already active. Showing it; your pickup draft is retained.")
            }
        }
    }

    fun beginParking(action: ParkingAction = ParkingAction.PARK) {
        check(state.workSession?.state == WorkSessionState.ACTIVE)
        if (state.parkingAction != action) clearMutation(PARKING_MUTATION)
        state = state.copy(
            screen = MvpScreen.PARKING,
            parkingAction = action,
            error = null,
            invalidField = null,
            reconciliation = null,
        )
    }

    fun park(location: String, nextAction: String, mustNotForget: String) {
        val requested = ParkingDraft(location, nextAction, mustNotForget)
        persistParkingDraft(requested)
        firstInvalidParkingField(requested)?.let {
            return fail("Complete all three parking lines.", it)
        }

        val activeSession = requireNotNull(session)
        val workSession = requireNotNull(state.workSession)
        val project = requireNotNull(state.selectedProject)
        val action = state.parkingAction
        val record = ParkingRecord(location.trim(), nextAction.trim(), mustNotForget.trim())
        val operation = if (action == ParkingAction.PARK) "PARK" else "PARK_AND_CLOSE"
        val idempotencyKey = mutationKey(
            slot = PARKING_MUTATION,
            operation = operation,
            targetId = workSession.id,
            payload = listOf(record.location, record.nextAction, record.mustNotForget),
        )
        performTransition(
            intent = if (action == ParkingAction.PARK) TransitionIntent.PARK else TransitionIntent.PARK_AND_CLOSE,
            project = project,
        ) {
            val reentry = when (action) {
                ParkingAction.PARK -> repository.park(activeSession, workSession, record, idempotencyKey)
                ParkingAction.PARK_AND_CLOSE -> repository.closeWithContinuation(
                    activeSession,
                    workSession,
                    record,
                    idempotencyKey,
                )
            }
            persistDrafts(
                state.drafts.copy(
                    parking = ParkingDraft(),
                    pendingMutations = state.drafts.pendingMutations - PARKING_MUTATION,
                ),
            )
            applyReentry(reentry, project)
        }
    }

    fun resume() {
        val project = requireNotNull(state.selectedProject)
        performTransition(TransitionIntent.RESUME, project) {
            applyReentry(
                repository.resume(requireNotNull(session), requireNotNull(state.workSession)),
                project,
            )
        }
    }

    fun closeOnExistingContinuation() {
        val activeSession = requireNotNull(session)
        val workSession = requireNotNull(state.workSession)
        val parking = requireNotNull(state.parking)
        val project = requireNotNull(state.selectedProject)
        val idempotencyKey = mutationKey(
            slot = CLOSE_MUTATION,
            operation = "CLOSE_EXISTING_CONTINUATION",
            targetId = workSession.id,
            payload = listOf(parking.id.orEmpty()),
        )
        performTransition(TransitionIntent.CLOSE_EXISTING_CONTINUATION, project) {
            val reentry = repository.closeOnExistingContinuation(
                activeSession,
                workSession,
                parking,
                idempotencyKey,
            )
            clearMutation(CLOSE_MUTATION)
            applyReentry(reentry, project)
        }
    }

    fun closeWithOutcome(tier: OutcomeTier) {
        val activeSession = requireNotNull(session)
        val workSession = requireNotNull(state.workSession)
        val project = requireNotNull(state.selectedProject)
        val idempotencyKey = mutationKey(
            slot = CLOSE_MUTATION,
            operation = "CLOSE_WITH_OUTCOME",
            targetId = workSession.id,
            payload = listOf(tier.wireValue),
        )
        performTransition(tier.toIntent(), project) {
            val reentry = repository.closeWithOutcome(activeSession, workSession, tier, idempotencyKey)
            clearMutation(CLOSE_MUTATION)
            applyReentry(reentry, project)
        }
    }

    fun chooseAnotherProject() {
        state = state.copy(
            screen = MvpScreen.PROJECTS,
            selectedProject = null,
            workSession = null,
            parking = null,
            error = null,
            invalidField = null,
            reconciliation = null,
        )
    }

    fun reportError(message: String) = fail(message)

    fun synchronize() {
        val activeSession = requireNotNull(session)
        val sync = repository.synchronize(activeSession)
        applySynchronizedState(activeSession, sync)
    }

    fun acceptAuthoritativeState() {
        val activeSession = requireNotNull(session)
        val sync = repository.resolveReconciliation(activeSession)
        applySynchronizedState(activeSession, sync)
    }

    private fun applySynchronizedState(activeSession: Session, sync: SyncResult) {
        val projects = repository.projects(activeSession)
        val selectedProject = projects.firstOrNull { it.id == sync.reentry.workSession?.projectId }
            ?: state.selectedProject?.let { selected -> projects.firstOrNull { it.id == selected.id } }
        state = state.copy(
            projects = projects,
            selectedProject = selectedProject,
            workSession = sync.reentry.workSession,
            parking = sync.reentry.parking,
            drafts = draftStore.load(activeSession.tenantId),
            syncStatus = sync.status,
            screen = if (selectedProject == null) MvpScreen.PROJECTS else MvpScreen.PICKUP,
            error = null,
            invalidField = null,
            reconciliation = null,
        )
    }

    fun updateProjectNameDraft(value: String) {
        persistDrafts(state.drafts.copy(projectName = value))
        if (value.isNotBlank() && state.invalidField == FormField.PROJECT_NAME) clearValidation()
    }

    fun updatePickupDraft(value: PickupDraft) {
        val current = state.drafts.pickups[value.projectId]
        val pending = if (current != value) state.drafts.pendingMutations - PICKUP_MUTATION else state.drafts.pendingMutations
        persistDrafts(
            state.drafts.copy(
                pickups = state.drafts.pickups + (value.projectId to value),
                pendingMutations = pending,
            ),
        )
        if (value.coreOutcome.isNotBlank() && state.invalidField == FormField.PICKUP_CORE) clearValidation()
    }

    fun updateParkingDraft(value: ParkingDraft) {
        val pending = if (state.drafts.parking != value) {
            state.drafts.pendingMutations - PARKING_MUTATION
        } else {
            state.drafts.pendingMutations
        }
        persistDrafts(state.drafts.copy(parking = value, pendingMutations = pending))
        val invalidWasCompleted = when (state.invalidField) {
            FormField.PARKING_LOCATION -> value.location.isNotBlank()
            FormField.PARKING_NEXT_ACTION -> value.nextAction.isNotBlank()
            FormField.PARKING_MUST_NOT_FORGET -> value.mustNotForget.isNotBlank()
            else -> false
        }
        if (invalidWasCompleted) clearValidation()
    }

    fun logout() {
        val activeSession = session
        session = null
        state = MvpState()
        activeSession?.let {
            val draftFailure = runCatching { draftStore.clear(it.tenantId) }.exceptionOrNull()
            val syncFailure = runCatching { repository.clearLocalState(it) }.exceptionOrNull()
            (draftFailure ?: syncFailure)?.let { failure ->
                throw IllegalStateException("Could not clear local tenant state", failure)
            }
        }
    }

    private fun performTransition(
        intent: TransitionIntent,
        project: Project,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (conflict: TransitionConflictException) {
            val activeSession = requireNotNull(session)
            val sync = repository.synchronize(activeSession)
            applyReentry(sync.reentry, project)
            state = state.copy(
                error = null,
                drafts = draftStore.load(activeSession.tenantId),
                syncStatus = sync.status,
                reconciliation = Reconciliation(intent, conflict.message.orEmpty()),
            )
        }
    }

    private fun applyReentry(reentry: Reentry, project: Project) {
        val authoritativeProject = state.projects.firstOrNull { it.id == reentry.workSession?.projectId } ?: project
        state = state.copy(
            screen = MvpScreen.PICKUP,
            selectedProject = authoritativeProject,
            workSession = reentry.workSession,
            parking = reentry.parking,
            error = null,
            invalidField = null,
            reconciliation = null,
        )
    }

    private fun persistPickupDraft(draft: PickupDraft) {
        val current = state.drafts.pickups[draft.projectId]
        val pending = if (current != null && current != draft) {
            state.drafts.pendingMutations - PICKUP_MUTATION
        } else {
            state.drafts.pendingMutations
        }
        persistDrafts(
            state.drafts.copy(
                pickups = state.drafts.pickups + (draft.projectId to draft),
                pendingMutations = pending,
            ),
        )
    }

    private fun persistParkingDraft(draft: ParkingDraft) {
        val pending = if (state.drafts.parking != draft) {
            state.drafts.pendingMutations - PARKING_MUTATION
        } else {
            state.drafts.pendingMutations
        }
        persistDrafts(state.drafts.copy(parking = draft, pendingMutations = pending))
    }

    private fun mutationKey(
        slot: String,
        operation: String,
        targetId: String,
        payload: List<String>,
    ): String {
        val requested = PendingMutation(operation, targetId, payload.digest(), "")
        val existing = state.drafts.pendingMutations[slot]
        if (existing?.sameIdentityAs(requested) == true && existing.idempotencyKey.isNotBlank()) {
            return existing.idempotencyKey
        }
        val created = requested.copy(idempotencyKey = newIdempotencyKey())
        persistDrafts(
            state.drafts.copy(pendingMutations = state.drafts.pendingMutations + (slot to created)),
        )
        return created.idempotencyKey
    }

    private fun clearMutation(slot: String) {
        if (slot in state.drafts.pendingMutations) {
            persistDrafts(state.drafts.copy(pendingMutations = state.drafts.pendingMutations - slot))
        }
    }

    private fun persistDrafts(drafts: FormDrafts) {
        draftStore.save(requireNotNull(session).tenantId, drafts)
        state = state.copy(drafts = drafts)
    }

    private fun fail(message: String, invalidField: FormField? = null) {
        state = state.copy(error = message, invalidField = invalidField, reconciliation = null)
    }

    private fun clearValidation() {
        state = state.copy(error = null, invalidField = null)
    }

    private fun firstInvalidParkingField(draft: ParkingDraft): FormField? = when {
        draft.location.isBlank() -> FormField.PARKING_LOCATION
        draft.nextAction.isBlank() -> FormField.PARKING_NEXT_ACTION
        draft.mustNotForget.isBlank() -> FormField.PARKING_MUST_NOT_FORGET
        else -> null
    }

    private fun OutcomeTier.toIntent() = when (this) {
        OutcomeTier.CORE -> TransitionIntent.CLOSE_CORE
        OutcomeTier.FULL -> TransitionIntent.CLOSE_FULL
        OutcomeTier.POLISH -> TransitionIntent.CLOSE_POLISH
    }

    private fun PendingMutation.sameIdentityAs(other: PendingMutation) =
        operation == other.operation && targetId == other.targetId && payloadDigest == other.payloadDigest

    private fun List<String>.digest(): String {
        val canonical = joinToString(separator = "") { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            "${bytes.size}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val PICKUP_MUTATION = "pickup"
        const val PARKING_MUTATION = "parking"
        const val CLOSE_MUTATION = "close"
    }
}
