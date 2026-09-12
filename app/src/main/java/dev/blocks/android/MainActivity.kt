package dev.blocks.android

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NfcAdapter
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
import android.widget.ImageView
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
import dev.blocks.android.tag.AndroidNfcTagPlatform
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.NdefLocatorCodec
import dev.blocks.android.tag.NfcProgrammingResult
import dev.blocks.android.tag.NfcTagHandle
import dev.blocks.android.tag.NfcTagPlatform
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagLocator
import dev.blocks.android.tag.TagProgrammingFailure
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

internal data class ProjectAppDependencies(
    val controller: ProjectPresentationController,
    val identity: IdentityProvider,
    val continuity: SessionContinuity,
    val acceptedTenantStore: AcceptedTenantContinuityStore,
    val observeConnectivity: Boolean = true,
    val nfcTagPlatform: NfcTagPlatform? = null,
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
    const val ROOT_SCROLL = 1040
    const val DASHBOARD_BODY = 1041
    const val PARKING_HISTORY = 1042
    const val LOAD_OLDER_PARKING = 1043
    const val TAGS = 1050
    const val SET_TAG = 1051
    const val SCAN_QR = 1052
    const val TAG_QR = 1053
}

class MainActivity : ComponentActivity() {
    private val dependencies by lazy { dependencyFactory?.invoke(this) ?: productionDependencies() }
    private val controller get() = dependencies.controller
    private val identity get() = dependencies.identity
    private val continuity get() = dependencies.continuity
    private val acceptedTenantStore get() = dependencies.acceptedTenantStore
    private val nfcTagPlatform by lazy { dependencies.nfcTagPlatform ?: AndroidNfcTagPlatform(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localStateDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "blocks-local-state")
    }.asCoroutineDispatcher()
    private lateinit var content: LinearLayout
    private lateinit var rootScroll: ScrollView
    private var syncJob: Job? = null
    private var credentialJob: Job? = null
    private var restoreProjectId: String? = null
    private var pendingTagLocator: TagLocator? = null
    private var activeProgrammingBlockId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var activeDialog: AlertDialog? = null
        private set
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::handleQrText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTagLocator = consumeNdefIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        restoreProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
        content = LinearLayout(this).apply {
            id = ProjectUiIds.CONTENT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(246, 243, 234))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(24), systemBars.top + dp(24), dp(24), systemBars.bottom + dp(24))
            insets
        }
        rootScroll = ScrollView(this).apply {
            id = ProjectUiIds.ROOT_SCROLL
            isFillViewport = true
            setBackgroundColor(Color.rgb(246, 243, 234))
            addView(content)
        }
        setContentView(rootScroll)
        ViewCompat.requestApplyInsets(content)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (controller.state.screen == ProjectScreen.PROJECT && controller.state.showParkingForm) {
                        controller.hideParkingForm()
                        render()
                    } else if (controller.state.screen == ProjectScreen.PROJECT) {
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
        activeProgrammingBlockId?.let { startNfcReader(it) }
        if (controller.canSynchronize) synchronizeAutomatically()
    }

    override fun onPause() {
        nfcTagPlatform.stopReader()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNdefIntent(intent)?.let {
            pendingTagLocator = it
            resolvePendingTagLocator()
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
            nfcTagPlatform = AndroidNfcTagPlatform(this),
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
                controller.backToDashboard()
                render()
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
        val body = LinearLayout(this).apply {
            id = ProjectUiIds.DASHBOARD_BODY
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        body.addView(label(if (archiveMode) "Archive" else "Projects", 22f))
        if (projects.isEmpty()) {
            body.addView(label(if (archiveMode) "No archived projects." else "No active projects yet.", 15f))
        }
        val projectContainer: ViewGroup = if (controller.state.dashboardView == DashboardView.TILES) {
            GridLayout(this).apply {
                id = ProjectUiIds.PROJECT_TILES
                columnCount = dashboardColumnCount()
                alignmentMode = GridLayout.ALIGN_BOUNDS
                useDefaultMargins = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        } else {
            LinearLayout(this).apply {
                id = ProjectUiIds.PROJECT_LIST
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        projects.forEach { project ->
            val projectButton = button(project.name) { source ->
                if (archiveMode) confirmReopen(project) else runRepositoryAction(source = source) {
                    controller.openProject(project.id)
                }
            }
            if (projectContainer is GridLayout) {
                projectContainer.addView(
                    projectButton,
                    GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    },
                )
            } else {
                projectContainer.addView(
                    projectButton,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        body.addView(projectContainer)

        if (archiveMode) {
            body.addView(button("Main") { source ->
                runRepositoryAction(source = source) { controller.showMainDashboard() }
            }.apply { id = ProjectUiIds.ARCHIVE_MAIN })
        } else {
            body.addView(button("Create project") { showCreateProject() }.apply {
                id = ProjectUiIds.CREATE_PROJECT
            })
        }
        content.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        rootScroll.post {
            val availableBodyHeight = rootScroll.height - body.top - content.paddingBottom
            if (availableBodyHeight > 0 && body.minimumHeight != availableBodyHeight) {
                body.minimumHeight = availableBodyHeight
            }
        }
    }

    private fun showSettings() {
        val viewChoice = if (controller.state.dashboardView == DashboardView.LIST) "Tile view" else "List view"
        val items = buildList {
            add("Archive")
            add("Scan QR")
            add(viewChoice)
            add("About")
            if (controller.state.authenticationRequired || controller.state.sessionConfirmationRequired) {
                add("Reconnect Google account")
            }
            add("Switch account")
            add("Sign out")
            add("Remove local data")
        }
        val dialog = AlertDialog.Builder(this).setTitle("Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Archive" -> openArchiveMode()
                    "Scan QR" -> launchQrScanner()
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
        val notes = input("Project notes", notesValue) { value ->
            queueDraftUpdate { controller.updateNotes(value) }
        }.apply {
            id = ProjectUiIds.NOTES
            minLines = 4
            gravity = Gravity.TOP
        }
        notes.markInvalid(ProjectFormField.NOTES)
        content.addView(notes)
        content.addView(button("Save notes") { source ->
            runRepositoryAction(source = source, syncAfter = true) {
                controller.saveNotes(notes.text.toString())
            }
        }.apply { id = ProjectUiIds.SAVE_NOTES })

        val olderParking = controller.state.parkingHistory.filterNot { it.id == newestParking?.id }
        if (olderParking.isNotEmpty() || controller.state.parkingHistoryHasMore) {
            content.addView(label("Parking history", 17f, Color.rgb(53, 104, 89)))
            val history = LinearLayout(this).apply {
                id = ProjectUiIds.PARKING_HISTORY
                orientation = LinearLayout.VERTICAL
            }
            olderParking.forEach { record ->
                history.addView(label(record.occurredAt, 12f, Color.GRAY))
                history.addView(label("Where I was", 12f, Color.GRAY))
                history.addView(label(record.location, 15f))
                history.addView(label("Next physical action", 12f, Color.GRAY))
                history.addView(label(record.nextAction, 15f))
                history.addView(label("Must not forget", 12f, Color.GRAY))
                history.addView(label(record.mustNotForget, 15f))
            }
            content.addView(history)
            if (controller.state.parkingHistoryHasMore) {
                content.addView(button("Show older park messages") { source ->
                    runRepositoryAction(source = source) { controller.loadOlderParking() }
                }.apply { id = ProjectUiIds.LOAD_OLDER_PARKING })
            }
        }

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
        fun persist() {
            val value = ParkingDraft(locationValue, nextActionValue, mustNotForgetValue)
            queueDraftUpdate { controller.updateParking(value) }
        }

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
        content.addView(button("Save park message") { source ->
            runRepositoryAction(source = source, syncAfter = true) {
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
            addTextChangedListener(watcher { value ->
                queueDraftUpdate { controller.updateProjectName(value) }
            })
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
        val items = arrayOf("Set tag", "Manage tags", "Scan QR", "Rename", "Archive")
        val dialog = AlertDialog.Builder(this).setTitle("Project settings")
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Set tag" -> showSetTag()
                    "Manage tags" -> showTagManagement()
                    "Scan QR" -> launchQrScanner()
                    "Rename" -> showRename()
                    "Archive" -> confirmArchive()
                }
            }.setNegativeButton("Cancel", null).create()
        showManagedDialog(dialog)
    }

    private fun showSetTag() {
        val projectId = requireNotNull(controller.state.projectPage).project.id
        controller.state.localBlocks.firstOrNull {
            it.intendedProjectId == projectId &&
                it.state in setOf(LocalBlockState.AWAITING_WRITE, LocalBlockState.WRITE_UNCERTAIN)
        }?.let { pending ->
            showManagedDialog(
                AlertDialog.Builder(this)
                    .setTitle("Resume unfinished tag")
                    .setMessage(
                        "Finish or resume ${pending.label} with its saved locator before creating another tag.",
                    )
                    .setPositiveButton("Resume") { _, _ -> showTagProgramming(pending) }
                    .setNegativeButton("Cancel", null)
                    .create(),
            )
            return
        }
        val input = EditText(this).apply {
            hint = "Block label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Set tag")
            .setMessage("Name this physical block, then tap a writable NFC tag or use the QR fallback.")
            .setView(input)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create()
        showManagedDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { source ->
            val label = input.text.toString().trim()
            if (label.isBlank() || label.length > 200) {
                input.error = if (label.isBlank()) "Required" else "Maximum 200 characters"
                input.requestFocus()
                return@setOnClickListener
            }
            source.isEnabled = false
            dialog.dismiss()
            var created: LocalBlockRecord? = null
            runRepositoryAction(
                syncAfter = false,
                complete = { created?.let(::showTagProgramming) },
            ) {
                created = controller.beginTagProgramming(label)
            }
        }
    }

    private fun showTagManagement() {
        val projectId = requireNotNull(controller.state.projectPage).project.id
        val blocks = controller.state.localBlocks.filter {
            it.boundProjectId == projectId || it.intendedProjectId == projectId
        }
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Set tag" to ::showSetTag)
            add("Scan QR" to ::launchQrScanner)
            blocks.forEach { block ->
                if (block.state in setOf(LocalBlockState.AWAITING_WRITE, LocalBlockState.WRITE_UNCERTAIN)) {
                    add("Resume NFC — ${block.label}" to { showTagProgramming(block) })
                    add("Use QR — ${block.label}" to { completeWithQr(block) })
                } else {
                    add("Show QR — ${block.label}" to { showQr(block) })
                    if (block.boundProjectId == projectId) {
                        add("Unbind — ${block.label}" to { confirmUnbind(block) })
                    }
                }
            }
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Tags")
                .setMessage(if (blocks.isEmpty()) "No tags are associated with this project yet." else null)
                .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                .setNegativeButton("Done", null)
                .create(),
        )
    }

    private fun showTagProgramming(block: LocalBlockRecord) {
        activeProgrammingBlockId = block.clientBlockId
        val message = when {
            !nfcTagPlatform.isAvailable -> "This device has no NFC hardware. Use the QR fallback."
            !nfcTagPlatform.isEnabled -> "NFC is turned off. Turn it on or use the QR fallback."
            block.state == LocalBlockState.WRITE_UNCERTAIN -> {
                "The previous write was not confirmed. Tap the same tag to verify and resume safely."
            }
            else -> "Hold a writable NDEF tag to this device. Blocks verifies the exact record before assigning it."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Set tag — ${block.label}")
            .setMessage(message)
            .setPositiveButton("Use QR instead", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            nfcTagPlatform.stopReader()
            if (activeProgrammingBlockId == block.clientBlockId) activeProgrammingBlockId = null
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            completeWithQr(block)
        }
        startNfcReader(block.clientBlockId)
    }

    private fun startNfcReader(clientBlockId: String) {
        if (activeProgrammingBlockId != clientBlockId || !nfcTagPlatform.isEnabled) return
        nfcTagPlatform.startReader { handle -> handleProgrammingTag(clientBlockId, handle) }
    }

    private fun handleProgrammingTag(clientBlockId: String, handle: NfcTagHandle) {
        nfcTagPlatform.stopReader()
        uiScope.launch {
            val block = try {
                withContext(NonCancellable + localStateDispatcher) {
                    controller.markTagWriteAttempt(clientBlockId)
                }
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
                return@launch
            }
            val locator = TagLocator.create(block.token)
            val outcome = withContext(Dispatchers.IO) {
                nfcTagPlatform.programAndVerify(handle, locator, block.replacementTokenFingerprint)
            }
            when (outcome) {
                NfcProgrammingResult.Verified -> {
                    withContext(NonCancellable + localStateDispatcher) {
                        controller.completeTagProgramming(clientBlockId, block.token, TagActivationMedium.NFC)
                    }
                    activeDialog?.dismiss()
                    render()
                    showMessage("Tag ready", "The verified tag is assigned to this project.")
                }
                is NfcProgrammingResult.ExistingBlocksTag -> {
                    withContext(NonCancellable + localStateDispatcher) {
                        controller.recordTagProgrammingFailure(
                            clientBlockId,
                            TagProgrammingFailure.FOREIGN_CONTENT,
                        )
                    }
                    activeDialog?.dismiss()
                    handleExistingBlocksTag(block, outcome.locator)
                }
                else -> {
                    val failure = outcome.toProgrammingFailure()
                    withContext(NonCancellable + localStateDispatcher) {
                        controller.recordTagProgrammingFailure(clientBlockId, failure)
                    }
                    activeDialog?.dismiss()
                    render()
                    showMessage("Tag not changed", failure.userMessage())
                }
            }
        }
    }

    private fun handleExistingBlocksTag(pending: LocalBlockRecord, locator: TagLocator) {
        uiScope.launch {
            val local = withContext(localStateDispatcher) { controller.resolveTagLocally(locator.token) }
            val resolution = if (local is LocalTagResolution.Unknown && controller.canSynchronize) {
                runCatching { withContext(Dispatchers.IO) { controller.resolveTagThroughService(locator.token) } }
                    .getOrElse { local }
            } else {
                local
            }
            when (resolution) {
                is LocalTagResolution.Unknown -> confirmReplaceUnknownTag(pending, locator.token)
                is LocalTagResolution.Bound -> confirmUseKnownTag(pending, resolution.block, resolution.projectId)
                is LocalTagResolution.Unbound -> confirmUseKnownTag(pending, resolution.block, null)
                is LocalTagResolution.PendingProgramming -> showMessage(
                    "Tag not changed",
                    "This is another pending Blocks tag. Resume that saved setup instead.",
                )
            }
        }
    }

    private fun confirmReplaceUnknownTag(pending: LocalBlockRecord, existingToken: OpaqueTagToken) {
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Replace unknown Blocks tag?")
                .setMessage(
                    "This tag is not available in the current account's saved data. Replacement is destructive " +
                        "and requires another tap of this same tag.",
                )
                .setPositiveButton("Replace on next tap") { _, _ ->
                    var updated: LocalBlockRecord? = null
                    runRepositoryAction(complete = { updated?.let(::showTagProgramming) }) {
                        updated = controller.authorizeTagReplacement(pending.clientBlockId, existingToken)
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun confirmUseKnownTag(
        pending: LocalBlockRecord,
        existing: LocalBlockRecord,
        previousProjectId: String?,
    ) {
        val targetProjectId = requireNotNull(controller.state.projectPage).project.id
        val sameProject = previousProjectId == targetProjectId
        val title = when {
            sameProject -> "Tag already assigned"
            previousProjectId == null -> "Assign known tag?"
            else -> "Reassign known tag?"
        }
        val message = when {
            sameProject -> "This known tag already opens the current project. It was not overwritten."
            previousProjectId == null -> "Assign this known, unbound tag to the current project without rewriting it?"
            else -> "Move this known tag to the current project without changing its payload?"
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(if (sameProject) "Use existing" else "Assign") { _, _ ->
                    runRepositoryAction {
                        controller.discardPendingTagProgramming(pending.clientBlockId)
                        if (!sameProject) controller.assignLocalBlock(existing.clientBlockId, targetProjectId)
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun completeWithQr(block: LocalBlockRecord) {
        runRepositoryAction(complete = {
            controller.state.localBlocks.firstOrNull { it.clientBlockId == block.clientBlockId }?.let(::showQr)
        }) {
            controller.completeTagProgramming(block.clientBlockId, block.token, TagActivationMedium.QR)
        }
    }

    private fun showQr(block: LocalBlockRecord) {
        val size = dp(280).coerceAtLeast(1)
        val image = ImageView(this).apply {
            id = ProjectUiIds.TAG_QR
            contentDescription = "QR code for ${block.label}"
            adjustViewBounds = true
            setImageBitmap(BarcodeEncoder().encodeBitmap(TagLocator.create(block.token).encoded, BarcodeFormat.QR_CODE, size, size))
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle(block.label)
                .setMessage("This QR carries the same opaque Blocks locator as NFC.")
                .setView(image)
                .setPositiveButton("Done", null)
                .create(),
        )
    }

    private fun confirmUnbind(block: LocalBlockRecord) {
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Unbind ${block.label}?")
                .setMessage("The tag remains valid but will no longer open a project until it is assigned again.")
                .setPositiveButton("Unbind") { _, _ ->
                    runRepositoryAction { controller.unbindLocalBlock(block.clientBlockId) }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun launchQrScanner() {
        qrScanner.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a Blocks QR tag")
                setBeepEnabled(false)
                setOrientationLocked(false)
            },
        )
    }

    internal fun handleQrText(value: String) {
        val locator = TagLocator.parse(value)
        if (locator == null) {
            showMessage("QR not recognized", "This is not a supported Blocks tag.")
        } else {
            pendingTagLocator = locator
            resolvePendingTagLocator()
        }
    }

    private fun consumeNdefIntent(source: Intent?): TagLocator? {
        val locator = NdefLocatorCodec.fromIntent(source)
        if (source?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            setIntent(Intent(this, MainActivity::class.java))
        }
        return locator
    }

    private fun resolvePendingTagLocator() {
        if (!controller.isAuthenticated) return
        val locator = pendingTagLocator ?: return
        pendingTagLocator = null
        uiScope.launch {
            val local = try {
                withContext(localStateDispatcher) { controller.resolveTagLocally(locator.token) }
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
                return@launch
            }
            val resolution = if (local is LocalTagResolution.Unknown && controller.canSynchronize) {
                runCatching { withContext(Dispatchers.IO) { controller.resolveTagThroughService(locator.token) } }
                    .getOrElse { error ->
                        handleRepositoryError(error as? Exception ?: IllegalStateException("Tag lookup failed"))
                        local
                    }
            } else {
                local
            }
            when (resolution) {
                is LocalTagResolution.Bound -> {
                    withContext(localStateDispatcher) { controller.openProject(resolution.projectId) }
                    render()
                }
                is LocalTagResolution.Unbound -> {
                    render()
                    showAssignTag(resolution.block)
                }
                is LocalTagResolution.PendingProgramming -> {
                    val projectId = resolution.block.intendedProjectId
                    if (projectId != null) {
                        runCatching { withContext(localStateDispatcher) { controller.openProject(projectId) } }
                    }
                    render()
                    showTagProgramming(resolution.block)
                }
                is LocalTagResolution.Unknown -> {
                    render()
                    showManagedDialog(
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Tag not available")
                            .setMessage(
                                "This tag is not available in the current account. The Dashboard remains usable offline.",
                            )
                            .setPositiveButton("Retry") { _, _ ->
                                pendingTagLocator = TagLocator.create(resolution.token)
                                resolvePendingTagLocator()
                            }
                            .setNegativeButton("Dashboard", null)
                            .create(),
                    )
                }
            }
        }
    }

    private fun showAssignTag(block: LocalBlockRecord) {
        val projects = (controller.state.activeProjects + controller.state.archivedProjects).distinctBy { it.id }
        if (projects.isEmpty()) {
            showMessage("Unassigned tag", "Create or reopen a project, then scan this tag again to assign it.")
            return
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Assign ${block.label}")
                .setItems(projects.map { it.name }.toTypedArray()) { _, which ->
                    val project = projects[which]
                    runRepositoryAction {
                        controller.assignLocalBlock(block.clientBlockId, project.id)
                        controller.openProject(project.id)
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun showMessage(title: String, message: String) {
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Done", null)
                .create(),
        )
    }

    private fun NfcProgrammingResult.toProgrammingFailure() = when (this) {
        NfcProgrammingResult.Unsupported -> TagProgrammingFailure.UNSUPPORTED
        NfcProgrammingResult.ReadOnly -> TagProgrammingFailure.READ_ONLY
        NfcProgrammingResult.TooSmall -> TagProgrammingFailure.TOO_SMALL
        NfcProgrammingResult.ForeignContent,
        is NfcProgrammingResult.ExistingBlocksTag,
        -> TagProgrammingFailure.FOREIGN_CONTENT
        NfcProgrammingResult.VerificationMismatch -> TagProgrammingFailure.VERIFICATION_MISMATCH
        NfcProgrammingResult.WriteUncertain -> TagProgrammingFailure.IO_UNCERTAIN
        NfcProgrammingResult.Verified -> error("A verified NFC write is not a failure")
    }

    private fun TagProgrammingFailure.userMessage() = when (this) {
        TagProgrammingFailure.UNSUPPORTED -> "This tag does not support writable NDEF records."
        TagProgrammingFailure.READ_ONLY -> "This tag is read-only."
        TagProgrammingFailure.TOO_SMALL -> "This tag is too small for a Blocks locator."
        TagProgrammingFailure.FOREIGN_CONTENT -> "The existing tag content was left unchanged."
        TagProgrammingFailure.VERIFICATION_MISMATCH -> {
            "The write could not be verified. Tap the same tag to resume with the saved token."
        }
        TagProgrammingFailure.IO_UNCERTAIN -> {
            "The write result is uncertain. Tap the same tag to verify; no replacement token was created."
        }
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
            addTextChangedListener(watcher { value ->
                queueDraftUpdate { controller.updateRename(value) }
            })
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
        if (credentialJob?.isActive == true) return
        credentialJob = uiScope.launch {
            try {
                authenticate(identity.signIn(this@MainActivity))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
            } finally {
                credentialJob = null
            }
        }
    }

    private fun restoreSignIn() {
        uiScope.launch {
            try {
                val acceptedTenant = withContext(localStateDispatcher) { acceptedTenantStore.load() }
                if (acceptedTenant == null) {
                    continuity.shouldRestore = false
                    controller.lock()
                    render()
                    return@launch
                } else {
                    withContext(localStateDispatcher) {
                        controller.restoreCached(acceptedTenant.tenantId)
                        restoreProjectId?.let { projectId ->
                            controller.openProject(projectId)
                            restoreProjectId = null
                        }
                    }
                    render()
                    resolvePendingTagLocator()
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
        if (credentialJob?.isActive == true) return
        credentialJob = uiScope.launch {
            try {
                runCatching { identity.clear(this@MainActivity) }
                authenticate(identity.signIn(this@MainActivity))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
            } finally {
                credentialJob = null
            }
        }
    }

    private suspend fun authenticate(token: String) {
        try {
            acceptServiceValidatedToken(token)
            render()
            resolvePendingTagLocator()
            synchronizeAutomatically()
        } catch (error: AcceptedAccountBindingException) {
            failClosedAccountBinding()
        }
    }

    private suspend fun acceptServiceValidatedToken(token: String): Session {
        val priorTenantId = controller.activeTenantId
        val authenticated = withContext(Dispatchers.IO) { controller.authenticate(token) }
        val accountIdentity = requireNotNull(authenticated.accountIdentity) {
            "Service accepted a session without a stable account identity"
        }
        withContext(localStateDispatcher) {
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
            withContext(localStateDispatcher) {
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
                withContext(localStateDispatcher) {
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
            val tenantId = controller.activeTenantId ?: withContext(localStateDispatcher) {
                acceptedTenantStore.load()?.tenantId
            } ?: return@launch
            syncJob?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            val removed = runCatching {
                withContext(localStateDispatcher) {
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

    private fun synchronizeAutomatically() {
        if (!controller.canSynchronize || syncJob?.isActive == true) return
        syncJob = uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
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
        source: View? = null,
        afterRender: Boolean = true,
        syncAfter: Boolean = false,
        complete: (() -> Unit)? = null,
        action: () -> Unit,
    ) {
        source?.let {
            it.isEnabled = false
            it.alpha = 0.55f
        }
        uiScope.launch {
            try {
                withContext(NonCancellable + localStateDispatcher) { action() }
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
                if (controller.canSynchronize) synchronizeAutomatically()
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

    /** Serializes encrypted draft writes off the main thread without reordering keystrokes. */
    private fun queueDraftUpdate(action: () -> Unit) {
        uiScope.launch {
            try {
                withContext(NonCancellable + localStateDispatcher) { action() }
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
            }
        }
    }

    private suspend fun handleRepositoryError(error: Exception) {
        val revokedTenantId = controller.reportRepositoryError(error)
        if (revokedTenantId != null) {
            val failingJob = currentCoroutineContext()[Job]
            syncJob?.takeIf { it !== failingJob }?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            withContext(NonCancellable) {
                runCatching {
                    withContext(localStateDispatcher) { acceptedTenantStore.remove(revokedTenantId) }
                }
                runCatching { identity.clear(this@MainActivity) }
            }
        }
    }

    override fun onDestroy() {
        nfcTagPlatform.stopReader()
        uiScope.cancel()
        localStateDispatcher.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dashboardColumnCount(): Int = when {
        resources.configuration.screenWidthDp >= 840 -> 4
        resources.configuration.screenWidthDp >= 600 -> 3
        else -> 2
    }

    internal companion object {
        private const val STATE_PROJECT_ID = "project_id"

        @Volatile
        var dependencyFactory: ((MainActivity) -> ProjectAppDependencies)? = null
    }
}
