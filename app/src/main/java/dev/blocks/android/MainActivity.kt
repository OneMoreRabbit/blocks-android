package dev.blocks.android

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import dev.blocks.android.cover.AndroidProjectCoverImporter
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.CoverCaptureFiles
import dev.blocks.android.cover.PendingCameraCover
import dev.blocks.android.cover.ProjectCoverImporter
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.ProjectFormField
import dev.blocks.android.project.DashboardView
import dev.blocks.android.project.DirectTagAttemptScope
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectScreen
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuity
import dev.blocks.android.state.SessionContinuityStore
import dev.blocks.android.sync.LegacyOperationReplayer
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.tag.AndroidNfcTagPlatform
import dev.blocks.android.tag.BlocksNavigationAddress
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.NfcClearResult
import dev.blocks.android.tag.NfcIoCancellation
import dev.blocks.android.tag.NfcPresentationCorrelation
import dev.blocks.android.tag.NfcProgrammingResult
import dev.blocks.android.tag.NfcTagHandle
import dev.blocks.android.tag.NfcTagPlatform
import dev.blocks.android.tag.ProjectLink
import dev.blocks.android.tag.TagLocator
import dev.blocks.android.tag.TagLaunchIntent
import dev.blocks.android.tag.TagShareIntent
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.gson.Gson
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
    val coverImporter: ProjectCoverImporter? = null,
)

private sealed interface ActiveTagAction {
    data class Write(val link: ProjectLink) : ActiveTagAction
    data object Clear : ActiveTagAction
}

private enum class TagAttemptPhase { ARMED, IN_FLIGHT, AWAITING_READ_BACK, COMPLETE, CANCELLED }

private class ActiveTagAttempt(
    val action: ActiveTagAction,
    val scope: DirectTagAttemptScope,
    val cancellation: NfcIoCancellation,
    var phase: TagAttemptPhase = TagAttemptPhase.ARMED,
) {
    var dialog: AlertDialog? = null
    var ioJob: Job? = null
    var readBackCorrelation: NfcPresentationCorrelation? = null
}

private enum class TagIoStep { PROGRAM, VERIFY_WRITE, CLEAR, VERIFY_CLEAR }

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
    const val PARKING_HISTORY_DIVIDER = 1054
    const val SEARCH = 1060
    const val VIEW_TILES = 1061
    const val VIEW_LIST = 1062
    const val SEARCH_EMPTY = 1063
    const val DASHBOARD_WATERMARK = 1064
    const val ROOT_CONTAINER = 1065
    const val TOP_NAVIGATION = 1066
    const val PROJECT_COVER = 1070
    const val EDIT_COVER = 1071
}

