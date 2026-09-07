package dev.blocks.android

import android.app.AlertDialog
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.blocks.android.api.BlocksApiClient
import dev.blocks.android.api.ProjectApiClient
import dev.blocks.android.api.ServiceBlocksRepository
import dev.blocks.android.api.ServiceProjectRepository
import dev.blocks.android.api.ServiceSessionAuthenticator
import dev.blocks.android.auth.GoogleIdentityClient
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.project.ProjectFormField
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectScreen
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidEncryptedSyncStateStore
import dev.blocks.android.state.SessionContinuity
import dev.blocks.android.state.SessionContinuityStore
import dev.blocks.android.sync.LegacyOperationReplayer
import dev.blocks.android.sync.ProjectSyncRepository
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ProjectAppDependencies(
    val controller: ProjectPresentationController,
    val identity: IdentityProvider,
    val continuity: SessionContinuity,
    val observeConnectivity: Boolean = true,
)

internal object ProjectUiIds {
    const val SCREEN_LOGIN = 1001
    const val SCREEN_DASHBOARD = 1002
    const val SCREEN_PROJECT = 1003
    const val PROJECT_NAME = 1010
    const val CREATE_PROJECT = 1011
    const val OPEN_ARCHIVE = 1012
    const val ACCOUNT = 1013
    const val BACK = 1020
    const val NOTES = 1021
    const val SAVE_NOTES = 1022
    const val ADD_PARKING = 1023
    const val PARKING_LOCATION = 1024
    const val PARKING_NEXT_ACTION = 1025
    const val PARKING_MUST_NOT_FORGET = 1026
    const val SAVE_PARKING = 1027
    const val PROJECT_ACTIONS = 1028
    const val REAUTHENTICATE = 1029
}

