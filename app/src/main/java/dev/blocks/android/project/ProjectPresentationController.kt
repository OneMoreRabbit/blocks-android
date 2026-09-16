package dev.blocks.android.project

import dev.blocks.android.api.IncompatibleServiceApiException
import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.PendingMutation
import dev.blocks.android.mvp.Session
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.DirectTagWritePlan
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLink
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagProgrammingFailure
import java.io.IOException
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
    val parkingHistory: List<ProjectParking> = emptyList(),
    val parkingHistoryHasMore: Boolean = false,
    val localBlocks: List<LocalBlockRecord> = emptyList(),
    val projectCovers: List<ProjectCover> = emptyList(),
    val drafts: FormDrafts = FormDrafts(),
    val invalidField: ProjectFormField? = null,
    val showParkingForm: Boolean = false,
    val dashboardView: DashboardView = DashboardView.TILES,
    val archiveMode: Boolean = false,
    val pendingChanges: Boolean = false,
    val syncing: Boolean = false,
    val reconciliationRequired: Boolean = false,
    val conflict: ProjectConflict? = null,
    val authenticationRequired: Boolean = false,
    val sessionConfirmationRequired: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

/** In-memory scope for one foreground NFC attempt; no credential or physical tag identity. */
data class DirectTagAttemptScope(
    val tenantId: String,
    val projectKey: ProjectKey,
)

