package dev.blocks.android.project

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.auth.IdentityBindingMismatchException
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPresentationControllerTest {
    @Test
    fun loginOpensCachedDashboardBeforeAnyNetworkSync() {
        val workspace = FakeWorkspace()
        val controller = controller(workspace)

        controller.login("identity-token")

        assertEquals(ProjectScreen.DASHBOARD, controller.state.screen)
        assertEquals(listOf("Active project"), controller.state.activeProjects.map { it.name })
        assertEquals(0, workspace.syncCalls)
        assertTrue(controller.isAuthenticated)
    }

    @Test
    fun identityBoundRestoreOpensCachedDashboardWithoutCallingOnlineAuthenticator() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace().apply { draftStore = drafts }
        val controller = ProjectPresentationController(
            authenticator = SessionAuthenticator { error("online session exchange must not run first") },
            workspace = workspace,
            draftStore = drafts,
        )

        controller.restoreCached(TENANT_ID)
        controller.openProject(PROJECT_ID)

        assertEquals(ProjectScreen.PROJECT, controller.state.screen)
        assertTrue(controller.state.sessionConfirmationRequired)
        assertEquals(0, workspace.pageCalls)
    }

    @Test
    fun onlineConfirmationWithADifferentTenantErasesTheBoundTenantAndFailsClosed() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace().apply { draftStore = drafts }
        val controller = ProjectPresentationController(
            authenticator = SessionAuthenticator { Session("different-tenant", it) },
            workspace = workspace,
            draftStore = drafts,
        )
        controller.restoreCached(TENANT_ID)

        val error = runCatching { controller.confirmSession("fresh-google-token") }.exceptionOrNull()

        assertTrue(error is IdentityBindingMismatchException)
        assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
        assertFalse(controller.isAuthenticated)
        assertEquals(ProjectScreen.LOGIN, controller.state.screen)
    }

    @Test
    fun projectIsFreelyBrowsableAndBackHasNoDomainMutation() {
        val workspace = FakeWorkspace()
        val controller = controller(workspace)
        controller.login("identity-token")

        controller.openProject(PROJECT_ID)
        controller.backToDashboard()

        assertEquals(ProjectScreen.DASHBOARD, controller.state.screen)
        assertEquals(1, workspace.pageCalls)
        assertTrue(workspace.mutations.isEmpty())
    }

    @Test
    fun allEditableFieldsSurviveValidationAndControllerRecreation() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace()
        val first = controller(workspace, drafts)
        first.login("identity-token")
        first.updateProjectName("  ")
        first.createProject("  ")
        assertEquals(ProjectFormField.PROJECT_NAME, first.state.invalidField)

        first.openProject(PROJECT_ID)
        first.updateRename("Renamed locally")
        first.updateNotes("Notes edited locally")
        first.showParkingForm()
        first.updateParking(ParkingDraft("Shelf", "Open notebook", "Bring cable"))

        val recreated = controller(workspace, drafts)
        recreated.login("identity-token")
        recreated.openProject(PROJECT_ID)

        assertEquals("  ", recreated.state.drafts.projectName)
        assertEquals("Renamed locally", recreated.state.drafts.projectRenames[PROJECT_ID])
        assertEquals("Notes edited locally", recreated.state.drafts.projectNotes[PROJECT_ID])
        assertEquals("Shelf", recreated.state.drafts.projectParking[PROJECT_ID]?.location)
        assertTrue(recreated.state.showParkingForm)
    }

    @Test
    fun retryableCreateReusesPersistedIdentityAcrossRestartAndClearsAfterAck() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace().apply { offline = true }
        val first = controller(workspace, drafts, ids = ArrayDeque(listOf("create-key", "unused")))
        first.login("identity-token")
        first.createProject("New project")

        val recreated = controller(workspace, drafts, ids = ArrayDeque(listOf("different-key")))
        recreated.login("identity-token")
        recreated.createProject("New project")

        assertEquals(listOf("create-key", "create-key"), workspace.operationIds)
        assertEquals("New project", recreated.state.drafts.projectName)
        workspace.offline = false
        recreated.synchronize()
        assertEquals("", recreated.state.drafts.projectName)
        assertTrue(recreated.state.drafts.pendingMutations.isEmpty())
    }

    @Test
    fun explicitCreateDraftDiscardRemovesItsQueuedOperationAndDraft() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace().apply { offline = true }
        val controller = controller(workspace, drafts, ids = ArrayDeque(listOf("create-operation")))
        controller.login("identity-token")
        controller.createProject("Discard me")

        controller.discardCreateProjectDraft()

        assertEquals("", controller.state.drafts.projectName)
        assertTrue(controller.state.drafts.pendingMutations.isEmpty())
        assertTrue(workspace.cache.commands.isEmpty())
        assertTrue("create-operation" in workspace.cache.retiredOperationIds)
    }

    @Test
    fun notesConflictKeepsTextAndShowsReconciliation() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace().apply { conflict = true }
        val controller = controller(workspace, drafts)
        controller.login("identity-token")
        controller.openProject(PROJECT_ID)
        controller.updateNotes("Keep my edit")

        controller.saveNotes("Keep my edit")

        assertTrue(controller.state.reconciliationRequired)
        assertEquals("Keep my edit", controller.state.drafts.projectNotes[PROJECT_ID])
        assertTrue(controller.state.status.orEmpty().contains("retained"))
    }

    @Test
    fun acknowledgingSaveDoesNotClearAnewerNotesEdit() {
        val drafts = MemoryDrafts()
        val workspace = FakeWorkspace()
        val controller = controller(workspace, drafts)
        controller.login("identity-token")
        controller.openProject(PROJECT_ID)
        controller.updateNotes("Submitted")
        workspace.onQueue = {
            val current = drafts.load(TENANT_ID)
            drafts.save(
                TENANT_ID,
                current.copy(projectNotes = current.projectNotes + (PROJECT_ID to "Newer edit")),
            )
        }

        controller.saveNotes("Submitted")

        assertEquals("Newer edit", controller.state.drafts.projectNotes[PROJECT_ID])
    }

    @Test
    fun parkMessageRequiresExactlyTheThreeUserFieldsAndPersistsThem() {
        val workspace = FakeWorkspace().apply { offline = true }
        val controller = controller(workspace)
        controller.login("identity-token")
        controller.openProject(PROJECT_ID)

        controller.addParking("Shelf", "", "Cable")
        assertEquals(ProjectFormField.PARKING_NEXT_ACTION, controller.state.invalidField)
        assertTrue(workspace.mutations.isEmpty())

        controller.addParking("Shelf", "Open notebook", "Cable")
        assertEquals(listOf("parking"), workspace.mutations)
        assertNull(workspace.lastSupersedesId)
        assertEquals("Shelf", controller.state.drafts.projectParking[PROJECT_ID]?.location)
        assertEquals("Open notebook", controller.state.drafts.projectParking[PROJECT_ID]?.nextAction)
        assertEquals("Cable", controller.state.drafts.projectParking[PROJECT_ID]?.mustNotForget)
    }

    @Test
    fun archiveAndReopenRemainContainedDashboardActions() {
        val workspace = FakeWorkspace()
        val controller = controller(workspace)
        controller.login("identity-token")
        controller.openProject(PROJECT_ID)

        controller.archiveProject()
        assertEquals(ProjectScreen.DASHBOARD, controller.state.screen)
        assertEquals(ProjectLifecycle.ARCHIVED, controller.state.archivedProjects.single().lifecycle)

        controller.reopenProject(controller.state.archivedProjects.single())
        assertEquals(listOf(ProjectLifecycle.ACTIVE), controller.state.activeProjects.map { it.lifecycle })
    }

    @Test
    fun logoutErasesTheTenantAndDropsTheInMemorySession() {
        val workspace = FakeWorkspace()
        val controller = controller(workspace)
        controller.login("identity-token")

        controller.logout()

        assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
        assertFalse(controller.isAuthenticated)
        assertEquals(ProjectScreen.LOGIN, controller.state.screen)
        assertNull(controller.state.projectPage)
    }

    @Test
    fun foregroundCredentialRefreshKeepsTheCurrentPageAndClearsAuthenticationHold() {
        val workspace = FakeWorkspace()
        val controller = controller(workspace)
        controller.login("old-token")
        controller.openProject(PROJECT_ID)
        controller.reportRepositoryError(
            ServiceException(401, ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required")),
        )
        assertTrue(controller.state.authenticationRequired)

        controller.login("fresh-token")

        assertEquals(ProjectScreen.PROJECT, controller.state.screen)
        assertEquals(PROJECT_ID, controller.state.projectPage?.project?.id)
        assertFalse(controller.state.authenticationRequired)
    }

    private fun controller(
        workspace: FakeWorkspace,
        drafts: MemoryDrafts = MemoryDrafts(),
        ids: ArrayDeque<String> = ArrayDeque(listOf("operation-key", "next-key", "third-key")),
    ) = ProjectPresentationController(
        authenticator = SessionAuthenticator { Session(TENANT_ID, it) },
        workspace = workspace.apply { draftStore = drafts },
        draftStore = drafts,
        newOperationId = { ids.removeFirst() },
    )

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

    private class FakeWorkspace : ProjectWorkspace {
        lateinit var draftStore: DraftStore
        var cache = ProjectCacheState(
            activeProjects = listOf(project()),
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            pages = listOf(page()),
        )
        var offline = false
        var conflict = false
        var syncCalls = 0
        var pageCalls = 0
        var onQueue: (() -> Unit)? = null
        val mutations = mutableListOf<String>()
        val operationIds = mutableListOf<String>()
        val clearedTenants = mutableListOf<String>()
        var lastSupersedesId: String? = "not-called"
        val conflictRetries = mutableListOf<Pair<String, String>>()

        override fun activate(session: Session) = Unit
        override fun snapshot(session: Session) = result()
        override fun archivedProjects(session: Session) = cache.archivedProjects
        override fun projectPage(session: Session, projectId: String): ProjectPage {
            pageCalls += 1
            return cache.pages.single { it.project.id == projectId }
        }

        override fun backgroundSync(session: Session): BackgroundSyncResult {
            syncCalls += 1
            if (!offline && !conflict) {
                cache.commands.toList().forEach(::acknowledge)
            }
            return result()
        }

        override fun queueCreateProject(
            session: Session,
            name: String,
            operationId: String,
        ) = queue("create", operationId, ProjectMutationType.CREATE_PROJECT, operationId, projectName = name)

        override fun queueRenameProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            name: String,
            operationId: String,
        ) = queue("rename", operationId, ProjectMutationType.RENAME_PROJECT, projectId, projectName = name)

        override fun queueArchiveProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            operationId: String,
        ): BackgroundSyncResult {
            mutations += "archive"
            val archived = cache.activeProjects.single { it.id == projectId }
                .copy(lifecycle = ProjectLifecycle.ARCHIVED, rowVersion = rowVersion + 1)
            cache = cache.copy(
                activeProjects = cache.activeProjects.filterNot { it.id == projectId },
                archivedProjects = cache.archivedProjects + archived,
            )
            return result()
        }

        override fun queueReopenProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            operationId: String,
        ): BackgroundSyncResult {
            mutations += "reopen"
            val active = cache.archivedProjects.single { it.id == projectId }
                .copy(lifecycle = ProjectLifecycle.ACTIVE, rowVersion = rowVersion + 1)
            cache = cache.copy(
                activeProjects = cache.activeProjects + active,
                archivedProjects = cache.archivedProjects.filterNot { it.id == projectId },
            )
            return result()
        }

        override fun queueSaveNotes(
            session: Session,
            projectId: String,
            rowVersion: Int,
            body: String,
            operationId: String,
        ) = queue("notes", operationId, ProjectMutationType.SAVE_NOTES, projectId, notesBody = body)

        override fun queueParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            operationId: String,
        ): BackgroundSyncResult {
            lastSupersedesId = supersedesId
            return queue("parking", operationId, ProjectMutationType.ADD_PARKING, projectId, parking = parking)
        }

        override fun discardConflict(session: Session, operationId: String): BackgroundSyncResult {
            val command = cache.commands.single { it.operationId == operationId }
            acknowledge(command)
            return result()
        }

        override fun retryConflict(
            session: Session,
            operationId: String,
            newOperationId: String,
        ): BackgroundSyncResult {
            cache.commands.single { it.operationId == operationId }
            conflictRetries += operationId to newOperationId
            cache = cache.copy(
                commands = cache.commands.map {
                    if (it.operationId == operationId) {
                        it.copy(
                            operationId = newOperationId,
                            idempotencyKey = it.idempotencyKey?.let { newOperationId },
                            status = ProjectMutationStatus.PENDING,
                        )
                    } else {
                        it
                    }
                },
                retiredOperationIds = cache.retiredOperationIds + operationId,
            )
            return result()
        }

        override fun discardPendingOperation(session: Session, operationId: String): BackgroundSyncResult {
            cache.commands.firstOrNull { it.operationId == operationId }?.let(::acknowledge)
            return result()
        }

        override fun clearLocalState(session: Session) {
            clearedTenants += session.tenantId
            draftStore.clear(session.tenantId)
        }

        private fun queue(
            label: String,
            operationId: String,
            type: ProjectMutationType,
            target: String,
            projectName: String? = null,
            notesBody: String? = null,
            parking: ParkingDraft? = null,
        ): BackgroundSyncResult {
            mutations += label
            operationIds += operationId
            if (cache.commands.none { it.operationId == operationId }) {
                cache = cache.copy(
                    commands = cache.commands + ProjectMutation(
                        operationId,
                        type,
                        target,
                        projectName = projectName,
                        notesBody = notesBody,
                        parking = parking,
                        status = if (conflict) {
                            ProjectMutationStatus.REQUIRES_RECONCILIATION
                        } else {
                            ProjectMutationStatus.PENDING
                        },
                    ),
                )
            }
            onQueue?.invoke()
            if (!offline && !conflict) acknowledge(cache.commands.first { it.operationId == operationId })
            return result()
        }

        private fun acknowledge(command: ProjectMutation) {
            val drafts = draftStore.load(TENANT_ID)
            val cleaned = when (command.type) {
                ProjectMutationType.CREATE_PROJECT -> drafts.copy(
                    projectName = if (drafts.projectName == command.projectName) "" else drafts.projectName,
                )
                ProjectMutationType.RENAME_PROJECT -> drafts.copy(
                    projectRenames = if (drafts.projectRenames[command.targetId] == command.projectName) {
                        drafts.projectRenames - command.targetId
                    } else {
                        drafts.projectRenames
                    },
                )
                ProjectMutationType.SAVE_NOTES -> drafts.copy(
                    projectNotes = if (drafts.projectNotes[command.targetId] == command.notesBody) {
                        drafts.projectNotes - command.targetId
                    } else {
                        drafts.projectNotes
                    },
                )
                ProjectMutationType.ADD_PARKING -> drafts.copy(
                    projectParking = if (drafts.projectParking[command.targetId] == command.parking) {
                        drafts.projectParking - command.targetId
                    } else {
                        drafts.projectParking
                    },
                )
                else -> drafts
            }
            draftStore.save(TENANT_ID, cleaned)
            cache = cache.copy(
                commands = cache.commands.filterNot { it.operationId == command.operationId },
                retiredOperationIds = cache.retiredOperationIds + command.operationId,
            )
        }

        private fun result(): BackgroundSyncResult {
            val conflicting = cache.commands.firstOrNull {
                it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
            }
            return BackgroundSyncResult(
            cache,
            cursor = null,
            pendingLegacyOperations = 0,
            pendingProjectOperations = cache.commands.size,
            reconciliationRequired = conflicting != null,
            conflict = conflicting?.let {
                ProjectConflict(
                    it.operationId,
                    it.type,
                    it.targetId,
                    localProjectName = it.projectName,
                    localNotes = it.notesBody,
                    localParking = it.parking,
                    authoritativePage = cache.pages.single(),
                )
            },
            )
        }
    }

    companion object {
        private const val TENANT_ID = "00000000-0000-0000-0000-000000000001"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000002"
        private const val NOW = "2026-09-07T10:00:00Z"

        private fun project() = ProjectSummary(
            PROJECT_ID,
            TENANT_ID,
            "Active project",
            ProjectLifecycle.ACTIVE,
            1,
            NOW,
            NOW,
        )

        private fun page() = ProjectPage(
            project(),
            ProjectNotes(PROJECT_ID, "Server notes", 2, NOW),
            null,
        )
    }
}