class MainActivity : ComponentActivity() {
    private val dependencies by lazy { dependencyFactory?.invoke(this) ?: productionDependencies() }
    private val controller get() = dependencies.controller
    private val identity get() = dependencies.identity
    private val continuity get() = dependencies.continuity
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private var syncJob: Job? = null
    private var restoreProjectId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(246, 243, 234))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (controller.state.screen == ProjectScreen.PROJECT) {
                        controller.backToDashboard()
                        render()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        render()
        if (continuity.shouldRestore) restoreSignIn()
    }

    override fun onStart() {
        super.onStart()
        if (dependencies.observeConnectivity) registerConnectivitySync()
    }

    override fun onResume() {
        super.onResume()
        if (controller.isAuthenticated) {
            synchronizeAutomatically(refreshCredential = controller.state.authenticationRequired)
        }
    }

    override fun onStop() {
        unregisterConnectivitySync()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        controller.state.projectPage?.project?.id?.let { outState.putString(STATE_PROJECT_ID, it) }
        super.onSaveInstanceState(outState)
    }

    private fun productionDependencies(): ProjectAppDependencies {
        val draftStore = AndroidEncryptedDraftStore(this)
        val syncStore = AndroidEncryptedSyncStateStore(this)
        val baseUri = URI(BuildConfig.BLOCKS_API_BASE_URL)
        val sessionClient = BlocksApiClient(baseUri, BuildConfig.BLOCKS_SERVICE_API_VERSION)
        val sessionAuthenticator = ServiceSessionAuthenticator(sessionClient::exchangeSession)
        val legacyService = ServiceBlocksRepository(BlocksApiClient(baseUri, "0.2"))
        val workspace = ProjectSyncRepository(
            ServiceProjectRepository(ProjectApiClient(baseUri, BuildConfig.BLOCKS_SERVICE_API_VERSION)),
            LegacyOperationReplayer(legacyService, draftStore, syncStore),
            draftStore,
            syncStore,
        )
        return ProjectAppDependencies(
            controller = ProjectPresentationController(sessionAuthenticator, workspace, draftStore),
            identity = GoogleIdentityClient(BuildConfig.BLOCKS_GOOGLE_SERVER_CLIENT_ID),
            continuity = SessionContinuityStore(this),
        )
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label("Blocks", 28f, Color.rgb(32, 48, 42)))
        controller.state.error?.let { content.addView(label(it, 14f, Color.rgb(150, 40, 40))) }
        controller.state.status?.let { content.addView(label(it, 14f, Color.rgb(53, 104, 89))) }
        if (controller.state.authenticationRequired) {
            content.addView(button("Reconnect Google account") { reauthenticate() }.apply {
                id = ProjectUiIds.REAUTHENTICATE
            })
        }
        when (controller.state.screen) {
            ProjectScreen.LOGIN -> renderLogin()
            ProjectScreen.DASHBOARD -> renderDashboard()
            ProjectScreen.PROJECT -> renderProject()
        }
    }

    private fun renderLogin() {
        content.tag = ProjectUiIds.SCREEN_LOGIN
        content.addView(label("Sign in to see your projects", 20f))
        content.addView(button("Sign in with Google") { signIn() })
        content.addView(label("Google proves your identity; Blocks controls project access.", 12f))
    }

    private fun renderDashboard() {
        content.tag = ProjectUiIds.SCREEN_DASHBOARD
        content.addView(label("Projects", 22f))
        if (controller.state.activeProjects.isEmpty()) {
            content.addView(label("No active projects yet.", 15f))
        }
        controller.state.activeProjects.forEach { project ->
            content.addView(button(project.name) { runRepositoryAction { controller.openProject(project.id) } })
        }

        val name = input("Project name", controller.state.drafts.projectName) {
            controller.updateProjectName(it)
        }.apply { id = ProjectUiIds.PROJECT_NAME }
        name.markInvalid(ProjectFormField.PROJECT_NAME)
        content.addView(name)
        content.addView(button("Create project") {
            runRepositoryAction { controller.createProject(name.text.toString()) }
        }.apply { id = ProjectUiIds.CREATE_PROJECT })
        content.addView(button("Archive") { showArchive() }.apply { id = ProjectUiIds.OPEN_ARCHIVE })
        content.addView(button("Account") { showAccountActions() }.apply { id = ProjectUiIds.ACCOUNT })
    }

    private fun renderProject() {
        content.tag = ProjectUiIds.SCREEN_PROJECT
        val page = requireNotNull(controller.state.projectPage)
        content.addView(button("Back to projects") {
            controller.backToDashboard()
            render()
        }.apply { id = ProjectUiIds.BACK })
        content.addView(label(page.project.name, 22f))

        page.newestParking?.let { parking ->
            content.addView(label("Latest park message", 17f, Color.rgb(53, 104, 89)))
            content.addView(label("Where I am", 12f, Color.GRAY))
            content.addView(label(parking.location, 16f))
            content.addView(label("Next physical action", 12f, Color.GRAY))
            content.addView(label(parking.nextAction, 16f))
            content.addView(label("Must not forget", 12f, Color.GRAY))
            content.addView(label(parking.mustNotForget, 16f))
        }

        content.addView(label("Notes", 17f))
        val notesValue = controller.state.drafts.projectNotes[page.project.id] ?: page.notes.body
        val notes = input("Project notes", notesValue) { controller.updateNotes(it) }.apply {
            id = ProjectUiIds.NOTES
            minLines = 4
            gravity = Gravity.TOP
        }
        notes.markInvalid(ProjectFormField.NOTES)
        content.addView(notes)
        content.addView(button("Save notes") {
            runRepositoryAction { controller.saveNotes(notes.text.toString()) }
        }.apply { id = ProjectUiIds.SAVE_NOTES })

        if (controller.state.showParkingForm) {
            renderParkingForm(page.project.id)
        } else {
            content.addView(button("Add park message") {
                controller.showParkingForm()
                render()
            }.apply { id = ProjectUiIds.ADD_PARKING })
        }
        content.addView(button("Project actions") { showProjectActions() }.apply {
            id = ProjectUiIds.PROJECT_ACTIONS
        })
    }

    private fun renderParkingForm(projectId: String) {
        content.addView(label("Add park message", 17f))
        val draft = controller.state.drafts.projectParking[projectId] ?: ParkingDraft()
        var locationValue = draft.location
        var nextActionValue = draft.nextAction
        var mustNotForgetValue = draft.mustNotForget
        fun persist() = controller.updateParking(ParkingDraft(locationValue, nextActionValue, mustNotForgetValue))

        val location = input("Where I am", locationValue) { locationValue = it; persist() }.apply {
            id = ProjectUiIds.PARKING_LOCATION
        }
        val nextAction = input("Next physical action", nextActionValue) { nextActionValue = it; persist() }.apply {
            id = ProjectUiIds.PARKING_NEXT_ACTION
        }
        val mustNotForget = input("Must not forget", mustNotForgetValue) {
            mustNotForgetValue = it
            persist()
        }.apply { id = ProjectUiIds.PARKING_MUST_NOT_FORGET }
        location.markInvalid(ProjectFormField.PARKING_LOCATION)
        nextAction.markInvalid(ProjectFormField.PARKING_NEXT_ACTION)
        mustNotForget.markInvalid(ProjectFormField.PARKING_MUST_NOT_FORGET)
        content.addView(location)
        content.addView(nextAction)
        content.addView(mustNotForget)
        content.addView(button("Save park message") {
            runRepositoryAction {
                controller.addParking(
                    location.text.toString(),
                    nextAction.text.toString(),
                    mustNotForget.text.toString(),
                )
            }
        }.apply { id = ProjectUiIds.SAVE_PARKING })
    }

    private fun showArchive() {
        runRepositoryAction(afterRender = false, complete = {
            val projects = controller.state.archivedProjects
            if (projects.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Archive").setMessage("No archived projects.")
                    .setPositiveButton("Done", null).show()
            } else {
                val names = projects.map { it.name }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Archive").setItems(names) { _, index ->
                    confirmReopen(projects[index])
                }.setNegativeButton("Done", null).show()
            }
        }, action = controller::loadArchive)
    }

    private fun confirmReopen(project: ProjectSummary) {
        AlertDialog.Builder(this).setTitle(project.name).setMessage("Return this project to the dashboard?")
            .setPositiveButton("Reopen") { _, _ -> runRepositoryAction { controller.reopenProject(project) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showProjectActions() {
        AlertDialog.Builder(this).setTitle("Project actions")
            .setItems(arrayOf("Rename", "Archive")) { _, which ->
                if (which == 0) showRename() else confirmArchive()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showRename() {
        val page = requireNotNull(controller.state.projectPage)
        val input = EditText(this).apply {
            hint = "Project name"
            setText(controller.state.drafts.projectRenames[page.project.id] ?: page.project.name)
            addTextChangedListener(watcher(controller::updateRename))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Rename project").setView(input)
            .setPositiveButton("Rename", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank() || value.length > 200) {
                    input.error = if (value.isBlank()) "Required" else "Maximum 200 characters"
                    input.requestFocus()
                    return@setOnClickListener
                }
                dialog.dismiss()
                runRepositoryAction { controller.renameProject(value) }
            }
        }
        dialog.show()
    }

    private fun confirmArchive() {
        val name = requireNotNull(controller.state.projectPage).project.name
        AlertDialog.Builder(this).setTitle("Archive $name?")
            .setMessage("You can reopen it from the dashboard Archive list.")
            .setPositiveButton("Archive") { _, _ -> runRepositoryAction { controller.archiveProject() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAccountActions() {
        AlertDialog.Builder(this).setTitle("Account")
            .setItems(arrayOf("Switch account", "Sign out")) { _, which ->
                if (which == 0) switchAccount() else signOut()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun label(text: String, size: Float, color: Int = Color.DKGRAY) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setPadding(0, 0, 0, dp(12))
    }

    private fun input(hint: String, initial: String = "", changed: (String) -> Unit = {}) = EditText(this).apply {
        this.hint = hint
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = false
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addTextChangedListener(watcher(changed))
    }

    private fun watcher(changed: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
            changed(value?.toString().orEmpty())
        }
        override fun afterTextChanged(value: Editable?) = Unit
    }

    private fun EditText.markInvalid(field: ProjectFormField) {
        if (controller.state.invalidField == field) {
            error = "Required"
            post { requestFocus() }
        }
    }

    private fun button(text: String, action: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(action)
    }

    private fun signIn() {
        uiScope.launch {
            try {
                authenticate(identity.signIn(this@MainActivity))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                controller.reportRepositoryError(error)
                render()
            }
        }
    }

    private fun restoreSignIn() {
        uiScope.launch {
            try {
                authenticate(identity.restore(this@MainActivity))
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Exception) {
                continuity.shouldRestore = false
                controller.reportError("Please sign in again to continue.")
                render()
            }
        }
    }

    private fun reauthenticate() {
        uiScope.launch {
            try {
                runCatching { identity.clear(this@MainActivity) }
                authenticate(identity.signIn(this@MainActivity))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                controller.reportRepositoryError(error)
                render()
            }
        }
    }

    private suspend fun authenticate(token: String) {
        withContext(Dispatchers.IO) {
            controller.login(token)
            restoreProjectId?.let { projectId ->
                controller.openProject(projectId)
                restoreProjectId = null
            }
        }
        continuity.shouldRestore = true
        render()
        synchronizeAutomatically()
    }

    private fun signOut() = clearAccount(switch = false)

    private fun switchAccount() = clearAccount(switch = true)

    private fun clearAccount(switch: Boolean) {
        uiScope.launch {
            continuity.shouldRestore = false
            runCatching { identity.clear(this@MainActivity) }
            val cleared = runCatching { withContext(Dispatchers.IO) { controller.logout() } }
            if (cleared.isFailure) {
                controller.reportError("Sign-out could not finish clearing local data. Try again.")
            }
            render()
            if (switch && cleared.isSuccess) signIn()
        }
    }

    private fun synchronizeAutomatically(refreshCredential: Boolean = false) {
        if (!controller.isAuthenticated || syncJob?.isActive == true) return
        syncJob = uiScope.launch {
            try {
                val refreshedToken = if (refreshCredential) identity.restore(this@MainActivity) else null
                withContext(Dispatchers.IO) {
                    refreshedToken?.let(controller::login)
                    controller.synchronize()
                }
            } catch (error: Exception) {
                val revoked = controller.reportRepositoryError(error)
                if (revoked) continuity.shouldRestore = false
            }
            render()
        }
    }

    private fun runRepositoryAction(
        afterRender: Boolean = true,
        complete: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: Exception) {
                val revoked = controller.reportRepositoryError(error)
                if (revoked) continuity.shouldRestore = false
            }
            if (afterRender) render()
            complete?.invoke()
        }
    }

    private fun registerConnectivitySync() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronizeAutomatically(refreshCredential = controller.state.authenticationRequired)
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterConnectivitySync() {
        val callback = networkCallback ?: return
        val manager = getSystemService(ConnectivityManager::class.java)
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal companion object {
        private const val STATE_PROJECT_ID = "project_id"

        @Volatile
        var dependencyFactory: ((MainActivity) -> ProjectAppDependencies)? = null
    }
}
