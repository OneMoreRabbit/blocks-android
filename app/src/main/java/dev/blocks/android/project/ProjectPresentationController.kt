package dev.blocks.android.project

import dev.blocks.android.api.ServiceException
import dev.blocks.android.auth.AcceptedTenantMismatchException
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.PendingMutation
import dev.blocks.android.mvp.Session
import java.security.MessageDigest

enum class ProjectScreen { LOGIN, DASHBOARD, PROJECT }

enum class ProjectFormField {
    PROJECT_NAME,
    RENAME,
    NOTES,
    PARKING_LOCATION,
    PARKING_NEXT_ACTION,
    PARKING_MUST_NOT_FORGET,
}

data class ProjectPresentationState(
    val screen: ProjectScreen = ProjectScreen.LOGIN,
    val activeProjects: List<ProjectSummary> = emptyList(),
    val archivedProjects: List<ProjectSummary> = emptyList(),
    val projectPage: ProjectPage? = null,
    val drafts: FormDrafts = FormDrafts(),
    val invalidField: ProjectFormField? = null,
    val showParkingForm: Boolean = false,
    val syncing: Boolean = false,
    val reconciliationRequired: Boolean = false,
    val conflict: ProjectConflict? = null,
    val authenticationRequired: Boolean = false,
    val sessionConfirmationRequired: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

/** UI state machine for the API 0.3 dashboard and project-page product surface. */
class ProjectPresentationController(
    private val authenticator: SessionAuthenticator,
    private val workspace: ProjectWorkspace,
    private val draftStore: DraftStore,
    private val newOperationId: () -> String = ::newOperationIdentity,
) {
    var state = ProjectPresentationState()
        private set

    private var session: Session? = null

    val isAuthenticated: Boolean
        get() = session != null

    @Synchronized
    fun login(identityToken: String): Session {
        require(identityToken.isNotBlank()) { "Google did not return an identity credential." }
        val authenticated = authenticator.login(identityToken)
        workspace.activate(authenticated)
        val continuingTenant = session?.tenantId == authenticated.tenantId
        session = authenticated
        state = if (continuingTenant) {
            state.copy(
                drafts = draftStore.load(authenticated.tenantId),
                authenticationRequired = false,
                sessionConfirmationRequired = false,
                error = null,
            )
        } else {
            ProjectPresentationState(
                screen = ProjectScreen.DASHBOARD,
                drafts = draftStore.load(authenticated.tenantId),
            )
        }
        applyResult(workspace.snapshot(authenticated), synced = false)
        return authenticated
    }

    /** Opens the Service-accepted tenant cache within the already-unlocked app sandbox. */
    @Synchronized
    fun restoreCached(tenantId: String) {
        require(tenantId.isNotBlank())
        val cachedSession = Session(tenantId)
        workspace.activate(cachedSession)
        session = cachedSession
        state = ProjectPresentationState(
            screen = ProjectScreen.DASHBOARD,
            drafts = draftStore.load(tenantId),
            sessionConfirmationRequired = true,
        )
        applyResult(workspace.snapshot(cachedSession), synced = false)
        state = state.copy(
            sessionConfirmationRequired = true,
            status = "Showing saved projects. Reconnecting in the background.",
        )
    }

    /** Lets the Service validate a fresh Google credential and confirm the accepted tenant. */
    @Synchronized
    fun confirmSession(identityToken: String): Session {
        require(identityToken.isNotBlank()) { "Google did not return an identity credential." }
        val previous = activeSession()
        val authenticated = authenticator.login(identityToken)
        if (authenticated.tenantId != previous.tenantId) {
            session = null
            state = ProjectPresentationState()
            workspace.clearLocalState(previous)
            throw AcceptedTenantMismatchException()
        }
        session = authenticated
        state = state.copy(
            authenticationRequired = false,
            sessionConfirmationRequired = false,
            error = null,
        )
        return authenticated
    }

    @Synchronized
    fun eraseAcceptedTenantCache(tenantId: String) {
        val cached = session?.takeIf { it.tenantId == tenantId } ?: Session(tenantId)
        session = null
        state = ProjectPresentationState()
        workspace.activate(cached)
        workspace.clearLocalState(cached)
    }

    @Synchronized
    fun synchronize() {
        val active = activeSession()
        state = state.copy(syncing = true, error = null)
        applyResult(workspace.backgroundSync(active), synced = true)
    }

    @Synchronized
    fun openProject(projectId: String) {
        val active = activeSession()
        val page = if (state.sessionConfirmationRequired) {
            workspace.snapshot(active).cache.pages.firstOrNull { it.project.id == projectId }
                ?: throw IllegalStateException("This project is not available in the saved offline data.")
        } else {
            workspace.projectPage(active, projectId)
        }
        val drafts = draftStore.load(active.tenantId)
        state = state.copy(
            screen = ProjectScreen.PROJECT,
            projectPage = page,
            drafts = drafts,
            showParkingForm = drafts.projectParking[projectId]?.hasContent() == true,
            invalidField = null,
            error = null,
        )
    }

    @Synchronized
    fun backToDashboard() {
        state = state.copy(
            screen = ProjectScreen.DASHBOARD,
            projectPage = null,
            showParkingForm = false,
            invalidField = null,
            error = null,
        )
    }

    @Synchronized
    fun loadArchive() {
        val active = activeSession()
        val archived = if (state.sessionConfirmationRequired) {
            workspace.snapshot(active).cache.archivedProjects
        } else {
            workspace.archivedProjects(active)
        }
        state = state.copy(archivedProjects = archived, error = null)
    }

    @Synchronized
    fun showParkingForm() {
        state = state.copy(showParkingForm = true, invalidField = null, error = null)
    }

    @Synchronized
    fun updateProjectName(value: String) = updateDraft(CREATE_SLOT, createPayload(value)) {
        it.copy(projectName = value)
    }

    @Synchronized
    fun updateRename(value: String) {
        val projectId = requireNotNull(state.projectPage).project.id
        updateDraft(renameSlot(projectId), renamePayload(projectId, value)) {
            it.copy(projectRenames = it.projectRenames + (projectId to value))
        }
    }

    @Synchronized
    fun updateNotes(value: String) {
        val projectId = requireNotNull(state.projectPage).project.id
        updateDraft(notesSlot(projectId), notesPayload(projectId, value)) {
            it.copy(projectNotes = it.projectNotes + (projectId to value))
        }
    }

    @Synchronized
    fun updateParking(value: ParkingDraft) {
        val projectId = requireNotNull(state.projectPage).project.id
        updateDraft(parkingSlot(projectId), parkingPayload(projectId, value)) {
            it.copy(projectParking = it.projectParking + (projectId to value))
        }
        state = state.copy(showParkingForm = true)
    }

    @Synchronized
    fun createProject(name: String) {
        persistDraft { it.copy(projectName = name) }
        if (name.isBlank()) return fail(ProjectFormField.PROJECT_NAME, "Enter a project name.")
        if (name.length > MAX_PROJECT_NAME) {
            return fail(ProjectFormField.PROJECT_NAME, "Project names can contain at most 200 characters.")
        }
        val operation = mutation(CREATE_SLOT, "CREATE_PROJECT", "new-project", createPayload(name))
        applyResult(
            workspace.queueCreateProject(activeSession(), name, operation.idempotencyKey),
        )
    }

    @Synchronized
    fun discardCreateProjectDraft() {
        val active = activeSession()
        val drafts = draftStore.load(active.tenantId)
        drafts.pendingMutations[CREATE_SLOT]?.let { pending ->
            applyResult(workspace.discardPendingOperation(active, pending.idempotencyKey))
        }
        val current = draftStore.load(active.tenantId)
        val cleared = current.copy(
            projectName = "",
            pendingMutations = current.pendingMutations - CREATE_SLOT,
        )
        draftStore.save(active.tenantId, cleared)
        state = state.copy(drafts = cleared, invalidField = null, error = null)
    }

    @Synchronized
    fun renameProject(name: String) {
        val page = requireNotNull(state.projectPage)
        persistDraft { it.copy(projectRenames = it.projectRenames + (page.project.id to name)) }
        if (name.isBlank()) return fail(ProjectFormField.RENAME, "Enter a project name.")
        if (name.length > MAX_PROJECT_NAME) {
            return fail(ProjectFormField.RENAME, "Project names can contain at most 200 characters.")
        }
        val operation = mutation(
            renameSlot(page.project.id),
            "RENAME_PROJECT",
            page.project.id,
            renamePayload(page.project.id, name),
        )
        applyResult(
            workspace.queueRenameProject(
                activeSession(),
                page.project.id,
                page.project.rowVersion,
                name,
                operation.idempotencyKey,
            ),
        )
    }

    @Synchronized
    fun saveNotes(body: String) {
        val page = requireNotNull(state.projectPage)
        persistDraft { it.copy(projectNotes = it.projectNotes + (page.project.id to body)) }
        if (body.length > MAX_NOTES) {
            return fail(ProjectFormField.NOTES, "Notes can contain at most 100,000 characters.")
        }
        val operation = mutation(
            notesSlot(page.project.id),
            "SAVE_NOTES",
            page.project.id,
            notesPayload(page.project.id, body),
        )
        applyResult(
            workspace.queueSaveNotes(
                activeSession(),
                page.project.id,
                page.notes.rowVersion,
                body,
                operation.idempotencyKey,
            ),
        )
    }

    @Synchronized
    fun addParking(location: String, nextAction: String, mustNotForget: String) {
        val page = requireNotNull(state.projectPage)
        val parking = ParkingDraft(location, nextAction, mustNotForget)
        persistDraft { it.copy(projectParking = it.projectParking + (page.project.id to parking)) }
        when {
            location.isBlank() -> return fail(ProjectFormField.PARKING_LOCATION, "Enter where you are.")
            nextAction.isBlank() -> return fail(ProjectFormField.PARKING_NEXT_ACTION, "Enter the next physical action.")
            mustNotForget.isBlank() -> return fail(ProjectFormField.PARKING_MUST_NOT_FORGET, "Enter what must not be forgotten.")
            location.length > MAX_PARKING_FIELD -> {
                return fail(ProjectFormField.PARKING_LOCATION, "This answer can contain at most 2,000 characters.")
            }
            nextAction.length > MAX_PARKING_FIELD -> {
                return fail(ProjectFormField.PARKING_NEXT_ACTION, "This answer can contain at most 2,000 characters.")
            }
            mustNotForget.length > MAX_PARKING_FIELD -> {
                return fail(ProjectFormField.PARKING_MUST_NOT_FORGET, "This answer can contain at most 2,000 characters.")
            }
        }
        val operation = mutation(
            parkingSlot(page.project.id),
            "ADD_PARKING",
            page.project.id,
            parkingPayload(page.project.id, parking),
        )
        applyResult(
            workspace.queueParking(
                activeSession(),
                page.project.id,
                parking,
                supersedesId = null,
                operationId = operation.idempotencyKey,
            ),
        )
    }

    @Synchronized
    fun archiveProject() {
        val project = requireNotNull(state.projectPage).project
        applyResult(
            workspace.queueArchiveProject(
                activeSession(),
                project.id,
                project.rowVersion,
                newOperationId(),
            ),
        )
        backToDashboard()
    }

    @Synchronized
    fun reopenProject(project: ProjectSummary) {
        applyResult(
            workspace.queueReopenProject(
                activeSession(),
                project.id,
                project.rowVersion,
                newOperationId(),
            ),
        )
    }

    @Synchronized
    fun discardConflict() {
        val conflict = requireNotNull(state.conflict) { "No saved change needs resolution" }
        applyResult(workspace.discardConflict(activeSession(), conflict.operationId))
    }

    @Synchronized
    fun retryConflict() {
        val conflict = requireNotNull(state.conflict) { "No saved change needs resolution" }
        require(conflict.canRetry) { "This saved change cannot be retried against a newer version" }
        applyResult(workspace.retryConflict(activeSession(), conflict.operationId, newOperationId()))
    }

    @Synchronized
    fun logout() {
        val active = session
        session = null
        state = ProjectPresentationState()
        active?.let(workspace::clearLocalState)
    }

    @Synchronized
    fun reportRepositoryError(error: Exception): Boolean {
        if (error is MembershipRevokedException) {
            session = null
            state = ProjectPresentationState(error = "Project access changed. Sign in again to continue.")
            return true
        }
        val message = when {
            error is ServiceException && error.status == 401 -> "Sign in again when you are online. Saved changes are retained."
            else -> "Blocks could not refresh just now. Saved changes are retained."
        }
        state = state.copy(
            syncing = false,
            authenticationRequired = error is ServiceException && error.status == 401,
            error = message,
        )
        return false
    }

    @Synchronized
    fun reportError(message: String) {
        state = state.copy(syncing = false, error = message)
    }

    private fun applyResult(
        result: BackgroundSyncResult,
        synced: Boolean = true,
    ) {
        val active = activeSession()
        var drafts = draftStore.load(active.tenantId)
        val unresolvedIdentities = drafts.pendingMutations.filterValues {
            it.idempotencyKey !in result.cache.retiredOperationIds
        }
        if (unresolvedIdentities != drafts.pendingMutations) {
            drafts = drafts.copy(pendingMutations = unresolvedIdentities)
            draftStore.save(active.tenantId, drafts)
        }
        val selectedId = state.projectPage?.project?.id
        val updatedPage = selectedId?.let { id -> result.cache.pages.firstOrNull { it.project.id == id } }
            ?: state.projectPage
        val pending = result.pendingLegacyOperations + result.pendingProjectOperations
        val status = when {
            result.reconciliationRequired -> "The service has a newer version. Your edit is retained for reconciliation."
            pending > 0 -> "Saved on this device. It will retry automatically."
            synced -> "Up to date."
            else -> null
        }
        state = state.copy(
            activeProjects = result.cache.activeProjects.sortedBy { it.name.lowercase() },
            archivedProjects = result.cache.archivedProjects.sortedBy { it.name.lowercase() },
            projectPage = updatedPage,
            drafts = drafts,
            invalidField = null,
            syncing = false,
            reconciliationRequired = result.reconciliationRequired,
            conflict = result.conflict,
            authenticationRequired = false,
            status = status,
            error = null,
        )
    }

    private fun updateDraft(slot: String, payload: String, update: (FormDrafts) -> FormDrafts) {
        persistDraft { drafts ->
            val updated = update(drafts)
            val pending = updated.pendingMutations[slot]
            if (pending != null && pending.payloadDigest != digest(payload)) {
                updated.copy(pendingMutations = updated.pendingMutations - slot)
            } else {
                updated
            }
        }
        state = state.copy(invalidField = null, error = null)
    }

    private fun persistDraft(update: (FormDrafts) -> FormDrafts) {
        val active = activeSession()
        val updated = update(draftStore.load(active.tenantId))
        draftStore.save(active.tenantId, updated)
        state = state.copy(drafts = updated)
    }

    private fun mutation(slot: String, operation: String, targetId: String, payload: String): PendingMutation {
        val active = activeSession()
        val drafts = draftStore.load(active.tenantId)
        val payloadDigest = digest(payload)
        val existing = drafts.pendingMutations[slot]?.takeIf {
            it.operation == operation && it.targetId == targetId && it.payloadDigest == payloadDigest
        }
        val pending = existing ?: PendingMutation(operation, targetId, payloadDigest, newOperationId())
        val updated = drafts.copy(pendingMutations = drafts.pendingMutations + (slot to pending))
        draftStore.save(active.tenantId, updated)
        state = state.copy(drafts = updated, invalidField = null, error = null)
        return pending
    }

    private fun fail(field: ProjectFormField, message: String) {
        state = state.copy(invalidField = field, error = message)
    }

    private fun activeSession(): Session = checkNotNull(session) { "No authenticated project session" }

    private fun ParkingDraft.hasContent() = location.isNotEmpty() || nextAction.isNotEmpty() || mustNotForget.isNotEmpty()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun createPayload(name: String) = encode("create", name)
    private fun renamePayload(projectId: String, name: String) = encode("rename", projectId, name)
    private fun notesPayload(projectId: String, body: String) = encode("notes", projectId, body)
    private fun parkingPayload(projectId: String, parking: ParkingDraft) = encode(
        "parking",
        projectId,
        parking.location,
        parking.nextAction,
        parking.mustNotForget,
    )

    private fun encode(vararg values: String) = values.joinToString("|") { "${it.length}:$it" }

    private companion object {
        const val CREATE_SLOT = "project-create"
        const val MAX_PROJECT_NAME = 200
        const val MAX_NOTES = 100_000
        const val MAX_PARKING_FIELD = 2_000
        fun renameSlot(projectId: String) = "project-rename:$projectId"
        fun notesSlot(projectId: String) = "project-notes:$projectId"
        fun parkingSlot(projectId: String) = "project-parking:$projectId"
    }
}