/** UI state machine for the standalone Dashboard and Project product surface. */
class ProjectPresentationController(
    private val authenticator: SessionAuthenticator,
    private val workspace: ProjectWorkspace,
    private val newOperationId: () -> String = ::newOperationIdentity,
) {
    var state = ProjectPresentationState()
        private set

    private var session: Session? = null

    val isAuthenticated: Boolean
        get() = session != null

    /** A cached tenant is locally open, but only an in-memory Service credential may synchronize. */
    val canSynchronize: Boolean
        get() = session?.identityToken?.isNotBlank() == true &&
            !state.authenticationRequired && !state.sessionConfirmationRequired

    val activeTenantId: String?
        get() = session?.tenantId

    /** Performs only the online Service validation. It does not expose a tenant cache. */
    @Synchronized
    fun authenticate(identityToken: String): Session {
        require(identityToken.isNotBlank()) { "Google did not return an identity credential." }
        return authenticator.login(identityToken)
    }

    @Synchronized
    fun login(identityToken: String): Session {
        return activateAuthenticated(authenticate(identityToken))
    }

    /** Opens a tenant only after the caller has accepted its Service-validated account binding. */
    @Synchronized
    fun activateAuthenticated(authenticated: Session): Session {
        workspace.activate(authenticated)
        val continuingTenant = session?.tenantId == authenticated.tenantId
        session = authenticated
        val preferences = workspace.uiPreferences(authenticated)
        state = if (continuingTenant) {
            state.copy(
                drafts = workspace.projectDrafts(authenticated),
                dashboardView = preferences.dashboardView,
                archiveMode = preferences.archiveMode,
                authenticationRequired = false,
                sessionConfirmationRequired = false,
                error = null,
            )
        } else {
            ProjectPresentationState(
                screen = ProjectScreen.DASHBOARD,
                drafts = workspace.projectDrafts(authenticated),
                dashboardView = preferences.dashboardView,
                archiveMode = preferences.archiveMode,
            )
        }
        applyResult(workspace.snapshot(authenticated))
        return authenticated
    }

    /** Opens the Service-accepted tenant cache within the already-unlocked app sandbox. */
    @Synchronized
    fun restoreCached(tenantId: String) {
        require(tenantId.isNotBlank())
        val cachedSession = Session(tenantId)
        workspace.activate(cachedSession)
        session = cachedSession
        val preferences = workspace.uiPreferences(cachedSession)
        state = ProjectPresentationState(
            screen = ProjectScreen.DASHBOARD,
            drafts = workspace.projectDrafts(cachedSession),
            dashboardView = preferences.dashboardView,
            archiveMode = preferences.archiveMode,
            sessionConfirmationRequired = true,
        )
        applyResult(workspace.snapshot(cachedSession))
        state = state.copy(
            sessionConfirmationRequired = true,
            status = null,
        )
    }

    /** Lets the Service validate a fresh Google credential and confirm the accepted tenant. */
    @Synchronized
    fun confirmSession(identityToken: String): Session {
        return activateAuthenticated(authenticate(identityToken))
    }

    @Synchronized
    fun eraseAcceptedTenantCache(tenantId: String) {
        val cached = session?.takeIf { it.tenantId == tenantId } ?: Session(tenantId)
        session = null
        state = ProjectPresentationState()
        workspace.activate(cached)
        try {
            workspace.clearLocalState(cached)
        } finally {
            workspace.deactivate(cached)
        }
    }

    fun synchronize() {
        val active = synchronized(this) {
            state = state.copy(syncing = true, error = null)
            activeSession()
        }
        val result = workspace.backgroundSync(active)
        synchronized(this) {
            if (session?.tenantId == active.tenantId) applyResult(result)
        }
    }

    @Synchronized
    fun openProject(projectId: String) {
        val active = activeSession()
        val page = workspace.projectPage(active, projectId)
        val history = workspace.parkingHistory(active, page.project.id, 0, PARKING_HISTORY_PAGE_SIZE)
        val drafts = workspace.projectDrafts(active)
        state = state.copy(
            screen = ProjectScreen.PROJECT,
            projectPage = page,
            parkingHistory = history.records,
            parkingHistoryHasMore = history.hasMore,
            drafts = drafts,
            showParkingForm = drafts.projectParking[projectId]?.hasContent() == true,
            invalidField = null,
            error = null,
        )
    }

    fun readProjectCoverContent(cover: ProjectCover, thumbnail: Boolean): ByteArray {
        val active = synchronized(this) { activeSession() }
        return workspace.readProjectCoverContent(active, cover, thumbnail)
    }

    @Synchronized
    fun saveProjectCover(cover: ProjectCoverContent) {
        val page = activeProjectPage()
        require(cover.projectId == page.project.id) { "The selected project changed during cover import" }
        applyResult(workspace.saveProjectCover(activeSession(), cover))
    }

    @Synchronized
    fun removeProjectCover() {
        val page = activeProjectPage()
        applyResult(workspace.removeProjectCover(activeSession(), page.project.id))
    }

    @Synchronized
    fun backToDashboard() {
        val active = activeSession()
        val preferences = workspace.updateUiPreferences(active) { it.copy(archiveMode = false) }
        state = state.copy(
            screen = ProjectScreen.DASHBOARD,
            projectPage = null,
            parkingHistory = emptyList(),
            parkingHistoryHasMore = false,
            showParkingForm = false,
            archiveMode = preferences.archiveMode,
            invalidField = null,
            error = null,
        )
    }

    @Synchronized
    fun showArchive() {
        val active = activeSession()
        val archived = if (state.sessionConfirmationRequired) {
            workspace.snapshot(active).cache.archivedProjects
        } else {
            workspace.archivedProjects(active)
        }
        val preferences = workspace.updateUiPreferences(active) { it.copy(archiveMode = true) }
        state = state.copy(
            screen = ProjectScreen.DASHBOARD,
            projectPage = null,
            parkingHistory = emptyList(),
            parkingHistoryHasMore = false,
            archivedProjects = archived,
            archiveMode = preferences.archiveMode,
            error = null,
        )
    }

    @Synchronized
    fun showMainDashboard() {
        val active = activeSession()
        val preferences = workspace.updateUiPreferences(active) { it.copy(archiveMode = false) }
        state = state.copy(
            screen = ProjectScreen.DASHBOARD,
            projectPage = null,
            parkingHistory = emptyList(),
            parkingHistoryHasMore = false,
            archiveMode = preferences.archiveMode,
            error = null,
        )
    }

    @Synchronized
    fun toggleDashboardView() {
        val active = activeSession()
        val preferences = workspace.updateUiPreferences(active) {
            it.copy(
                dashboardView = if (it.dashboardView == DashboardView.LIST) {
                    DashboardView.TILES
                } else {
                    DashboardView.LIST
                },
            )
        }
        state = state.copy(dashboardView = preferences.dashboardView, error = null)
    }

    @Synchronized
    fun showParkingForm() {
        activeProjectPage()
        state = state.copy(showParkingForm = true, invalidField = null, error = null)
    }

    @Synchronized
    fun hideParkingForm() {
        state = state.copy(showParkingForm = false, invalidField = null, error = null)
    }

    @Synchronized
    fun loadOlderParking() {
        val active = activeSession()
        val page = requireNotNull(state.projectPage)
        val next = workspace.parkingHistory(
            active,
            page.project.id,
            state.parkingHistory.size,
            PARKING_HISTORY_PAGE_SIZE,
        )
        state = state.copy(
            parkingHistory = (state.parkingHistory + next.records).distinctBy { it.id },
            parkingHistoryHasMore = next.hasMore,
            error = null,
        )
    }

    @Synchronized
    fun prepareDirectTagWrite(): DirectTagWritePlan {
        val project = activeProjectPage().project
        return workspace.prepareDirectTagWrite(activeSession(), project.id, project.name)
    }

    @Synchronized
    fun currentProjectLink(): ProjectLink {
        val project = requireNotNull(state.projectPage).project
        return workspace.projectLink(activeSession(), project.id)
    }

    @Synchronized
    fun captureDirectTagAttemptScope(): DirectTagAttemptScope {
        val active = activeSession()
        val project = activeProjectPage().project
        return DirectTagAttemptScope(active.tenantId, workspace.projectLink(active, project.id).key)
    }

    @Synchronized
    fun matchesDirectTagAttemptScope(scope: DirectTagAttemptScope): Boolean {
        val active = session ?: return false
        val project = state.projectPage?.project ?: return false
        if (active.tenantId != scope.tenantId) return false
        return runCatching { workspace.projectLink(active, project.id).key == scope.projectKey }.getOrDefault(false)
    }

    @Synchronized
    fun commitVerifiedDirectTagWrite(
        scope: DirectTagAttemptScope,
        plan: DirectTagWritePlan,
        replacedToken: OpaqueTagToken?,
    ) {
        applyResult(workspace.commitVerifiedDirectTagWrite(scopedTagSession(scope), plan, replacedToken))
    }

    @Synchronized
    fun commitVerifiedDirectTagClear(scope: DirectTagAttemptScope, erasedToken: OpaqueTagToken?) {
        applyResult(
            workspace.commitVerifiedDirectTagClear(scopedTagSession(scope), erasedToken, newOperationId()),
        )
    }

    @Synchronized
    fun beginTagProgramming(label: String): LocalBlockRecord {
        val projectId = requireNotNull(state.projectPage).project.id
        val block = workspace.beginTagProgramming(activeSession(), projectId, label)
        state = state.copy(localBlocks = workspace.localBlocks(activeSession()), error = null)
        return block
    }

    @Synchronized
    fun markTagWriteAttempt(clientBlockId: String): LocalBlockRecord {
        val block = workspace.markTagWriteAttempt(activeSession(), clientBlockId)
        state = state.copy(localBlocks = workspace.localBlocks(activeSession()), error = null)
        return block
    }

    @Synchronized
    fun recordTagProgrammingFailure(
        clientBlockId: String,
        failure: TagProgrammingFailure,
    ): LocalBlockRecord {
        val block = workspace.recordTagProgrammingFailure(activeSession(), clientBlockId, failure)
        state = state.copy(localBlocks = workspace.localBlocks(activeSession()), error = null)
        return block
    }

    @Synchronized
    fun discardPendingTagProgramming(clientBlockId: String) {
        applyResult(workspace.discardPendingTagProgramming(activeSession(), clientBlockId))
    }

    @Synchronized
    fun authorizeTagReplacement(clientBlockId: String, existingToken: OpaqueTagToken): LocalBlockRecord {
        val block = workspace.authorizeTagReplacement(activeSession(), clientBlockId, existingToken)
        state = state.copy(localBlocks = workspace.localBlocks(activeSession()), error = null)
        return block
    }

    @Synchronized
    fun completeTagProgramming(
        clientBlockId: String,
        verifiedToken: OpaqueTagToken,
        medium: TagActivationMedium,
    ) {
        applyResult(
            workspace.completeTagProgramming(activeSession(), clientBlockId, verifiedToken, medium),
        )
    }

    @Synchronized
    fun resolveTagLocally(token: OpaqueTagToken): LocalTagResolution =
        workspace.resolveTagLocally(activeSession(), token)

    @Synchronized
    fun resolveProjectLinkLocally(key: ProjectKey): String? =
        workspace.resolveProjectLinkLocally(activeSession(), key)

    fun resolveTagThroughService(token: OpaqueTagToken): LocalTagResolution {
        val active = synchronized(this) { activeSession() }
        val resolution = workspace.resolveTagThroughService(active, token)
        synchronized(this) {
            if (session?.tenantId == active.tenantId) {
                applyResult(workspace.snapshot(active))
            }
        }
        return resolution
    }

    @Synchronized
    fun assignLocalBlock(clientBlockId: String, projectId: String) {
        applyResult(workspace.assignLocalBlock(activeSession(), clientBlockId, projectId, newOperationId()))
    }

    @Synchronized
    fun unbindLocalBlock(clientBlockId: String) {
        applyResult(workspace.unbindLocalBlock(activeSession(), clientBlockId, newOperationId()))
    }

    @Synchronized
    fun updateProjectName(value: String) = updateDraft(CREATE_SLOT, createPayload(value)) {
        it.copy(projectName = value)
    }

    @Synchronized
    fun updateRename(value: String) {
        val projectId = activeProjectPage().project.id
        updateDraft(renameSlot(projectId), renamePayload(projectId, value)) {
            it.copy(projectRenames = it.projectRenames + (projectId to value))
        }
    }

    @Synchronized
    fun updateNotes(value: String) {
        val projectId = activeProjectPage().project.id
        updateDraft(notesSlot(projectId), notesPayload(projectId, value)) {
            it.copy(projectNotes = it.projectNotes + (projectId to value))
        }
    }

    @Synchronized
    fun updateParking(value: ParkingDraft) {
        val projectId = activeProjectPage().project.id
        updateDraft(parkingSlot(projectId), parkingPayload(projectId, value)) {
            it.copy(projectParking = it.projectParking + (projectId to value))
        }
        state = state.copy(showParkingForm = true)
    }

    /** Restores configuration-only form state; this never crosses the persistence boundary. */
    @Synchronized
    fun restoreTransientDrafts(drafts: FormDrafts, showParkingForm: Boolean) {
        activeSession()
        state = state.copy(drafts = drafts, showParkingForm = showParkingForm)
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
        activeSession()
        val current = state.drafts
        val cleared = current.copy(
            projectName = "",
            pendingMutations = current.pendingMutations - CREATE_SLOT,
        )
        state = state.copy(drafts = cleared, invalidField = null, error = null)
    }

    @Synchronized
    fun renameProject(name: String) {
        val page = activeProjectPage()
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
        val page = activeProjectPage()
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
        val page = activeProjectPage()
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
        state = state.copy(showParkingForm = false)
    }

    @Synchronized
    fun archiveProject() {
        val project = activeProjectPage().project
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
        require(project.lifecycle == ProjectLifecycle.ARCHIVED) { "Only archived projects can be reopened" }
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
    fun lock() {
        val active = session
        session = null
        state = ProjectPresentationState()
        active?.let(workspace::deactivate)
    }

    @Synchronized
    fun logout() {
        lock()
    }

    @Synchronized
    fun reportRepositoryError(error: Exception): String? {
        if (error is MembershipRevokedException) {
            val revoked = session
            session = null
            state = ProjectPresentationState(error = "Project access changed. Sign in again to continue.")
            revoked?.let(workspace::deactivate)
            return revoked?.tenantId
        }
        state = state.copy(
            syncing = false,
            authenticationRequired = error is ServiceException && error.status == 401,
            error = if (
                error is IOException || error is IncompatibleServiceApiException || error is ServiceException
            ) {
                null
            } else {
                "Blocks could not save that change on this device."
            },
        )
        return null
    }

    @Synchronized
    fun reportError(message: String) {
        state = state.copy(syncing = false, error = message)
    }

    /** Keeps local work open while background cloud access waits for a fresh credential. */
    @Synchronized
    fun pauseSynchronizationForAuthentication() {
        state = state.copy(
            syncing = false,
            authenticationRequired = true,
            error = null,
        )
    }

    private fun applyResult(result: BackgroundSyncResult) {
        val active = activeSession()
        var drafts = workspace.projectDrafts(active)
        result.conflict?.let { conflict ->
            drafts = when (conflict.type) {
                ProjectMutationType.RENAME_PROJECT -> drafts.copy(
                    projectRenames = drafts.projectRenames + (requireNotNull(conflict.projectId) to conflict.localProjectName.orEmpty()),
                )
                ProjectMutationType.SAVE_NOTES -> drafts.copy(
                    projectNotes = drafts.projectNotes + (requireNotNull(conflict.projectId) to conflict.localNotes.orEmpty()),
                )
                ProjectMutationType.ADD_PARKING -> conflict.localParking?.let { parking ->
                    drafts.copy(projectParking = drafts.projectParking + (requireNotNull(conflict.projectId) to parking))
                } ?: drafts
                else -> drafts
            }
        }
        val selectedId = state.projectPage?.project?.id
        val resolvedSelectedId = selectedId?.let { id ->
            result.cache.projectIdMappings.firstOrNull { it.provisionalId == id }?.serverId ?: id
        }
        val updatedPage = resolvedSelectedId?.let { id -> result.cache.pages.firstOrNull { it.project.id == id } }
            ?: state.projectPage
        val visibleHistoryCount = maxOf(state.parkingHistory.size, PARKING_HISTORY_PAGE_SIZE)
        val history = updatedPage?.let {
            workspace.parkingHistory(active, it.project.id, 0, visibleHistoryCount)
        }
        val pending = result.pendingLegacyOperations +
            result.pendingProjectOperations +
            result.pendingBlockOperations
        val preferences = workspace.uiPreferences(active)
        state = state.copy(
            activeProjects = result.cache.activeProjects.sortedBy { it.name.lowercase() },
            archivedProjects = result.cache.archivedProjects.sortedBy { it.name.lowercase() },
            projectPage = updatedPage,
            parkingHistory = history?.records.orEmpty(),
            parkingHistoryHasMore = history?.hasMore ?: false,
            localBlocks = result.cache.localBlocks,
            projectCovers = result.cache.projectCovers,
            drafts = drafts,
            dashboardView = preferences.dashboardView,
            archiveMode = preferences.archiveMode,
            pendingChanges = pending > 0,
            invalidField = null,
            syncing = false,
            reconciliationRequired = result.reconciliationRequired,
            conflict = result.conflict,
            authenticationRequired = false,
            status = null,
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
        activeSession()
        state = state.copy(drafts = update(state.drafts))
    }

    private fun mutation(slot: String, operation: String, targetId: String, payload: String): PendingMutation {
        activeSession()
        val payloadDigest = digest(payload)
        val pending = state.drafts.pendingMutations[slot]?.takeIf {
            it.operation == operation && it.targetId == targetId && it.payloadDigest == payloadDigest
        } ?: PendingMutation(operation, targetId, payloadDigest, newOperationId())
        state = state.copy(
            drafts = state.drafts.copy(
                pendingMutations = state.drafts.pendingMutations + (slot to pending),
            ),
            invalidField = null,
            error = null,
        )
        return pending
    }

    private fun fail(field: ProjectFormField, message: String) {
        state = state.copy(invalidField = field, error = message)
    }

    private fun activeSession(): Session = checkNotNull(session) { "No authenticated project session" }

    private fun activeProjectPage(): ProjectPage = requireNotNull(state.projectPage) { "No project is open" }.also {
        check(it.project.lifecycle == ProjectLifecycle.ACTIVE) { "Archived projects are read-only" }
    }

    private fun scopedTagSession(scope: DirectTagAttemptScope): Session {
        val active = activeSession()
        check(active.tenantId == scope.tenantId) { "The NFC attempt account changed" }
        val project = requireNotNull(state.projectPage) { "The NFC attempt project is no longer open" }.project
        check(workspace.projectLink(active, project.id).key == scope.projectKey) {
            "The NFC attempt project changed"
        }
        return active
    }

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
        const val PARKING_HISTORY_PAGE_SIZE = 10
        fun renameSlot(projectId: String) = "project-rename:$projectId"
        fun notesSlot(projectId: String) = "project-notes:$projectId"
        fun parkingSlot(projectId: String) = "project-parking:$projectId"
    }
}
