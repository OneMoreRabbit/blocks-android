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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.blocks.android.api.BlocksApiClient
import dev.blocks.android.api.ProjectApiClient
import dev.blocks.android.api.ServiceBlocksRepository
import dev.blocks.android.api.ServiceProjectRepository
import dev.blocks.android.api.ServiceSessionAuthenticator
import dev.blocks.android.auth.AcceptedAccountBindingException
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AcceptedTenantContinuityStore
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.auth.GoogleIdentityClient
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.ProjectFormField
import dev.blocks.android.project.DashboardView
import dev.blocks.android.project.ProjectMutationType
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
    val acceptedTenantStore: AcceptedTenantContinuityStore,
    val observeConnectivity: Boolean = true,
)

internal object ProjectUiIds {
    const val CONTENT = 1000
    const val SCREEN_LOGIN = 1001
    const val SCREEN_DASHBOARD = 1002
    const val SCREEN_PROJECT = 1003
    const val PROJECT_NAME = 1010
    const val CREATE_PROJECT = 1011
    const val BACK = 1020
    const val NOTES = 1021
    const val SAVE_NOTES = 1022
    const val ADD_PARKING = 1023
    const val PARKING_LOCATION = 1024
    const val PARKING_NEXT_ACTION = 1025
    const val PARKING_MUST_NOT_FORGET = 1026
    const val SAVE_PARKING = 1027
    const val PROJECT_ACTIONS = 1028
    const val RESOLVE_CONFLICT = 1030
    const val SETTINGS = 1031
    const val MAIN = 1032
    const val PARK = 1033
    const val PROJECT_SETTINGS = 1034
    const val PENDING_CHANGES = 1035
    const val ARCHIVE_MAIN = 1036
    const val PROJECT_LIST = 1037
    const val PROJECT_TILES = 1038
    const val PARK_SHEET = 1039
}