class MainActivity : ComponentActivity() {
    private val dependencies by lazy { dependencyFactory?.invoke(this) ?: productionDependencies() }
    private val controller get() = dependencies.controller
    private val identity get() = dependencies.identity
    private val continuity get() = dependencies.continuity
    private val acceptedTenantStore get() = dependencies.acceptedTenantStore
    private val nfcTagPlatform by lazy { dependencies.nfcTagPlatform ?: AndroidNfcTagPlatform(this) }
    private val coverImporter by lazy { dependencies.coverImporter ?: AndroidProjectCoverImporter(this) }
    private val coverCaptureFiles by lazy { CoverCaptureFiles(this) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localStateDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "blocks-local-state")
    }.asCoroutineDispatcher()
    private lateinit var content: LinearLayout
    private lateinit var rootScroll: ScrollView
    private lateinit var topNavigation: LinearLayout
    private lateinit var bottomActions: LinearLayout
    private var syncJob: Job? = null
    private var credentialJob: Job? = null
    private var restoreProjectId: String? = null
    private var dashboardSearchQuery: String = ""
    private var transientDraftsToRestore: FormDrafts? = null
    private var transientParkingToRestore: Boolean = false
    private var pendingCameraCover: PendingCameraCover? = null
    private var pendingCoverProjectId: String? = null
    private var renderGeneration = 0L
    private val coverJobs = mutableListOf<Job>()
    private val coverBitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private var pendingNavigationAddress: BlocksNavigationAddress? = null
    private var pendingInvalidTagIntent = false
    private val tagAttemptLock = Any()
    @Volatile
    private var activeTagAttempt: ActiveTagAttempt? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var activeDialog: AlertDialog? = null
        private set
    private val coverPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        handleCoverPickerResult(uri)
    }
    private val coverCameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        handleCoverCameraResult(saved)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeTagIntent(intent, clearActivityIntent = true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        restoreProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
        transientDraftsToRestore = savedInstanceState?.getString(STATE_TRANSIENT_DRAFTS)?.let {
            Gson().fromJson(it, FormDrafts::class.java)
        }
        transientParkingToRestore = savedInstanceState?.getBoolean(STATE_TRANSIENT_PARKING, false) ?: false
        pendingCoverProjectId = savedInstanceState?.getString(STATE_COVER_PROJECT_ID)
        val interruptedCapture = coverCaptureFiles.restore(savedInstanceState?.getString(STATE_CAMERA_COVER_PATH))
        if (interruptedCapture != null) pendingCoverProjectId = null
        uiScope.launch(Dispatchers.IO) {
            coverCaptureFiles.delete(interruptedCapture)
            coverCaptureFiles.cleanupOrphans()
            coverImporter.cleanupAbandonedSources()
        }
        val root = LinearLayout(this).apply {
            id = ProjectUiIds.ROOT_CONTAINER
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(brandColor(R.color.blocks_paper))
        }
        topNavigation = LinearLayout(this).apply {
            id = ProjectUiIds.TOP_NAVIGATION
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }
        content = LinearLayout(this).apply {
            id = ProjectUiIds.CONTENT
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(brandColor(R.color.blocks_paper))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topNavigation.setPadding(dp(16), systemBars.top + dp(8), dp(16), dp(6))
            bottomActions.setPadding(dp(16), dp(6), dp(16), systemBars.bottom + dp(8))
            insets
        }
        rootScroll = ScrollView(this).apply {
            id = ProjectUiIds.ROOT_SCROLL
            isFillViewport = true
            setBackgroundColor(brandColor(R.color.blocks_paper))
            addView(content)
        }
        root.addView(topNavigation)
        root.addView(rootScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(bottomActions)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
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
        startNfcReader()
        if (controller.canSynchronize) synchronizeAutomatically()
    }

    override fun onPause() {
        cancelActiveTagAttempt()
        nfcTagPlatform.stopReader()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingTagIntent(intent)
    }

    internal fun handleIncomingTagIntent(intent: Intent) {
        consumeTagIntent(intent, clearActivityIntent = false)
        resolvePendingTagLocator()
    }

    override fun onStop() {
        unregisterConnectivitySync()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        controller.state.projectPage?.project?.id?.let { outState.putString(STATE_PROJECT_ID, it) }
        if (controller.isAuthenticated) {
            outState.putString(STATE_TRANSIENT_DRAFTS, Gson().toJson(controller.state.drafts))
            outState.putBoolean(STATE_TRANSIENT_PARKING, controller.state.showParkingForm)
        }
        pendingCoverProjectId?.let { outState.putString(STATE_COVER_PROJECT_ID, it) }
        pendingCameraCover?.path?.let { outState.putString(STATE_CAMERA_COVER_PATH, it) }
        super.onSaveInstanceState(outState)
    }

    private fun productionDependencies(): ProjectAppDependencies {
        val draftStore = AndroidEncryptedDraftStore(this)
        val syncStore = AndroidSyncStateStore(this)
        val baseUri = URI(BuildConfig.BLOCKS_API_BASE_URL)
        val sessionClient = BlocksApiClient(baseUri, BuildConfig.BLOCKS_SERVICE_API_VERSION)
        val sessionAuthenticator = ServiceSessionAuthenticator(sessionClient::exchangeSession)
        val legacyService = ServiceBlocksRepository(BlocksApiClient(baseUri, "0.2"))
        val workspace = ProjectSyncRepository(
            ServiceProjectRepository(ProjectApiClient(baseUri, BuildConfig.BLOCKS_SERVICE_API_VERSION)),
            LegacyOperationReplayer(legacyService, draftStore, syncStore),
            draftStore,
            syncStore,
            coverMediaStore = AndroidEncryptedProjectCoverMediaStore(this),
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
        renderGeneration += 1
        coverJobs.forEach { it.cancel() }
        coverJobs.clear()
        content.removeAllViews()
        topNavigation.removeAllViews()
        bottomActions.removeAllViews()
        if (!continuity.shouldRestore) restoreTransientDraftsIfReady()
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
        if (controller.state.screen == ProjectScreen.LOGIN) {
            bar.addView(ImageView(this).apply {
                setImageResource(R.drawable.blocks_foreground)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            bar.addView(
                heading("Blocks", 28f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        } else if (controller.state.screen == ProjectScreen.PROJECT) {
            bar.addView(
                iconOnlyButton(R.drawable.ic_back, "Dashboard") {
                    controller.backToDashboard()
                    render()
                }.apply { id = ProjectUiIds.MAIN },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        if (controller.state.screen == ProjectScreen.DASHBOARD) {
            bar.addView(dashboardControls(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
        }
        if (controller.state.screen == ProjectScreen.PROJECT) {
            bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            bar.addView(iconOnlyButton(R.drawable.ic_more, "Project settings") { showProjectSettings() }.apply {
                id = ProjectUiIds.PROJECT_SETTINGS
            })
        }
        topNavigation.addView(bar)
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
        if (archiveMode) body.addView(heading("Archive", 24f).apply { setPadding(0, dp(16), 0, dp(8)) })

        val collection = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            minimumHeight = dp(360)
        }
        collection.addView(
            ImageView(this).apply {
                id = ProjectUiIds.DASHBOARD_WATERMARK
                setImageResource(R.drawable.blocks_monochrome)
                imageTintList = ColorStateList.valueOf(brandColor(R.color.blocks_teal))
                alpha = 0.055f
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(dp(360), dp(360), Gravity.CENTER),
        )
        body.addView(
            collection,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        populateDashboardCollection(collection, projects, archiveMode)

        if (archiveMode) {
            bottomActions.addView(secondaryButton("Projects", R.drawable.ic_back) { source ->
                runRepositoryAction(source = source) { controller.showMainDashboard() }
            }.apply { id = ProjectUiIds.ARCHIVE_MAIN }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
        } else {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(
                primaryButton("Create project", R.drawable.ic_plus) { showCreateProject() }.apply {
                    id = ProjectUiIds.CREATE_PROJECT
                },
                LinearLayout.LayoutParams(0, dp(48), 1.6f).apply { marginEnd = dp(6) },
            )
            actions.addView(
                secondaryButton("Archive", R.drawable.ic_archive) { openArchiveMode() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) },
            )
            bottomActions.addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
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

    private fun dashboardControls(): View {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val search = EditText(this).apply {
            id = ProjectUiIds.SEARCH
            hint = "Search projects"
            contentDescription = "Search projects"
            setText(dashboardSearchQuery)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(brandColor(R.color.blocks_ink))
            setHintTextColor(brandColor(R.color.blocks_muted))
            textSize = 14f
            typeface = interTypeface
            setPadding(dp(14), 0, dp(10), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_input), 10f)
            minimumHeight = dp(48)
            addTextChangedListener(watcher { value ->
                dashboardSearchQuery = value
                val projects = if (controller.state.archiveMode) {
                    controller.state.archivedProjects
                } else {
                    controller.state.activeProjects
                }
                val collection = content.findViewById<View>(ProjectUiIds.DASHBOARD_WATERMARK)?.parent as? FrameLayout
                collection?.let { populateDashboardCollection(it, projects, controller.state.archiveMode) }
            })
        }
        controls.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })

        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tilesSelected = controller.state.dashboardView == DashboardView.TILES
        toggle.addView(
            viewToggleButton(R.drawable.ic_grid, "Tile view", tilesSelected) {
                if (!tilesSelected) runRepositoryAction { controller.toggleDashboardView() }
            }.apply { id = ProjectUiIds.VIEW_TILES },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        toggle.addView(
            viewToggleButton(R.drawable.ic_list, "List view", !tilesSelected) {
                if (tilesSelected) runRepositoryAction { controller.toggleDashboardView() }
            }.apply { id = ProjectUiIds.VIEW_LIST },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        controls.addView(toggle, LinearLayout.LayoutParams(dp(96), dp(48)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        controls.addView(iconOnlyButton(R.drawable.ic_settings, "Settings") { showSettings() }.apply {
            id = ProjectUiIds.SETTINGS
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return controls
    }

    private fun populateDashboardCollection(
        frame: FrameLayout,
        sourceProjects: List<ProjectSummary>,
        archiveMode: Boolean,
    ) {
        frame.findViewWithTag<View>(DASHBOARD_COLLECTION_TAG)?.let(frame::removeView)
        val query = dashboardSearchQuery.trim()
        val projects = if (query.isEmpty()) sourceProjects else sourceProjects.filter {
            it.name.contains(query, ignoreCase = true)
        }
        val view = when {
            projects.isEmpty() && query.isNotEmpty() -> searchEmptyState()
            projects.isEmpty() -> emptyDashboardState(archiveMode)
            controller.state.dashboardView == DashboardView.TILES -> tileCollection(projects, archiveMode)
            else -> listCollection(projects, archiveMode)
        }
        view.tag = DASHBOARD_COLLECTION_TAG
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun tileCollection(projects: List<ProjectSummary>, archiveMode: Boolean) = GridLayout(this).apply {
        id = ProjectUiIds.PROJECT_TILES
        columnCount = dashboardColumnCount()
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
        setPadding(0, dp(2), 0, dp(2))
        projects.forEach { project ->
            addView(
                projectTile(project, archiveMode),
                GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                },
            )
        }
    }

    private fun listCollection(projects: List<ProjectSummary>, archiveMode: Boolean) = LinearLayout(this).apply {
        id = ProjectUiIds.PROJECT_LIST
        orientation = LinearLayout.VERTICAL
        projects.forEachIndexed { index, project ->
            addView(projectRow(project, archiveMode))
            if (index < projects.lastIndex) addView(divider())
        }
    }

    private fun projectTile(project: ProjectSummary, archiveMode: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(brandColor(R.color.blocks_white), Color.TRANSPARENT, 12f)
        clipToOutline = true
        configureProjectQuickLink(project, archiveMode)
        val image = projectImage(project, thumbnail = true)
        addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)))
        addView(
            projectButton(project, archiveMode).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = null
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )
    }

    private fun projectRow(project: ProjectSummary, archiveMode: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(0, dp(4), 0, dp(4))
        configureProjectQuickLink(project, archiveMode)
        addView(projectImage(project, thumbnail = true), LinearLayout.LayoutParams(dp(88), dp(60)).apply {
            marginEnd = dp(10)
        })
        addView(projectButton(project, archiveMode).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = null
            setPadding(0, 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_arrow)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(32), dp(48)))
    }

    private fun projectButton(project: ProjectSummary, archiveMode: Boolean) = Button(this).apply {
        text = project.name
        isAllCaps = false
        textSize = 17f
        typeface = manropeTypeface
        setTextColor(brandColor(R.color.blocks_ink))
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { source ->
            if (archiveMode) confirmReopen(project) else runRepositoryAction(source = source) {
                controller.openProject(project.id)
            }
        }
    }

    private fun View.configureProjectQuickLink(project: ProjectSummary, archiveMode: Boolean) {
        isClickable = true
        isFocusable = true
        contentDescription = if (archiveMode) "Reopen ${project.name}" else "Open ${project.name}"
        setOnClickListener { source ->
            if (archiveMode) confirmReopen(project) else runRepositoryAction(source = source) {
                controller.openProject(project.id)
            }
        }
    }

    private fun projectImage(project: ProjectSummary, thumbnail: Boolean) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = roundedBackground(brandColor(R.color.blocks_soft), Color.TRANSPARENT, 10f)
        setImageResource(R.drawable.blocks_foreground)
        contentDescription = null
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        controller.state.projectCovers.firstOrNull { it.projectId == project.id }?.let { cover ->
            loadCover(this, cover, thumbnail)
        }
    }

    private fun searchEmptyState() = LinearLayout(this).apply {
        id = ProjectUiIds.SEARCH_EMPTY
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(72), dp(24), dp(72))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(brandColor(R.color.blocks_teal))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(heading("No projects found", 22f).apply { gravity = Gravity.CENTER })
        addView(label("Try another search or clear the filter.", 15f, brandColor(R.color.blocks_muted)).apply {
            gravity = Gravity.CENTER
        })
        addView(secondaryButton("Clear search", R.drawable.ic_search) {
            dashboardSearchQuery = ""
            render()
        }, LinearLayout.LayoutParams(dp(190), dp(52)))
        addView(label("Search works on this device.", 13f, brandColor(R.color.blocks_muted)).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun emptyDashboardState(archiveMode: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(72), dp(24), dp(72))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.blocks_foreground)
            contentDescription = null
            alpha = 0.85f
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(heading(if (archiveMode) "No archived projects" else "Your first project starts here.", 20f).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun showSettings() {
        val items = buildList {
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
                    "covers, drafts, and pending changes from this device.",
            )
            .setPositiveButton("Remove local data") { _, _ -> removeActiveLocalData() }
            .setNegativeButton("Cancel", null)
            .create()
        showManagedDialog(dialog)
    }

    private fun renderProject() {
        content.tag = ProjectUiIds.SCREEN_PROJECT
        val page = requireNotNull(controller.state.projectPage)
        content.addView(heading(page.project.name, 30f).apply {
            setPadding(0, dp(6), 0, dp(8))
        })

        val coverFrame = FrameLayout(this).apply {
            id = ProjectUiIds.PROJECT_COVER
            background = roundedBackground(brandColor(R.color.blocks_soft), Color.TRANSPARENT, 14f)
            clipToOutline = true
        }
        coverFrame.addView(
            projectImage(page.project, thumbnail = false),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        coverFrame.addView(
            secondaryButton("Edit cover", R.drawable.ic_edit) { showCoverActions() }.apply {
                id = ProjectUiIds.EDIT_COVER
                backgroundTintList = ColorStateList.valueOf(Color.argb(218, 255, 255, 255))
            },
            FrameLayout.LayoutParams(dp(150), dp(50), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(12), dp(12))
            },
        )
        content.addView(
            coverFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)).apply {
                bottomMargin = dp(12)
            },
        )

        val newestParking = page.newestParking
        val parkingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_line), 12f)
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        parkingCard.addView(heading("Where you left off", 20f))
        if (newestParking == null) {
            parkingCard.addView(label("No park message yet.", 16f, brandColor(R.color.blocks_muted)))
        } else {
            parkingCard.addView(fieldValue("Where I am", newestParking.location))
            parkingCard.addView(divider())
            parkingCard.addView(fieldValue("Next physical action", newestParking.nextAction))
            parkingCard.addView(divider())
            parkingCard.addView(fieldValue("Must not forget", newestParking.mustNotForget))
        }
        content.addView(parkingCard)
        bottomActions.addView(primaryButton("Park", R.drawable.ic_park) {
            controller.showParkingForm()
            render()
        }.apply { id = ProjectUiIds.PARK }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))

        content.addView(heading("Notes", 22f))
        val notesValue = controller.state.drafts.projectNotes[page.project.id] ?: page.notes.body
        val notes = input("Project notes", notesValue) { value ->
            queueDraftUpdate { controller.updateNotes(value) }
        }.apply {
            id = ProjectUiIds.NOTES
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            isVerticalScrollBarEnabled = true
            ViewCompat.setNestedScrollingEnabled(this, true)
        }
        notes.markInvalid(ProjectFormField.NOTES)
        notes.background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_input), 10f)
        content.addView(notes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(primaryButton("Save notes", R.drawable.ic_edit) { source ->
            runRepositoryAction(source = source, syncAfter = true) {
                controller.saveNotes(notes.text.toString())
            }
        }.apply { id = ProjectUiIds.SAVE_NOTES }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { topMargin = dp(10); bottomMargin = dp(20) })

        val olderParking = controller.state.parkingHistory.filterNot { it.id == newestParking?.id }
        if (olderParking.isNotEmpty() || controller.state.parkingHistoryHasMore) {
            content.addView(heading("Parking history", 20f))
            val history = LinearLayout(this).apply {
                id = ProjectUiIds.PARKING_HISTORY
                orientation = LinearLayout.VERTICAL
            }
            olderParking.forEachIndexed { index, record ->
                if (index > 0) {
                    history.addView(
                        View(this).apply {
                            id = ProjectUiIds.PARKING_HISTORY_DIVIDER
                            setBackgroundColor(Color.rgb(200, 197, 188))
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            topMargin = dp(12)
                            bottomMargin = dp(12)
                        },
                    )
                }
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
        topNavigation.addView(heading)
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
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            isVerticalScrollBarEnabled = true
            ViewCompat.setNestedScrollingEnabled(this, true)
            background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_input), 10f)
        }
        val nextAction = input("Next physical action", nextActionValue) { nextActionValue = it; persist() }.apply {
            id = ProjectUiIds.PARKING_NEXT_ACTION
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            isVerticalScrollBarEnabled = true
            ViewCompat.setNestedScrollingEnabled(this, true)
            background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_input), 10f)
        }
        val mustNotForget = input("Must not forget", mustNotForgetValue) {
            mustNotForgetValue = it
            persist()
        }.apply {
            id = ProjectUiIds.PARKING_MUST_NOT_FORGET
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            isVerticalScrollBarEnabled = true
            ViewCompat.setNestedScrollingEnabled(this, true)
            background = roundedBackground(brandColor(R.color.blocks_white), brandColor(R.color.blocks_input), 10f)
        }
        location.markInvalid(ProjectFormField.PARKING_LOCATION)
        nextAction.markInvalid(ProjectFormField.PARKING_NEXT_ACTION)
        mustNotForget.markInvalid(ProjectFormField.PARKING_MUST_NOT_FORGET)
        content.addView(location, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) })
        content.addView(nextAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) })
        content.addView(mustNotForget, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        bottomActions.addView(primaryButton("Save park message", R.drawable.ic_park) { source ->
            runRepositoryAction(source = source, syncAfter = true) {
                controller.addParking(
                    location.text.toString(),
                    nextAction.text.toString(),
                    mustNotForget.text.toString(),
                )
            }
        }.apply { id = ProjectUiIds.SAVE_PARKING }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
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
        val items = arrayOf("Project cover", "Set Tag", "View QR", "Clear Tags", "Rename", "Archive")
        val dialog = AlertDialog.Builder(this).setTitle("Project settings")
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Project cover" -> showCoverActions()
                    "Set Tag" -> showSetTag()
                    "View QR" -> showLatestQr()
                    "Clear Tags" -> showClearTags()
                    "Rename" -> showRename()
                    "Archive" -> confirmArchive()
                }
            }.setNegativeButton("Cancel", null).create()
        showManagedDialog(dialog)
    }

    private fun showCoverActions() {
        val page = controller.state.projectPage ?: return
        val hasCover = controller.state.projectCovers.any { it.projectId == page.project.id }
        val items = buildList {
            add(if (hasCover) "Take replacement photo" else "Take photo")
            add(if (hasCover) "Choose replacement image" else "Choose image")
            if (hasCover) add("Remove cover")
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Project cover")
                .setMessage("Choose how this project appears on your dashboard.")
                .setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "Take photo", "Take replacement photo" -> launchCoverCamera(page.project.id)
                        "Choose image", "Choose replacement image" -> launchCoverPicker(page.project.id)
                        "Remove cover" -> confirmRemoveCover()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    private fun launchCoverPicker(projectId: String) {
        stagePendingCover(projectId)
        runCatching {
            coverPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            pendingCoverProjectId = null
            showMessage("Image unavailable", "Blocks could not open the system photo picker. Your current cover is unchanged.")
        }
    }

    private fun launchCoverCamera(projectId: String) {
        val capture = runCatching { coverCaptureFiles.create() }.getOrElse {
            showMessage("Camera unavailable", "Blocks could not create a private temporary photo. Your current cover is unchanged.")
            return
        }
        stagePendingCover(projectId, capture)
        try {
            coverCameraLauncher.launch(capture.uri)
        } catch (_: ActivityNotFoundException) {
            discardPendingCameraCover()
            showMessage("Camera unavailable", "No camera app is available. Your current cover is unchanged.")
        } catch (_: SecurityException) {
            discardPendingCameraCover()
            showMessage("Camera unavailable", "The camera did not accept the private photo destination. Your current cover is unchanged.")
        }
    }

    internal fun handleCoverPickerResult(uri: Uri?) {
        val projectId = pendingCoverProjectId
        pendingCoverProjectId = null
        if (uri != null && projectId != null) importCover(projectId, uri)
    }

    internal fun stagePendingCover(projectId: String, capture: PendingCameraCover? = null) {
        pendingCoverProjectId = projectId
        pendingCameraCover = capture
    }

    internal fun handleCoverCameraResult(saved: Boolean) {
        val projectId = pendingCoverProjectId
        val capture = pendingCameraCover
        pendingCoverProjectId = null
        pendingCameraCover = null
        if (!saved || projectId == null || capture == null) {
            uiScope.launch(Dispatchers.IO) { runCatching { coverCaptureFiles.delete(capture) } }
            return
        }
        importCover(projectId, capture.uri, capture)
    }

    internal fun importCover(projectId: String, uri: Uri, capture: PendingCameraCover? = null) {
        val tenantId = controller.activeTenantId
        if (tenantId == null) {
            coverCaptureFiles.delete(capture)
            return
        }
        uiScope.launch {
            var imported: dev.blocks.android.project.ProjectCoverContent? = null
            try {
                imported = withContext(Dispatchers.IO) { coverImporter.importCover(projectId, uri) }
                withContext(NonCancellable + localStateDispatcher) {
                    check(controller.activeTenantId == tenantId) { "The active account changed during cover import" }
                    check(controller.state.projectPage?.project?.id == projectId) {
                        "The selected project changed during cover import"
                    }
                    controller.saveProjectCover(requireNotNull(imported))
                }
            } catch (_: Exception) {
                controller.reportError("Blocks could not save that cover on this device. Your current cover is unchanged.")
            } finally {
                imported?.clear()
                if (capture != null) withContext(NonCancellable + Dispatchers.IO) {
                    coverCaptureFiles.delete(capture)
                }
            }
            render()
        }
    }

    private fun confirmRemoveCover() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Remove project cover?")
            .setMessage("The encrypted local cover and its thumbnail will be removed from this device.")
            .setPositiveButton("Remove") { _, _ ->
                runRepositoryAction { controller.removeProjectCover() }
            }
            .setNegativeButton("Cancel", null)
            .create()
        showManagedDialog(dialog)
    }

    private fun discardPendingCameraCover() {
        val capture = pendingCameraCover
        pendingCameraCover = null
        pendingCoverProjectId = null
        uiScope.launch(Dispatchers.IO) { runCatching { coverCaptureFiles.delete(capture) } }
    }

    private fun showSetTag() {
        var link: ProjectLink? = null
        runRepositoryAction(
            syncAfter = false,
            complete = { link?.let(::showTagWriter) },
        ) {
            link = controller.currentProjectLink()
        }
    }

    private fun showTagWriter(link: ProjectLink) {
        val attempt = newTagAttempt(ActiveTagAction.Write(link)) ?: return
        val message = when {
            !nfcTagPlatform.isAvailable -> "This device has no NFC hardware. No tag was changed."
            !nfcTagPlatform.isEnabled -> "NFC is turned off. Turn it on, then choose Set Tag again."
            else -> "Hold one writable NFC tag to this device until Blocks confirms verification. Its current writable content will be replaced."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Tag")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            cancelTagAttempt(attempt)
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        attempt.dialog = dialog
        dialog.show()
        startNfcReader()
    }

    private fun showClearTags() {
        val attempt = newTagAttempt(ActiveTagAction.Clear) ?: return
        val message = when {
            !nfcTagPlatform.isAvailable -> "This device has no NFC hardware. No tag was changed."
            !nfcTagPlatform.isEnabled -> "NFC is turned off. Turn it on, then choose Clear Tags again."
            else -> "Hold one writable NFC tag to erase it. Repeat Clear Tags for each additional tag."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Clear Tags")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            cancelTagAttempt(attempt)
            if (activeDialog === dialog) activeDialog = null
        }
        activeDialog = dialog
        attempt.dialog = dialog
        dialog.show()
        startNfcReader()
    }

    private fun startNfcReader() {
        if (activeTagAttempt == null || !nfcTagPlatform.isEnabled) return
        nfcTagPlatform.startReader(::handleTagPresentation)
    }

    private fun handleTagPresentation(handle: NfcTagHandle) {
        val claimed = synchronized(tagAttemptLock) {
            val attempt = activeTagAttempt ?: return
            val step = when (attempt.phase) {
                TagAttemptPhase.ARMED -> when (attempt.action) {
                    is ActiveTagAction.Write -> TagIoStep.PROGRAM
                    ActiveTagAction.Clear -> TagIoStep.CLEAR
                }
                TagAttemptPhase.AWAITING_READ_BACK -> when (attempt.action) {
                    is ActiveTagAction.Write -> TagIoStep.VERIFY_WRITE
                    ActiveTagAction.Clear -> TagIoStep.VERIFY_CLEAR
                }
                TagAttemptPhase.IN_FLIGHT,
                TagAttemptPhase.COMPLETE,
                TagAttemptPhase.CANCELLED,
                -> return
            }
            attempt.phase = TagAttemptPhase.IN_FLIGHT
            attempt to step
        }
        handleTagIo(claimed.first, claimed.second, handle)
    }

    private fun handleTagIo(attempt: ActiveTagAttempt, step: TagIoStep, handle: NfcTagHandle) {
        val job = uiScope.launch {
            if (!isActiveTagAttempt(attempt) || attempt.cancellation.isCancelled()) return@launch
            try {
                val outcome = withContext(Dispatchers.IO) {
                    when (step) {
                        TagIoStep.PROGRAM -> nfcTagPlatform.programAndVerify(
                            handle,
                            (attempt.action as ActiveTagAction.Write).link,
                            attempt.cancellation,
                        )
                        TagIoStep.VERIFY_WRITE -> nfcTagPlatform.verifyWritten(
                            handle,
                            (attempt.action as ActiveTagAction.Write).link,
                            requireNotNull(attempt.readBackCorrelation),
                            attempt.cancellation,
                        )
                        TagIoStep.CLEAR -> nfcTagPlatform.clearAndVerify(handle, attempt.cancellation)
                        TagIoStep.VERIFY_CLEAR -> nfcTagPlatform.verifyCleared(
                            handle,
                            requireNotNull(attempt.readBackCorrelation),
                            attempt.cancellation,
                        )
                    }
                }
                if (!isActiveTagAttempt(attempt)) {
                    cancelTagAttempt(attempt)
                    return@launch
                }
                when (outcome) {
                    is NfcProgrammingResult.AwaitingReadBack -> awaitTagReadBack(attempt, outcome.correlation)
                    is NfcClearResult.AwaitingReadBack -> awaitTagReadBack(attempt, outcome.correlation)
                    NfcProgrammingResult.Cancelled,
                    NfcClearResult.Cancelled,
                    -> cancelTagAttempt(attempt)
                    is NfcProgrammingResult.Verified -> finishTagWrite(attempt, outcome)
                    is NfcClearResult.Verified -> finishTagClear(attempt, outcome)
                    is NfcProgrammingResult -> presentTagOutcome(
                        attempt,
                        "Tag not set",
                        outcome.userMessage(),
                    )
                    is NfcClearResult -> presentTagOutcome(
                        attempt,
                        "Tag not cleared",
                        outcome.userMessage(),
                    )
                }
            } catch (_: CancellationException) {
                // Lifecycle/dialog cancellation owns cleanup and deliberately suppresses late results.
            }
        }
        synchronized(tagAttemptLock) {
            if (activeTagAttempt === attempt && attempt.phase != TagAttemptPhase.CANCELLED) {
                attempt.ioJob = job
            } else {
                attempt.cancellation.cancel()
                job.cancel()
            }
        }
    }

    private fun newTagAttempt(action: ActiveTagAction): ActiveTagAttempt? {
        val scope = runCatching(controller::captureDirectTagAttemptScope).getOrNull() ?: return null
        val attempt = ActiveTagAttempt(
            action,
            scope,
            NfcIoCancellation { controller.matchesDirectTagAttemptScope(scope) },
        )
        return attempt.also {
            synchronized(tagAttemptLock) {
                activeTagAttempt?.let {
                    it.phase = TagAttemptPhase.CANCELLED
                    it.cancellation.cancel()
                    it.ioJob?.cancel()
                }
                activeTagAttempt = attempt
            }
        }
    }

    private fun isActiveTagAttempt(attempt: ActiveTagAttempt): Boolean = synchronized(tagAttemptLock) {
        activeTagAttempt === attempt && attempt.phase != TagAttemptPhase.CANCELLED &&
            controller.matchesDirectTagAttemptScope(attempt.scope)
    }

    private fun awaitTagReadBack(attempt: ActiveTagAttempt, correlation: NfcPresentationCorrelation) {
        val stillActive = synchronized(tagAttemptLock) {
            if (activeTagAttempt !== attempt || attempt.phase == TagAttemptPhase.CANCELLED) false else {
                attempt.readBackCorrelation = correlation
                attempt.phase = TagAttemptPhase.AWAITING_READ_BACK
                true
            }
        }
        if (!stillActive) return
        attempt.dialog?.takeIf { it.isShowing }?.setMessage(
            "The tag was formatted. Remove it, then hold the same tag to the device again until Blocks verifies it.",
        )
    }

    private suspend fun finishTagWrite(attempt: ActiveTagAttempt, outcome: NfcProgrammingResult.Verified) {
        try {
            val replacedLegacy = outcome.replacedAddress as? TagLocator
            if (replacedLegacy != null) {
                withContext(NonCancellable + localStateDispatcher) {
                    controller.commitVerifiedDirectTagClear(attempt.scope, replacedLegacy.token)
                }
            }
            presentTagOutcome(attempt, "Tag set", "The project link was written and verified.")
        } catch (error: Exception) {
            completeTagAttempt(attempt)
            if (controller.matchesDirectTagAttemptScope(attempt.scope)) handleRepositoryError(error)
            render()
        }
    }

    private suspend fun finishTagClear(attempt: ActiveTagAttempt, outcome: NfcClearResult.Verified) {
        try {
            val erasedLegacy = outcome.erasedAddress as? TagLocator
            if (erasedLegacy != null) {
                withContext(NonCancellable + localStateDispatcher) {
                    controller.commitVerifiedDirectTagClear(attempt.scope, erasedLegacy.token)
                }
            }
            presentTagOutcome(attempt, "Tag cleared", "The presented tag was erased and verified.")
        } catch (error: Exception) {
            completeTagAttempt(attempt)
            if (controller.matchesDirectTagAttemptScope(attempt.scope)) handleRepositoryError(error)
            render()
        }
    }

    private fun presentTagOutcome(attempt: ActiveTagAttempt, title: String, message: String) {
        if (!isActiveTagAttempt(attempt)) {
            cancelTagAttempt(attempt)
            return
        }
        val completed = synchronized(tagAttemptLock) {
            if (activeTagAttempt !== attempt || attempt.phase == TagAttemptPhase.CANCELLED) false else {
                attempt.phase = TagAttemptPhase.COMPLETE
                true
            }
        }
        if (!completed) return
        attempt.dialog?.apply {
            setTitle(title)
            setMessage("$message Remove the tag, then choose Done.")
            getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Done"
        }
    }

    private fun completeTagAttempt(attempt: ActiveTagAttempt) {
        val completed = synchronized(tagAttemptLock) {
            if (activeTagAttempt !== attempt) false else {
                attempt.phase = TagAttemptPhase.CANCELLED
                activeTagAttempt = null
                true
            }
        }
        if (!completed) return
        attempt.cancellation.cancel()
        nfcTagPlatform.stopReader()
        attempt.dialog?.takeIf { it.isShowing }?.dismiss()
    }

    private fun cancelTagAttempt(attempt: ActiveTagAttempt) {
        val cancelled = synchronized(tagAttemptLock) {
            if (activeTagAttempt !== attempt) false else {
                attempt.phase = TagAttemptPhase.CANCELLED
                activeTagAttempt = null
                true
            }
        }
        if (cancelled) {
            attempt.cancellation.cancel()
            attempt.ioJob?.cancel()
            nfcTagPlatform.stopReader()
        }
    }

    private fun cancelActiveTagAttempt() {
        activeTagAttempt?.let(::cancelTagAttempt)
    }

    private fun showLatestQr() {
        var link: ProjectLink? = null
        runRepositoryAction(syncAfter = false, complete = { link?.let(::showQr) }) {
            link = controller.currentProjectLink()
        }
    }

    private fun showQr(link: ProjectLink) {
        val size = dp(280).coerceAtLeast(1)
        val image = ImageView(this).apply {
            id = ProjectUiIds.TAG_QR
            contentDescription = "QR code for this project"
            adjustViewBounds = true
            setImageBitmap(BarcodeEncoder().encodeBitmap(link.encoded, BarcodeFormat.QR_CODE, size, size))
        }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("Project QR")
                .setView(image)
                .setPositiveButton("Share") { _, _ -> shareProjectLink(link) }
                .setNegativeButton("Done", null)
                .create(),
        )
    }

    private fun shareProjectLink(link: ProjectLink) {
        startActivity(
            Intent.createChooser(TagShareIntent.create(link), "Share Blocks project"),
        )
    }

    internal fun handleQrText(value: String) {
        val address = dev.blocks.android.tag.BlocksNavigationAddressParser.parse(value)
        if (address == null) {
            pendingInvalidTagIntent = true
        } else {
            pendingNavigationAddress = address
        }
        resolvePendingTagLocator()
    }

    private fun consumeTagIntent(source: Intent?, clearActivityIntent: Boolean) {
        when (val parsed = TagLaunchIntent.parse(source)) {
            is TagLaunchIntent.Valid -> pendingNavigationAddress = parsed.address
            TagLaunchIntent.Invalid -> pendingInvalidTagIntent = true
            TagLaunchIntent.None -> return
        }
        if (clearActivityIntent) setIntent(Intent(this, MainActivity::class.java))
    }

    private fun resolvePendingTagLocator() {
        if (!controller.isAuthenticated) return
        if (pendingInvalidTagIntent) {
            pendingInvalidTagIntent = false
            render()
            showMessage("Link not recognized", "This is not a supported Blocks project link. The Dashboard remains available.")
            return
        }
        val address = pendingNavigationAddress ?: return
        pendingNavigationAddress = null
        uiScope.launch {
            val projectId = try {
                withContext(localStateDispatcher) {
                    when (address) {
                        is ProjectLink -> controller.resolveProjectLinkLocally(address.key)
                        is TagLocator -> when (val local = controller.resolveTagLocally(address.token)) {
                            is LocalTagResolution.Bound -> local.projectId
                            is LocalTagResolution.Unbound,
                            is LocalTagResolution.PendingProgramming,
                            is LocalTagResolution.Unknown,
                            -> null
                        }
                    }
                }
            } catch (error: Exception) {
                handleRepositoryError(error)
                render()
                return@launch
            }
            if (projectId != null) {
                withContext(localStateDispatcher) { controller.openProject(projectId) }
                render()
            } else {
                render()
                showMessage(
                    "Project not available",
                    "This link does not open a project in the current account's saved data. The Dashboard remains usable.",
                )
            }
        }
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

    private fun NfcProgrammingResult.userMessage() = when (this) {
        NfcProgrammingResult.Unsupported -> "This tag does not support writable NDEF records. No association was saved."
        NfcProgrammingResult.ReadOnly -> "This tag is read-only. No association was saved."
        NfcProgrammingResult.TooSmall -> "This tag is too small for a Blocks locator. No association was saved."
        NfcProgrammingResult.Removed -> "The tag was removed before writing began. No association was saved."
        NfcProgrammingResult.VerificationMismatch -> "The new value could not be verified. No association was saved."
        NfcProgrammingResult.WriteUncertain -> "The write result is uncertain. Choose Set Tag to make a fresh attempt."
        NfcProgrammingResult.Cancelled -> error("Cancellation is handled before failure presentation")
        is NfcProgrammingResult.AwaitingReadBack -> error("Read-back continuation is handled before failure presentation")
        is NfcProgrammingResult.Verified -> error("A verified NFC write is not a failure")
    }

    private fun NfcClearResult.userMessage() = when (this) {
        NfcClearResult.Unsupported -> "This tag does not support writable NDEF records. Its association was retained."
        NfcClearResult.ReadOnly -> "This tag is read-only. Its association was retained."
        NfcClearResult.Removed -> "The tag was removed before erasing began. Its content was not changed."
        NfcClearResult.VerificationMismatch -> "The erase could not be verified. Its association was retained."
        NfcClearResult.WriteUncertain -> "The erase result is uncertain. Its association was retained."
        NfcClearResult.Cancelled -> error("Cancellation is handled before failure presentation")
        is NfcClearResult.AwaitingReadBack -> error("Read-back continuation is handled before failure presentation")
        is NfcClearResult.Verified -> error("A verified NFC clear is not a failure")
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

    private val interTypeface: Typeface
        get() = resources.getFont(R.font.inter_variable)

    private val manropeTypeface: Typeface
        get() = resources.getFont(R.font.manrope_variable)

    private fun brandColor(resource: Int): Int = ContextCompat.getColor(this, resource)

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = manropeTypeface
        setTextColor(brandColor(R.color.blocks_ink))
        setPadding(0, 0, 0, dp(10))
    }

    private fun fieldValue(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 13f, brandColor(R.color.blocks_teal)).apply {
            typeface = manropeTypeface
            setPadding(0, 0, 0, dp(3))
        })
        addView(label(value, 17f, brandColor(R.color.blocks_ink)).apply {
            setPadding(0, 0, 0, dp(8))
        })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(brandColor(R.color.blocks_line))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun primaryButton(text: String, icon: Int, action: (View) -> Unit) = styledButton(
        text,
        icon,
        brandColor(R.color.blocks_teal),
        brandColor(R.color.blocks_white),
        Color.TRANSPARENT,
        action,
    )

    private fun secondaryButton(text: String, icon: Int, action: (View) -> Unit) = styledButton(
        text,
        icon,
        brandColor(R.color.blocks_white),
        brandColor(R.color.blocks_ink),
        brandColor(R.color.blocks_input),
        action,
    )

    private fun iconTextButton(text: String, icon: Int, action: (View) -> Unit) = styledButton(
        text,
        icon,
        Color.TRANSPARENT,
        brandColor(R.color.blocks_ink),
        Color.TRANSPARENT,
        action,
    )

    private fun styledButton(
        text: String,
        icon: Int,
        fill: Int,
        foreground: Int,
        stroke: Int,
        action: (View) -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = manropeTypeface
        setTextColor(foreground)
        background = roundedBackground(fill, stroke, 10f)
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(foreground)
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener(action)
    }

    private fun iconOnlyButton(icon: Int, description: String, action: (View) -> Unit) = Button(this).apply {
        text = ""
        contentDescription = description
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(brandColor(R.color.blocks_ink))
        background = roundedBackground(Color.TRANSPARENT, Color.TRANSPARENT, 10f)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener(action)
    }

    private fun viewToggleButton(icon: Int, description: String, selected: Boolean, action: (View) -> Unit) =
        Button(this).apply {
            text = ""
            contentDescription = description
            isSelected = selected
            ViewCompat.setStateDescription(this, if (selected) "Selected" else "Not selected")
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(
                if (selected) brandColor(R.color.blocks_white) else brandColor(R.color.blocks_ink),
            )
            background = roundedBackground(
                if (selected) brandColor(R.color.blocks_teal) else brandColor(R.color.blocks_white),
                brandColor(R.color.blocks_input),
                9f,
            )
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener(action)
        }

    private fun loadCover(image: ImageView, cover: ProjectCover, thumbnail: Boolean) {
        val tenantId = controller.activeTenantId ?: return
        if (cover.tenantId != tenantId) return
        val cacheKey = "$tenantId:${cover.coverId}:${cover.revision}:${if (thumbnail) "thumb" else "display"}"
        coverBitmaps.get(cacheKey)?.let {
            if (tenantId != controller.activeTenantId || cover.tenantId != tenantId) return
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            image.setImageBitmap(it)
            return
        }
        val generation = renderGeneration
        val job = uiScope.launch {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = controller.readProjectCoverContent(cover, thumbnail)
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }.getOrNull() ?: return@launch
            if (
                generation != renderGeneration ||
                tenantId != controller.activeTenantId ||
                cover.tenantId != tenantId
            ) {
                bitmap.recycle()
                return@launch
            }
            coverBitmaps.put(cacheKey, bitmap)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            image.setImageBitmap(bitmap)
        }
        coverJobs += job
    }

    private fun clearCoverMemory() {
        dashboardSearchQuery = ""
        coverJobs.forEach { it.cancel() }
        coverJobs.clear()
        coverBitmaps.evictAll()
        val capture = pendingCameraCover
        pendingCameraCover = null
        pendingCoverProjectId = null
        uiScope.launch(Dispatchers.IO) { runCatching { coverCaptureFiles.delete(capture) } }
    }

    internal fun coverCacheKeysForTest(): Set<String> = coverBitmaps.snapshot().keys

    private fun label(text: String, size: Float, color: Int = Color.DKGRAY) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = interTypeface
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
        minimumHeight = dp(48)
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
                        restoreTransientDraftsIfReady()
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
        if (priorTenantId != null && priorTenantId != authenticated.tenantId) clearCoverMemory()
        withContext(localStateDispatcher) {
            acceptedTenantStore.save(AcceptedTenantContinuity(authenticated.tenantId, accountIdentity))
            controller.activateAuthenticated(authenticated)
            if (priorTenantId == authenticated.tenantId) {
                restoreProjectId?.let(controller::openProject)
            }
            restoreTransientDraftsIfReady()
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
        clearCoverMemory()
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
            clearCoverMemory()
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
            clearCoverMemory()
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

    /** Form edits are deliberately memory-only; explicit buttons own persistence. */
    private fun queueDraftUpdate(action: () -> Unit) {
        action()
    }

    private fun restoreTransientDraftsIfReady() {
        val drafts = transientDraftsToRestore ?: return
        if (!controller.isAuthenticated) return
        controller.restoreTransientDrafts(drafts, transientParkingToRestore)
        transientDraftsToRestore = null
        transientParkingToRestore = false
    }

    private suspend fun handleRepositoryError(error: Exception) {
        val revokedTenantId = controller.reportRepositoryError(error)
        if (revokedTenantId != null) {
            val failingJob = currentCoroutineContext()[Job]
            syncJob?.takeIf { it !== failingJob }?.cancel()
            syncJob = null
            continuity.shouldRestore = false
            restoreProjectId = null
            clearCoverMemory()
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
        coverJobs.forEach { it.cancel() }
        coverJobs.clear()
        coverBitmaps.evictAll()
        coverCaptureFiles.delete(pendingCameraCover)
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
        private const val STATE_COVER_PROJECT_ID = "cover_project_id"
        private const val STATE_CAMERA_COVER_PATH = "camera_cover_path"
        private const val STATE_TRANSIENT_DRAFTS = "transient_form_drafts"
        private const val STATE_TRANSIENT_PARKING = "transient_parking_form"
        private const val DASHBOARD_COLLECTION_TAG = "dashboard_collection"

        @Volatile
        var dependencyFactory: ((MainActivity) -> ProjectAppDependencies)? = null
    }
}
