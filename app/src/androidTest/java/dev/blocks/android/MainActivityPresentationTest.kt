package dev.blocks.android

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.BackgroundSyncResult
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.SessionAuthenticator
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.SessionContinuity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityPresentationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: FakeWorkspace
    private lateinit var identity: FakeIdentity
    private lateinit var continuity: MemoryContinuity

    @Before
    fun setUp() {
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        workspace = FakeWorkspace()
        identity = FakeIdentity()
        continuity = MemoryContinuity(true)
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            ProjectAppDependencies(
                controller = ProjectPresentationController(
                    SessionAuthenticator { Session(TENANT_ID, it) },
                    workspace,
                    drafts,
                    newOperationId = { "ui-operation-${operationIds.incrementAndGet()}" },
                ),
                identity = identity,
                continuity = continuity,
                observeConnectivity = false,
            )
        }
    }

    @After
    fun tearDown() {
        MainActivity.dependencyFactory = null
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
    }

    @Test
    fun authorizedRestartSilentlyOpensCachedDashboardAndReconcilesInBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { activity -> activity.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.hasButton("Sync saved changes"))
                assertFalse(activity.hasButton("Pick up this project"))
            }
            waitUntil { workspace.syncCalls > 0 }
            assertEquals(1, identity.restoreCalls)
            assertEquals(0, identity.signInCalls)
        }
    }

    @Test
    fun projectBackIsNavigationOnlyAndNeverCreatesLegacyOrProjectMutation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.BACK).performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            assertTrue(workspace.mutations.isEmpty())
            assertEquals(1, workspace.pageCalls)
        }
    }

    @Test
    fun notesAndAllThreeParkingFieldsSurviveConfigurationAndProcessStyleRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTES).setText("Draft after recreation")
                activity.findViewById<Button>(ProjectUiIds.ADD_PARKING).performClick()
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText("Shelf")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Open notebook")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).setText("Bring cable")
            }

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }

            scenario.onActivity { activity ->
                assertEquals(
                    "Draft after recreation",
                    activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString(),
                )
                assertEquals("Shelf", activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).text.toString())
                assertEquals(
                    "Open notebook",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).text.toString(),
                )
                assertEquals(
                    "Bring cable",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).text.toString(),
                )
            }
            assertTrue(identity.restoreCalls >= 2)
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { it.button("Active project").performClick() }
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            restarted.onActivity { activity ->
                assertEquals(
                    "Draft after recreation",
                    activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString(),
                )
                assertEquals("Shelf", activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).text.toString())
                assertEquals(
                    "Open notebook",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).text.toString(),
                )
                assertEquals(
                    "Bring cable",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).text.toString(),
                )
            }
        }
    }

    @Test
    fun offlineSaveRetriesAutomaticallyOnResumeAndConflictNeverDropsTheEdit() {
        workspace.offline = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTES).setText("Offline notes")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTES).performClick()
            }
            waitUntil { workspace.cache.commands.isNotEmpty() }

            workspace.offline = false
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil { workspace.cache.commands.isEmpty() }

            workspace.conflict = true
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTES).setText("Conflict notes")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTES).performClick()
            }
            waitUntil { workspace.cache.commands.any { it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION } }
            scenario.onActivity { activity ->
                assertEquals(
                    "Conflict notes",
                    activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString(),
                )
                assertTrue(activity.texts().any { it.contains("retained for reconciliation") })
            }
        }
    }

    @Test
    fun rejectedCredentialOffersOneControlledForegroundReauthenticationJourney() {
        workspace.rejectAuthentication = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { activity ->
                activity.findViewById<Button>(ProjectUiIds.REAUTHENTICATE) != null
            }
            workspace.rejectAuthentication = false
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.REAUTHENTICATE).performClick()
            }
            waitFor(scenario) { activity ->
                activity.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    activity.findViewById<Button>(ProjectUiIds.REAUTHENTICATE) == null
            }
            assertEquals(1, identity.signInCalls)
        }
    }

    private fun waitFor(
        scenario: ActivityScenario<MainActivity>,
        condition: (MainActivity) -> Boolean,
    ) = waitUntil {
        var met = false
        scenario.onActivity { met = condition(it) }
        met
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for UI state")
    }

    private fun Activity.screenTag() = findViewById<ViewGroup>(android.R.id.content)
        .findViewWithTag<LinearLayout>(ProjectUiIds.SCREEN_LOGIN)?.tag
        ?: findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<LinearLayout>(ProjectUiIds.SCREEN_DASHBOARD)?.tag
        ?: findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<LinearLayout>(ProjectUiIds.SCREEN_PROJECT)?.tag

    private fun Activity.button(text: String): Button = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<Button>()
        .single { it.text.toString() == text }

    private fun Activity.hasButton(text: String) = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<Button>()
        .any { it.text.toString() == text }

    private fun Activity.texts() = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .mapNotNull { (it as? android.widget.TextView)?.text?.toString() }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private class FakeIdentity : IdentityProvider {
        var restoreCalls = 0
        var signInCalls = 0
        override suspend fun signIn(activity: Activity): String {
            signInCalls += 1
            return "identity-token"
        }
        override suspend fun restore(activity: Activity): String {
            restoreCalls += 1
            return "restored-token"
        }
        override suspend fun clear(activity: Activity) = Unit
    }

    private data class MemoryContinuity(override var shouldRestore: Boolean) : SessionContinuity

    private class FakeWorkspace : ProjectWorkspace {
        @Volatile
        var cache = ProjectCacheState(
            activeProjects = listOf(project()),
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            pages = listOf(page()),
        )
        @Volatile
        var offline = false
        @Volatile
        var conflict = false
        @Volatile
        var rejectAuthentication = false
        @Volatile
        var syncCalls = 0
        @Volatile
        var pageCalls = 0
        val mutations = mutableListOf<String>()

        override fun activate(session: Session) = Unit
        override fun snapshot(session: Session) = result()
        override fun archivedProjects(session: Session) = cache.archivedProjects
        override fun projectPage(session: Session, projectId: String): ProjectPage {
            pageCalls += 1
            return cache.pages.single()
        }
        @Synchronized
        override fun backgroundSync(session: Session): BackgroundSyncResult {
            syncCalls += 1
            if (rejectAuthentication) {
                throw ServiceException(
                    401,
                    ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required"),
                )
            }
            if (!offline && !conflict) {
                val retired = cache.commands.map { it.operationId }
                val notes = cache.commands.lastOrNull { it.type == ProjectMutationType.SAVE_NOTES }?.notesBody
                cache = cache.copy(
                    pages = if (notes == null) cache.pages else listOf(
                        page().copy(notes = page().notes.copy(body = notes, rowVersion = 3)),
                    ),
                    commands = emptyList(),
                    retiredOperationIds = cache.retiredOperationIds + retired,
                )
            }
            return result()
        }
        override fun queueCreateProject(
            session: Session,
            name: String,
            operationId: String,
        ) = queue(ProjectMutation(operationId, ProjectMutationType.CREATE_PROJECT, operationId, projectName = name))
        override fun queueRenameProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            name: String,
            operationId: String,
        ) = queue(ProjectMutation(operationId, ProjectMutationType.RENAME_PROJECT, projectId, projectName = name))
        override fun queueArchiveProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            operationId: String,
        ) = result()
        override fun queueReopenProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            operationId: String,
        ) = result()
        override fun queueSaveNotes(
            session: Session,
            projectId: String,
            rowVersion: Int,
            body: String,
            operationId: String,
        ) = queue(
            ProjectMutation(
                operationId,
                ProjectMutationType.SAVE_NOTES,
                projectId,
                notesBody = body,
                status = if (conflict) {
                    ProjectMutationStatus.REQUIRES_RECONCILIATION
                } else {
                    ProjectMutationStatus.PENDING
                },
            ),
        )
        override fun queueParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            operationId: String,
        ) = queue(ProjectMutation(operationId, ProjectMutationType.ADD_PARKING, projectId, parking = parking))
        override fun clearLocalState(session: Session) {
            cache = ProjectCacheState()
        }

        @Synchronized
        private fun queue(command: ProjectMutation): BackgroundSyncResult {
            mutations += command.type.name
            if (cache.commands.none { it.operationId == command.operationId }) {
                cache = cache.copy(commands = cache.commands + command)
            }
            return backgroundSync(Session(TENANT_ID, "token"))
        }

        private fun result() = BackgroundSyncResult(
            cache,
            cursor = null,
            pendingLegacyOperations = 0,
            pendingProjectOperations = cache.commands.size,
            reconciliationRequired = cache.commands.any {
                it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
            },
        )
    }

    companion object {
        private val operationIds = AtomicInteger()
        private const val TENANT_ID = "00000000-0000-0000-0000-000000000011"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000012"
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
        private fun page() = ProjectPage(project(), ProjectNotes(PROJECT_ID, "Server notes", 2, NOW), null)
    }
}