class MainActivity : ComponentActivity() {
    private val dependencies by lazy { dependencyFactory?.invoke(this) ?: productionDependencies() }
    private val controller get() = dependencies.controller
    private val identity get() = dependencies.identity
    private val continuity get() = dependencies.continuity
    private val acceptedTenantStore get() = dependencies.acceptedTenantStore
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private var syncJob: Job? = null
    private var restoreProjectId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var activeDialog: AlertDialog? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        restoreProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
        content = LinearLayout(this).apply {
            id = ProjectUiIds.CONTENT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(246, 243, 234))
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(24), systemBars.top + dp(24), dp(24), systemBars.bottom + dp(24))
            insets
        }
        setContentView(ScrollView(this).apply { addView(content) })
        ViewCompat.requestApplyInsets(content)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (controller.state.screen == ProjectScreen.PROJECT && controller.state.showParkingForm) {
                        controller.hideParkingForm()
                        render()
                    } else if (controller.state.screen == ProjectScreen.PROJECT) {
                        runRepositoryAction { controller.backToDashboard() }
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
            synchronizeAutomatically(
                refreshCredential = controller.state.authenticationRequired ||
                    controller.state.sessionConfirmationRequired,
            )
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
            controller = ProjectPresentationController(sessionAuthenticator, workspace),
            identity = GoogleIdentityClient(BuildConfig.BLOCKS_GOOGLE_SERVER_CLIENT_ID),
            continuity = SessionContinuityStore(this),
            acceptedTenantStore = AndroidEncryptedAcceptedTenantContinuityStore(this),
        )
    }

    private fun render() {
        content.removeAllViews()
        if (controller.state.screen == ProjectScreen.PROJECT && controller.state.showParkingForm) {
            renderParkingSheet()
            return
        }
        renderTopBar()
        controller.state.error?.let { content.addView(label(it, 14f, Color.rgb(150, 40, 40))) }
        controller.state.conflict?.let {
            content.addView(button("Resolve saved change") { showConflictResolution() }.apply {
                id = ProjectUiIds.RESOLVE_CONFLICT
            })
        }
        when (controller.state.screen) {
            ProjectScreen.LOGIN -> renderLogin()
            ProjectScreen.DASHBOARD -> renderDashboard()
            ProjectScreen.PROJECT -> renderProject()
        }
    }

    private fun renderTopBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            label("Blocks", 28f, Color.rgb(32, 48, 42)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (controller.state.pendingChanges) {
            bar.addView(label("●", 16f, Color.rgb(96, 104, 100)).apply {
                id = ProjectUiIds.PENDING_CHANGES
                contentDescription = "Changes pending"
            })
        }
        if (controller.state.screen == ProjectScreen.DASHBOARD) {
            bar.addView(button("Settings") { showSettings() }.apply { id = ProjectUiIds.SETTINGS })
        } else if (controller.state.screen == ProjectScreen.PROJECT) {
            bar.addView(button("Main") {
                runRepositoryAction { controller.backToDashboard() }
            }.apply { id = ProjectUiIds.MAIN })
            bar.addView(button("Park") {
                controller.showParkingForm()
                render()
            }.apply { id = ProjectUiIds.PARK })
            bar.addView(button("Settings") { showProjectSettings() }.apply {
                id = ProjectUiIds.PROJECT_SETTINGS
            })
        }
        content.addView(bar)
    }

    private fun renderLogin() {
        content.tag = ProjectUiIds.SCREEN_LOGIN
        content.addView(label("Sign in to see your projects", 20f))
        content.addView(button("Sign in with Google") { signIn() })
        content.addView(label("Google proves your identity; Blocks controls project access.", 12f))
    }

    private fun renderDashboard() {
        content.tag = ProjectUiIds.SCREEN_DASHBOARD
        val archiveMode = controller.state.archiveMode
        val projects = if (archiveMode) controller.state.archivedProjects else controller.state.activeProjects
        content.addView(label(if (archiveMode) "Archive" else "Projects", 22f))
        if (projects.isEmpty()) {
            content.addView(label(if (archiveMode) "No archived projects." else "No active projects yet.", 15f))
        }
        val projectContainer: ViewGroup = if (controller.state.dashboardView == DashboardView.TILES) {
            GridLayout(this).apply {
                id = ProjectUiIds.PROJECT_TILES
                columnCount = 2
            }
        } else {
            LinearLayout(this).apply {
                id = ProjectUiIds.PROJECT_LIST
                orientation = LinearLayout.VERTICAL
            }
        }
        projects.forEach { project ->
            projectContainer.addView(
                button(project.name) {
                    if (archiveMode) confirmReopen(project) else runRepositoryAction {
                        controller.openProject(project.id)
                    }
                },
            )
        }
        content.addView(projectContainer)

        if (archiveMode) {
            content.addView(button("Main") {
                runRepositoryAction { controller.showMainDashboard() }
            }.apply { id = ProjectUiIds.ARCHIVE_MAIN })
        } else {
            content.addView(button("Create project") { showCreateProject() }.apply {
                id = ProjectUiIds.CREATE_PROJECT
            })
        }
    }

    private fun showSettings() {
        val viewChoice = if (controller.state.dashboardView == DashboardView.LIST) "Tile view" else "List view"
        val items = buildList {
            add("Archive")
            add(viewChoice)
            add("About")
            if (controller.state.authenticationRequired) add("Reconnect Google account")
            add("Switch account")
            add("Sign out")
            add("Remove local data")
        }
        val dialog = AlertDialog.Builder(this).setTitle("Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Archive" -> openArchiveMode()
                    viewChoice -> runRepositoryAction { controller.toggleDashboardView() }
                    "About" -> showAbout()
                    "Reconnect Google account" -> reauthenticate()
                    "Switch account" -> switchAccount()
                    "Sign out" -> signOut()
                    "Remove local data" -> confirmRemoveLocalData()
                }
            }
            .setNegativeButton("Done", null)
            .create()
        showManagedDialog(dialog)
    }

    private fun showAbout() {
        val message = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Build identity: ${BuildConfig.APPLICATION_ID}/${BuildConfig.BUILD_TYPE}"
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Blocks Android")
                .setMessage(message)
                .setPositiveButton("Done", null)
                .create(),
        )
    }

    private fun confirmRemoveLocalData() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Remove local data?")
            .setMessage(
                "This permanently removes this account's projects, notes, park messages, " +
                    "drafts, and pending changes from this device.",
            )
            .setPositiveButton("Remove local data") { _, _ -> removeActiveLocalData() }
            .setNegativeButton("Cancel", null)
            .create()
        showManagedDialog(dialog)
    }

    private fun renderProject() {
        content.tag = ProjectUiIds.SCREEN_PROJECT
        val page = requireNotNull(controller.state.projectPage)
        content.addView(label(page.project.name, 22f))

        val newestParking = page.newestParking
        if (newestParking == null) {
            content.addView(label("No park message yet.", 16f, Color.rgb(53, 104, 89)))
        } else {
            content.addView(label("Latest park message", 17f, Color.rgb(53, 104, 89)))
            content.addView(label("Where I am", 12f, Color.GRAY))
            content.addView(label(newestParking.location, 16f))
            content.addView(label("Next physical action", 12f, Color.GRAY))
            content.addView(label(newestParking.nextAction, 16f))
            content.addView(label("Must not forget", 12f, Color.GRAY))
            content.addView(label(newestParking.mustNotForget, 16f))
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
            runRepositoryAction(syncAfter = true) { controller.saveNotes(notes.text.toString()) }
        }.apply { id = ProjectUiIds.SAVE_NOTES })

    }

    private fun renderParkingSheet() {
        content.tag = ProjectUiIds.SCREEN_PROJECT
        val projectId = requireNotNull(controller.state.projectPage).project.id
        val heading = LinearLayout(this).apply {
            id = ProjectUiIds.PARK_SHEET
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(
            label("Park", 24f, Color.rgb(32, 48, 42)),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        heading.addView(button("Back") {
            controller.hideParkingForm()
            render()
        }.apply { id = ProjectUiIds.BACK })
        content.addView(heading)
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
            runRepositoryAction(syncAfter = true) {
                controller.addParking(
                    location.text.toString(),
                    nextAction.text.toString(),
                    mustNotForget.text.toString(),
                )
            }
        }.apply { id = ProjectUiIds.SAVE_PARKING })
    }

    private fun openArchiveMode() {
        runRepositoryAction { controller.showArchive() }
    }

    private fun showCreateProject() {
        val input = EditText(this).apply {
            id = ProjectUiIds.PROJECT_NAME
            hint = "Project name"
            setText(controller.state.drafts.projectName)
            addTextChangedListener(watcher(controller::updateProjectName))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Create project")
            .setView(input)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Discard draft", null)
            .create()
        showManagedDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString()
            if (value.isBlank() || value.length > 200) {
                input.error = if (value.isBlank()) "Required" else "Maximum 200 characters"
                input.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            runRepositoryAction(syncAfter = true) { controller.createProject(value) }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            runRepositoryAction { controller.discardCreateProjectDraft() }
        }
    }

    private fun confirmReopen(project: ProjectSummary) {
        val dialog = AlertDialog.Builder(this).setTitle(project.name).setMessage("Return this project to the dashboard?")
            .setPositiveButton("Reopen") { _, _ ->
                runRepositoryAction(syncAfter = true) { controller.reopenProject(project) }
            }
            .setNegativeButton("Cancel", null).create()
        showManagedDialog(dialog)
    }

    private fun showProjectSettings() {
        val dialog = AlertDialog.Builder(this).setTitle("Project settings")
            .setItems(arrayOf("Rename", "Archive")) { _, which ->
                if (which == 0) showRename() else confirmArchive()
            }.setNegativeButton("Cancel", null).create()
        showManagedDialog(dialog)
    }

    private fun showConflictResolution() {
        val conflict = controller.state.conflict ?: return
        val page = conflict.authoritativePage
        val local = when (conflict.type) {
            ProjectMutationType.RENAME_PROJECT -> conflict.localProjectName.orEmpty()
            ProjectMutationType.SAVE_NOTES -> conflict.localNotes.orEmpty()
            ProjectMutationType.ADD_PARKING -> conflict.localParking?.let {
                "${it.location} / ${it.nextAction} / ${it.mustNotForget}"
            }.orEmpty()
            ProjectMutationType.ARCHIVE_PROJECT -> "Archive project"
            ProjectMutationType.REOPEN_PROJECT -> "Reopen project"
            else -> "Saved change"
        }
        val authoritative = when (conflict.type) {
            ProjectMutationType.SAVE_NOTES -> {
                page?.let { "Notes version ${it.notes.rowVersion}: ${it.notes.body}" }
            }
            else -> page?.let {
                "Project version ${it.project.rowVersion}: ${it.project.name} (${it.project.lifecycle.wireValue})"
            }
        } ?: "The authoritative value could not be loaded."
        val builder = AlertDialog.Builder(this)
            .setTitle("Resolve saved change")
            .setMessage("Your saved attempt:\n$local\n\nCurrent service value:\n$authoritative")
            .setNegativeButton("Discard saved change") { _, _ ->
                runRepositoryAction(syncAfter = true) { controller.discardConflict() }
            }
            .setNeutralButton("Cancel", null)
        if (conflict.canRetry) {
            builder.setPositiveButton("Retry with latest") { _, _ ->
                runRepositoryAction(syncAfter = true) { controller.retryConflict() }
            }
        }
        showManagedDialog(builder.create())
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
        showManagedDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString()
            if (value.isBlank() || value.length > 200) {
                input.error = if (value.isBlank()) "Required" else "Maximum 200 characters"
                input.requestFocus()
                return@setOnClickListener
            }
            dialog.dismiss()
            runRepositoryAction(syncAfter = true) { controller.renameProject(value) }
        }
    }

    private fun confirmArchive() {
        val name = requireNotNull(controller.state.projectPage).project.name
        val dialog = AlertDialog.Builder(this).setTitle("Archive $name?")
            .setMessage("You can reopen it from the dashboard Archive list.")
            .setPositiveButton("Archive") { _, _ ->
                runRepositoryAction(syncAfter = true) { controller.archiveProject() }
            }
            .setNegativeButton("Cancel", null).create()
        showManagedDialog(dialog)
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
                handleRepositoryError(error)
                render()
            }
        }
    }

    private fun restoreSignIn() {
        uiScope.launch {
            try {
                val acceptedTenant = withContext(Dispatchers.IO) { acceptedTenantStore.load() }
                if (acceptedTenant == null) {
                    continuity.shouldRestore = false
                    controller.lock()
                    render()
                    return@launch
                } else {
                    withContext(Dispatchers.IO) {
                        controller.restoreCached(acceptedTenant.tenantId)
                        restoreProjectId?.let { projectId ->
                            controller.openProject(projectId)
                            restoreProjectId = null
                        }
                    }
                    render()
                    reconcileAcceptedTenant(acceptedTenant)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                if (controller.isAuthenticated) {
                    handleRepositoryError(error)
                } else {
                    continuity.shouldRestore = false
                    controller.reportError("Blocks could not reopen local data safely. Sign in again to continue.")
                }
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
                handleRepositoryError(error)
                render()
            }
        }
    }

    private suspend fun authenticate(token: String) {
        try {
            acceptServiceValidatedToken(token)
            render()
            synchronizeAutomatically()
        } catch (error: AcceptedAccountBindingException) {
            failClosedAccountBinding()
        }
    }

    private suspend fun reconcileAcceptedTenant(acceptedTenant: AcceptedTenantContinuity) {
        val token = try {
            identity.restore(this@MainActivity)
        } catch (_: CancellationException) {
            return
        } catch (_: Exception) {
            controller.pauseSynchronizationForAuthentication()
            render()
            return
        }
        try {
            val priorTenantId = acceptedTenant.tenantId
            withContext(Dispatchers.IO) {
                val session = acceptServiceValidatedToken(token)
                if (session.tenantId != priorTenantId) restoreProjectId = null
                controller.synchronize()
            }
        } catch (_: CancellationException) {
            return
        } catch (error: AcceptedAccountBindingException) {
            failClosedAccountBinding()
            return
        } catch (error: Exception) {
            handleRepositoryError(error)
        }
        render()
    }

    private suspend fun acceptServiceValidatedToken(token: String): Session {
        val priorTenantId = controller.activeTenantId
        val authenticated = withContext(Dispatchers.IO) { controller.authenticate(token) }
        val accountIdentity = requireNotNull(authenticated.accountIdentity) {
            "Service accepted a session without a stable account identity"
        }
        withContext(Dispatchers.IO) {
            acceptedTenantStore.save(AcceptedTenantContinuity(authenticated.tenantId, accountIdentity))
            controller.activateAuthenticated(authenticated)
            if (priorTenantId == authenticated.tenantId) {
                restoreProjectId?.let(controller::openProject)
            }
            restoreProjectId = null
        }
        continuity.shouldRestore = true
        return authenticated
    }

    private suspend fun failClosedAccountBinding() {
        continuity.shouldRestore = false
        syncJob?.cancel()
        syncJob = null
        restoreProjectId = null
        val locked = runCatching {
            withContext(Dispatchers.IO) {
                controller.lock()
                acceptedTenantStore.lock()
            }
        }
        controller.reportError(
            if (locked.isSuccess) {
                "This account could not be matched to retained Blocks data. Sign in again."
            } else {
                "Blocks could not safely lock the previous account. Sign-in remains blocked."
            },
        )
        render()
    }

    private fun signOut() = clearAccount(switch = false)

    private fun switchAccount() = clearAccount(switch = true)

    private fun clearAccount(switch: Boolean) {
        uiScope.launch {
            syncJob?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            val locked = runCatching {
                withContext(Dispatchers.IO) {
                    controller.lock()
                    acceptedTenantStore.lock()
                }
            }
            val credentialCleared = runCatching { identity.clear(this@MainActivity) }
            if (locked.isFailure || credentialCleared.isFailure) {
                controller.reportError("Sign-out could not finish safely. Local data remains locked.")
            }
            render()
            if (switch && locked.isSuccess && credentialCleared.isSuccess) {
                try {
                    authenticate(identity.signIn(this@MainActivity))
                } catch (_: CancellationException) {
                    return@launch
                } catch (error: Exception) {
                    handleRepositoryError(error)
                    render()
                }
            }
        }
    }

    private fun removeActiveLocalData() {
        uiScope.launch {
            val tenantId = controller.activeTenantId ?: withContext(Dispatchers.IO) {
                acceptedTenantStore.load()?.tenantId
            } ?: return@launch
            syncJob?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            val removed = runCatching {
                withContext(Dispatchers.IO) {
                    controller.eraseAcceptedTenantCache(tenantId)
                    acceptedTenantStore.remove(tenantId)
                }
            }
            runCatching { identity.clear(this@MainActivity) }
            if (removed.isFailure) {
                controller.reportError("Blocks could not safely remove all local data. Sign-in remains locked.")
            }
            render()
        }
    }

    private fun synchronizeAutomatically(refreshCredential: Boolean = false) {
        if (!controller.isAuthenticated || syncJob?.isActive == true) return
        syncJob = uiScope.launch {
            val refreshedToken = if (refreshCredential) {
                try {
                    identity.restore(this@MainActivity)
                } catch (_: CancellationException) {
                    return@launch
                } catch (_: Exception) {
                    controller.pauseSynchronizationForAuthentication()
                    render()
                    return@launch
                }
            } else {
                null
            }
            try {
                withContext(Dispatchers.IO) {
                    refreshedToken?.let { acceptServiceValidatedToken(it) }
                    controller.synchronize()
                }
            } catch (error: AcceptedAccountBindingException) {
                failClosedAccountBinding()
            } catch (error: Exception) {
                handleRepositoryError(error)
            }
            render()
        }
    }

    private fun runRepositoryAction(
        afterRender: Boolean = true,
        syncAfter: Boolean = false,
        complete: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: Exception) {
                handleRepositoryError(error)
            }
            if (afterRender) render()
            complete?.invoke()
            if (syncAfter) synchronizeAutomatically()
        }
    }

    private fun registerConnectivitySync() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronizeAutomatically(
                    refreshCredential = controller.state.authenticationRequired ||
                        controller.state.sessionConfirmationRequired,
                )
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

    private fun showManagedDialog(dialog: AlertDialog) {
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private suspend fun handleRepositoryError(error: Exception) {
        val revokedTenantId = controller.reportRepositoryError(error)
        if (revokedTenantId != null) {
            syncJob?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            runCatching { withContext(Dispatchers.IO) { acceptedTenantStore.remove(revokedTenantId) } }
            runCatching { identity.clear(this@MainActivity) }
        }
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
