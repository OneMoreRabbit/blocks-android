package dev.blocks.android

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AcceptedTenantContinuityStore
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.BackgroundSyncResult
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.BlockLookup
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.RegisteredBlock
import dev.blocks.android.project.SessionAuthenticator
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidEncryptedSyncStateStore
import dev.blocks.android.state.SessionContinuity
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
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
    private lateinit var acceptedTenant: MemoryAcceptedTenantContinuityStore
    private lateinit var presentationController: ProjectPresentationController
    @Volatile
    private var sessionExchangeOffline = false
    @Volatile
    private var authenticatedTenant = TENANT_ID
    private lateinit var sessionExchanges: AtomicInteger

    @Before
    fun setUp() {
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        workspace = FakeWorkspace()
        identity = FakeIdentity()
        continuity = MemoryContinuity(true)
        acceptedTenant = MemoryAcceptedTenantContinuityStore(
            AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A),
        )
        sessionExchangeOffline = false
        authenticatedTenant = TENANT_ID
        sessionExchanges = AtomicInteger()
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            presentationController = ProjectPresentationController(
                SessionAuthenticator {
                    sessionExchanges.incrementAndGet()
                    if (sessionExchangeOffline) throw java.io.IOException("offline")
                    Session(
                        tenantId = authenticatedTenant,
                        identityToken = it,
                        accountIdentity = if (authenticatedTenant == TENANT_ID) ACCOUNT_A else ACCOUNT_B,
                    )
                },
                workspace,
                newOperationId = { "ui-operation-${operationIds.incrementAndGet()}" },
            )
            ProjectAppDependencies(
                controller = presentationController,
                identity = identity,
                continuity = continuity,
                acceptedTenantStore = acceptedTenant,
                observeConnectivity = false,
            )
        }
    }

    @After
    fun tearDown() {
        MainActivity.dependencyFactory = null
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        AndroidEncryptedSyncStateStore(context).clearSyncState(TENANT_ID)
        AndroidEncryptedAcceptedTenantContinuityStore(context).clear()
    }

    @Test
    fun authorizedRestartSilentlyOpensCachedDashboardAndReconcilesInBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { activity -> activity.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.hasButton("Sync saved changes"))
                assertFalse(activity.hasButton("Pick up this project"))
                assertFalse(
                    activity.texts().any {
                        it.contains("NFC", ignoreCase = true) ||
                            it.contains("QR", ignoreCase = true) ||
                            it.contains("project links", ignoreCase = true)
                    },
                )
            }
            waitUntil { workspace.syncCalls > 0 }
            assertEquals(1, identity.restoreCalls)
            assertEquals(0, identity.signInCalls)
        }
    }

    @Test
    fun genuineLoginUsesOneProviderResultAndOneServiceBootstrap() {
        continuity.shouldRestore = false
        acceptedTenant.clear()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            scenario.onActivity { it.button("Sign in with Google").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            assertEquals(1, identity.signInCalls)
            assertEquals(1, sessionExchanges.get())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
        }
    }

    @Test
    fun signOutLocksThenSameAccountLoginRestoresWithoutErasingLocalData() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(4)
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            assertEquals(null, acceptedTenant.load())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertTrue(workspace.clearedTenants.isEmpty())
            assertTrue(TENANT_ID in workspace.deactivatedTenants)

            scenario.onActivity { it.button("Sign in with Google").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Active project") }

            assertEquals(1, identity.signInCalls)
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun accountSwitchInBothDirectionsUsesSeparateRetainedPartitions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Active project") }

            authenticatedTenant = OTHER_TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(3)
            }
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    !it.hasButton("Active project") &&
                    it.texts().contains("No active projects yet.")
            }
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))

            authenticatedTenant = TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(3)
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Active project") }

            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertTrue(workspace.clearedTenants.isEmpty())
            assertTrue(workspace.activatedTenants.containsAll(listOf(TENANT_ID, OTHER_TENANT_ID)))
        }
    }

    @Test
    fun failedAndCancelledLoginDoNotEraseOrReplaceRetainedAccount() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(4)
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            identity.signInFailure = java.io.IOException("provider unavailable")
            scenario.onActivity { it.button("Sign in with Google").performClick() }
            waitUntil { identity.signInCalls == 1 }
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))

            identity.signInFailure = kotlinx.coroutines.CancellationException("cancelled")
            scenario.onActivity { it.button("Sign in with Google").performClick() }
            waitUntil { identity.signInCalls == 2 }
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertEquals(null, acceptedTenant.load())
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun dashboardBodyHasOneSettingsEntryInsteadOfArchiveAndAccountActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.SETTINGS) != null)
                assertFalse(activity.hasButton("Archive"))
                assertFalse(activity.hasButton("Account"))
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                val items = activity.activeDialog?.listView?.adapter ?: error("Settings menu is missing")
                assertEquals(
                    listOf("Archive", "Tile view", "About", "Switch account", "Sign out", "Remove local data"),
                    (0 until items.count).map { items.getItem(it).toString() },
                )
            }
        }
    }

    @Test
    fun systemBarInsetAddsTopSpacingAndAboutShowsOnlyReleaseIdentity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                val content = activity.findViewById<LinearLayout>(ProjectUiIds.CONTENT)
                val topInset = 37
                ViewCompat.dispatchApplyWindowInsets(
                    content,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topInset, 0, 11))
                        .build(),
                )
                val comfortableSpacing = (24 * activity.resources.displayMetrics.density).toInt()
                assertEquals(topInset + comfortableSpacing, content.paddingTop)

                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(2)
                val about = activity.dialogTexts().joinToString("\n")
                assertTrue(about.contains("Blocks Android"))
                assertTrue(about.contains("Version 0.5.1 (6)"))
                assertTrue(about.contains("dev.blocks.android/debug"))
                assertFalse(about.contains("tenant", ignoreCase = true))
                assertFalse(about.contains("token", ignoreCase = true))
                assertFalse(about.contains("http", ignoreCase = true))
                assertFalse(about.contains("sync", ignoreCase = true))
            }
        }
    }

    @Test
    fun viewPreferenceAndArchiveDashboardModeSurviveRecreation() {
        workspace.cache = workspace.cache.copy(
            archivedProjects = listOf(
                project().copy(
                    id = "00000000-0000-0000-0000-000000000013",
                    name = "Archived project",
                    lifecycle = ProjectLifecycle.ARCHIVED,
                ),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(1)
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }

            scenario.recreate()
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(0)
            }
            waitFor(scenario) { activity ->
                activity.texts().contains("Archive") && activity.hasButton("Archived project")
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT) == null)
                assertTrue(activity.findViewById<Button>(ProjectUiIds.ARCHIVE_MAIN) != null)
            }

            scenario.recreate()
            waitFor(scenario) { it.hasButton("Archived project") }
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.ARCHIVE_MAIN).performClick() }
            waitFor(scenario) { it.hasButton("Active project") }
        }
    }

    @Test
    fun dashboardCreationIsContainedAndItsDraftSurvivesUntilExplicitDiscard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(ProjectUiIds.PROJECT_NAME) == null)
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)
                    ?.setText("Dialog draft") ?: error("Create-project dialog input is missing")
                activity.dialogButton("Cancel").callOnClick()
            }

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(ProjectUiIds.PROJECT_NAME) == null)
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                assertEquals(
                    "Dialog draft",
                    activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)?.text.toString(),
                )
                activity.dialogButton("Discard draft").callOnClick()
            }
            waitUntil { presentationController.state.drafts.projectName.isEmpty() }
            assertTrue(AndroidEncryptedDraftStore(context).load(TENANT_ID).projectName.isEmpty())
        }
    }

    @Test
    fun coldOfflineRestartShowsOsSandboxContinuityBeforeSessionExchange() {
        sessionExchangeOffline = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.texts().any { it.contains("service", ignoreCase = true) })
            }
            assertEquals(0, workspace.syncCalls)

            sessionExchangeOffline = false
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil { workspace.syncCalls > 0 }
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
            }
        }
    }

    @Test
    fun expiredOrUnavailableProviderRestoreLeavesCachedWorkOpenAndQuiet() {
        identity.restoreFailure = java.io.IOException("credential unavailable")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    presentationController.state.authenticationRequired
            }
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.hasButton("Reconnect Google account"))
                assertFalse(activity.texts().any { it.contains("credential", ignoreCase = true) })
            }
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun processStyleOfflineRestartReadsRealEncryptedDatabaseAndKeystoreContinuityThenReconciles() {
        val syncStore = AndroidEncryptedSyncStateStore(context)
        syncStore.clearSyncState(TENANT_ID)
        syncStore.saveSyncState(
            TENANT_ID,
            TenantSyncState(
                projectCache = ProjectCacheState(
                    activeProjects = listOf(project()),
                    activeProjectsLoaded = true,
                    archivedProjectsLoaded = true,
                    pages = listOf(page()),
                    blocksLoaded = true,
                ),
            ),
        )
        val realContinuity = AndroidEncryptedAcceptedTenantContinuityStore(context)
        realContinuity.clear()
        realContinuity.save(AcceptedTenantContinuity(TENANT_ID))
        val service = CacheProjectService()
        val exchangeOnline = java.util.concurrent.atomic.AtomicBoolean(false)
        val exchanges = AtomicInteger()
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            val persisted = AndroidEncryptedSyncStateStore(activity)
            ProjectAppDependencies(
                controller = ProjectPresentationController(
                    SessionAuthenticator {
                        exchanges.incrementAndGet()
                        if (!exchangeOnline.get()) throw java.io.IOException("service unavailable")
                        Session(TENANT_ID, "service-session", ACCOUNT_A)
                    },
                    ProjectSyncRepository(service, NoOpLegacyQueueReplay, drafts, persisted),
                ),
                identity = identity,
                continuity = continuity,
                acceptedTenantStore = AndroidEncryptedAcceptedTenantContinuityStore(activity),
                observeConnectivity = false,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { first ->
            waitFor(first) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            first.onActivity { assertTrue(it.hasButton("Active project")) }
            waitUntil { exchanges.get() > 0 }
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { assertTrue(it.hasButton("Active project")) }
            waitUntil { exchanges.get() > 1 }
            assertEquals(0, service.changeCalls)

            exchangeOnline.set(true)
            restarted.moveToState(Lifecycle.State.CREATED)
            restarted.moveToState(Lifecycle.State.RESUMED)
            waitUntil { service.changeCalls > 0 }
            restarted.onActivity { assertTrue(it.hasButton("Active project")) }
        }
    }

    @Test
    fun emptyEncryptedCacheSupportsCreateNotesAndParkingAcrossAFullOfflineActivityRestart() {
        AndroidEncryptedSyncStateStore(context).clearSyncState(TENANT_ID)
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        val realAccepted = AndroidEncryptedAcceptedTenantContinuityStore(context).apply {
            clear()
            save(AcceptedTenantContinuity(TENANT_ID))
        }
        val service = CacheProjectService().apply { offline = true }
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            val repository = ProjectSyncRepository(
                service,
                NoOpLegacyQueueReplay,
                drafts,
                AndroidEncryptedSyncStateStore(activity),
            )
            presentationController = ProjectPresentationController(
                SessionAuthenticator { Session(TENANT_ID, it, ACCOUNT_A) },
                repository,
                newOperationId = { "offline-ui-${operationIds.incrementAndGet()}" },
            )
            ProjectAppDependencies(
                controller = presentationController,
                identity = identity,
                continuity = continuity,
                acceptedTenantStore = realAccepted,
                observeConnectivity = false,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { first ->
            waitFor(first) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            first.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(1)
            }
            waitFor(first) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            first.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)
                    ?.setText("Offline project") ?: error("Create-project input is missing")
                activity.dialogButton("Create").callOnClick()
            }
            waitFor(first) { it.hasButton("Offline project") }
            first.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.PENDING_CHANGES) != null)
                assertFalse(
                    activity.texts().any {
                        it.contains("service", ignoreCase = true) ||
                            it.contains("sync", ignoreCase = true) ||
                            it.contains("retry", ignoreCase = true)
                    },
                )
                activity.button("Offline project").performClick()
            }
            waitFor(first) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            first.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTES).setText("Offline notes")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTES).performClick()
            }
            waitUntil { presentationController.state.projectPage?.notes?.body == "Offline notes" }
            first.onActivity { it.findViewById<Button>(ProjectUiIds.PARK).performClick() }
            first.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText("Workbench")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Fit bracket")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).setText("Bring screws")
                activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING).performClick()
            }
            waitUntil { presentationController.state.projectPage?.newestParking?.location == "Workbench" }
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    it.hasButton("Offline project") &&
                    it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null
            }
            restarted.onActivity { it.button("Offline project").performClick() }
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            restarted.onActivity { activity ->
                assertEquals("Offline notes", activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString())
                assertTrue(activity.texts().contains("Workbench"))
                assertTrue(activity.texts().contains("Fit bracket"))
                assertTrue(activity.texts().contains("Bring screws"))

                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem(0)
                val rename = requireNotNull(activity.activeDialog).window?.decorView?.descendants().orEmpty()
                    .filterIsInstance<EditText>()
                    .single()
                rename.setText("Renamed offline project")
                activity.dialogButton("Rename").callOnClick()
            }
            waitUntil {
                presentationController.state.projectPage?.project?.name == "Renamed offline project"
            }
            waitFor(restarted) { it.texts().contains("Renamed offline project") }
            restarted.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem(1)
                activity.dialogButton("Archive").callOnClick()
            }
            waitFor(restarted) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && !it.hasButton("Renamed offline project")
            }
            restarted.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(0)
            }
            waitFor(restarted) { it.hasButton("Renamed offline project") }

            restarted.recreate()
            waitFor(restarted) {
                it.hasButton("Renamed offline project") &&
                    it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null
            }
            restarted.onActivity { activity ->
                activity.button("Renamed offline project").performClick()
                activity.dialogButton("Reopen").callOnClick()
            }
            waitFor(restarted) {
                !it.hasButton("Renamed offline project") &&
                    it.findViewById<Button>(ProjectUiIds.ARCHIVE_MAIN) != null
            }
            restarted.onActivity { it.findViewById<Button>(ProjectUiIds.ARCHIVE_MAIN).performClick() }
            waitFor(restarted) { it.hasButton("Renamed offline project") }
            assertTrue(presentationController.state.pendingChanges)
        }

        ActivityScenario.launch(MainActivity::class.java).use { finalRestart ->
            waitFor(finalRestart) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    it.hasButton("Renamed offline project") &&
                    it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null
            }
        }
    }

    @Test
    fun backgroundServiceConfirmationOfAnotherAccountSwitchesPartitionWithoutErasingEitherAccount() {
        authenticatedTenant = OTHER_TENANT_ID

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    it.texts().contains("No active projects yet.")
            }

            assertTrue(continuity.shouldRestore)
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.load())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun confirmedMembershipRevocationErasesOnlyTheAffectedPartition() {
        acceptedTenant.save(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B))
        acceptedTenant.save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))
        workspace.membershipRevoked = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            assertEquals(null, acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))
            assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
            assertFalse(continuity.shouldRestore)
        }
    }

    @Test
    fun explicitRemoveLocalDataErasesOnlyTheSelectedTenantAfterConfirmation() {
        acceptedTenant.save(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B))
        acceptedTenant.save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem(5)
                assertTrue(activity.dialogTexts().any { it.contains("permanently removes") })
                activity.dialogButton("Remove local data").callOnClick()
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            assertEquals(null, acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))
            assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
        }
    }

    @Test
    fun unverifiedTokenClaimsCannotSelectAnotherTenantCache() {
        identity.restoredToken = "header.eyJ0ZW5hbnRfaWQiOiJvdGhlci10ZW5hbnQifQ.signature"
        sessionExchangeOffline = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity -> assertTrue(activity.hasButton("Active project")) }
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertEquals(listOf(TENANT_ID), workspace.activatedTenants.distinct())
            assertTrue(continuity.shouldRestore)
        }
    }

    @Test
    fun projectBackIsNavigationOnlyAndNeverCreatesLegacyOrProjectMutation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.MAIN) != null)
                assertTrue(activity.findViewById<Button>(ProjectUiIds.PARK) != null)
                assertTrue(activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS) != null)
                assertFalse(activity.hasButton("Back to projects"))
                assertFalse(activity.hasButton("Add park message"))
                assertFalse(activity.hasButton("Project actions"))

                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                val projectSettings = activity.activeDialog?.listView?.adapter
                    ?: error("Project Settings is missing")
                assertEquals(
                    listOf("Rename", "Archive"),
                    (0 until projectSettings.count).map { projectSettings.getItem(it).toString() },
                )
                activity.dialogButton("Cancel").callOnClick()

                activity.findViewById<Button>(ProjectUiIds.PARK).performClick()
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK_SHEET) != null)
                val fields = activity.findViewById<ViewGroup>(android.R.id.content)
                    .descendants()
                    .filterIsInstance<EditText>()
                    .map { it.id }
                    .toSet()
                assertEquals(
                    setOf(
                        ProjectUiIds.PARKING_LOCATION,
                        ProjectUiIds.PARKING_NEXT_ACTION,
                        ProjectUiIds.PARKING_MUST_NOT_FORGET,
                    ),
                    fields,
                )
                activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText("Retained place")
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
                assertTrue(activity.findViewById<Button>(ProjectUiIds.PARK) != null)
                activity.findViewById<Button>(ProjectUiIds.PARK).performClick()
                assertEquals(
                    "Retained place",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).text.toString(),
                )
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
                activity.findViewById<Button>(ProjectUiIds.MAIN).performClick()
            }
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
                activity.findViewById<Button>(ProjectUiIds.PARK).performClick()
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText("Shelf")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Open notebook")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).setText("Bring cable")
            }

            scenario.recreate()
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_PROJECT &&
                    it.findViewById<View>(ProjectUiIds.PARK_SHEET) != null
            }

            scenario.onActivity { activity ->
                assertEquals("Shelf", activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).text.toString())
                assertEquals(
                    "Open notebook",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).text.toString(),
                )
                assertEquals(
                    "Bring cable",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).text.toString(),
                )
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
                assertEquals(
                    "Draft after recreation",
                    activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString(),
                )
            }
            assertTrue(identity.restoreCalls >= 2)
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { it.button("Active project").performClick() }
            waitFor(restarted) {
                it.screenTag() == ProjectUiIds.SCREEN_PROJECT &&
                    it.findViewById<View>(ProjectUiIds.PARK_SHEET) != null
            }
            restarted.onActivity { activity ->
                assertEquals("Shelf", activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).text.toString())
                assertEquals(
                    "Open notebook",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).text.toString(),
                )
                assertEquals(
                    "Bring cable",
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).text.toString(),
                )
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
                assertEquals(
                    "Draft after recreation",
                    activity.findViewById<EditText>(ProjectUiIds.NOTES).text.toString(),
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
                assertTrue(activity.findViewById<Button>(ProjectUiIds.RESOLVE_CONFLICT) != null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PENDING_CHANGES) != null)
                assertFalse(activity.texts().any { it.contains("service", ignoreCase = true) })
                assertTrue(activity.findViewById<Button>(ProjectUiIds.RESOLVE_CONFLICT) != null)
            }

            workspace.conflict = false
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.RESOLVE_CONFLICT).performClick()
                assertTrue(activity.dialogTexts().any { it.contains("Current service value") })
                activity.dialogButton("Retry with latest").callOnClick()
            }
            waitUntil { workspace.cache.commands.isEmpty() }
        }
    }

    @Test
    fun rejectedCredentialOffersOneControlledForegroundReauthenticationJourney() {
        workspace.rejectAuthentication = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntil { presentationController.state.authenticationRequired }
            workspace.rejectAuthentication = false
            scenario.onActivity { activity ->
                assertFalse(activity.hasButton("Reconnect Google account"))
                assertTrue(activity.hasButton("Active project"))
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            waitFor(scenario) { activity ->
                activity.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    !presentationController.state.authenticationRequired
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
        error("Timed out waiting for UI state: ${presentationController.state}")
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

    private fun MainActivity.dialogButton(text: String): Button = requireNotNull(activeDialog)
        .window?.decorView?.descendants().orEmpty()
        .filterIsInstance<Button>()
        .single { it.text.toString() == text }

    private fun MainActivity.selectDialogItem(index: Int) {
        val list = requireNotNull(activeDialog).listView
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }

    private fun MainActivity.selectDialogItem(text: String) {
        val list = requireNotNull(activeDialog).listView
        val index = (0 until list.adapter.count).single { list.adapter.getItem(it).toString() == text }
        list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
    }

    private fun MainActivity.dialogTexts() = requireNotNull(activeDialog)
        .window?.decorView?.descendants().orEmpty()
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
        var clearCalls = 0
        var restoredToken = "restored-token"
        var signInFailure: Exception? = null
        var restoreFailure: Exception? = null
        override suspend fun signIn(activity: Activity): String {
            signInCalls += 1
            signInFailure?.let { throw it }
            return "identity-token"
        }
        override suspend fun restore(activity: Activity): String {
            restoreCalls += 1
            restoreFailure?.let { throw it }
            return restoredToken
        }
        override suspend fun clear(activity: Activity) {
            clearCalls += 1
        }
    }

    private data class MemoryContinuity(override var shouldRestore: Boolean) : SessionContinuity

    private class MemoryAcceptedTenantContinuityStore(initial: AcceptedTenantContinuity?) :
        AcceptedTenantContinuityStore {
        private var activeTenantId = initial?.tenantId
        private val bindings = initial?.let { mutableListOf(it) } ?: mutableListOf()

        @Synchronized
        override fun load() = bindings.firstOrNull { it.tenantId == activeTenantId }
        @Synchronized
        override fun find(accountIdentity: AccountIdentity) =
            bindings.firstOrNull { it.accountIdentity == accountIdentity }
        @Synchronized
        override fun save(continuity: AcceptedTenantContinuity) {
            bindings.removeAll { it.tenantId == continuity.tenantId }
            bindings += continuity
            activeTenantId = continuity.tenantId
        }
        @Synchronized
        override fun lock() {
            activeTenantId = null
        }
        @Synchronized
        override fun remove(tenantId: String) {
            bindings.removeAll { it.tenantId == tenantId }
            if (activeTenantId == tenantId) activeTenantId = null
        }
        @Synchronized
        override fun clear() {
            activeTenantId = null
            bindings.clear()
        }
    }

    private class FakeWorkspace : ProjectWorkspace {
        private var activeTenantId = TENANT_ID
        private val draftsByTenant = mutableMapOf<String, FormDrafts>()
        private val preferencesByTenant = mutableMapOf<String, ProjectUiPreferences>()
        private val caches = mutableMapOf(
            TENANT_ID to ProjectCacheState(
                activeProjects = listOf(project()),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(page()),
            ),
        )
        var cache: ProjectCacheState
            get() = caches.getOrPut(activeTenantId, ::emptyCache)
            set(value) {
                caches[activeTenantId] = value
            }
        @Volatile
        var offline = false
        @Volatile
        var conflict = false
        @Volatile
        var rejectAuthentication = false
        @Volatile
        var membershipRevoked = false
        @Volatile
        var syncCalls = 0
        @Volatile
        var pageCalls = 0
        val activatedTenants = mutableListOf<String>()
        val deactivatedTenants = mutableListOf<String>()
        val clearedTenants = mutableListOf<String>()
        val mutations = mutableListOf<String>()

        override fun activate(session: Session) {
            activeTenantId = session.tenantId
            activatedTenants += session.tenantId
        }
        override fun deactivate(session: Session) {
            deactivatedTenants += session.tenantId
        }
        override fun snapshot(session: Session) = result()
        override fun projectDrafts(session: Session) = draftsByTenant[session.tenantId] ?: FormDrafts()
        override fun updateProjectDrafts(
            session: Session,
            update: (FormDrafts) -> FormDrafts,
        ): FormDrafts = update(projectDrafts(session)).also { draftsByTenant[session.tenantId] = it }
        override fun uiPreferences(session: Session) =
            preferencesByTenant[session.tenantId] ?: ProjectUiPreferences()
        override fun updateUiPreferences(
            session: Session,
            update: (ProjectUiPreferences) -> ProjectUiPreferences,
        ) = update(uiPreferences(session)).also { preferencesByTenant[session.tenantId] = it }
        override fun archivedProjects(session: Session) = cache.archivedProjects
        override fun projectPage(session: Session, projectId: String): ProjectPage {
            pageCalls += 1
            return cache.pages.single()
        }
        @Synchronized
        override fun backgroundSync(session: Session): BackgroundSyncResult {
            syncCalls += 1
            if (membershipRevoked) {
                clearLocalState(session)
                throw MembershipRevokedException(
                    ServiceException(403, ApiError(ApiErrorCode.UNAUTHORIZED, "Membership revoked")),
                )
            }
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
            clientId: String,
        ) = queue(ProjectMutation(operationId, ProjectMutationType.CREATE_PROJECT, clientId, projectName = name))
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
            clientId: String,
        ) = queue(ProjectMutation(operationId, ProjectMutationType.ADD_PARKING, projectId, parking = parking))
        override fun discardConflict(session: Session, operationId: String): BackgroundSyncResult {
            cache = cache.copy(
                commands = cache.commands.filterNot { it.operationId == operationId },
                retiredOperationIds = cache.retiredOperationIds + operationId,
            )
            conflict = false
            return result()
        }
        override fun retryConflict(
            session: Session,
            operationId: String,
            newOperationId: String,
        ): BackgroundSyncResult {
            cache = cache.copy(
                commands = cache.commands.map {
                    if (it.operationId == operationId) {
                        it.copy(operationId = newOperationId, status = ProjectMutationStatus.PENDING)
                    } else {
                        it
                    }
                },
                retiredOperationIds = cache.retiredOperationIds + operationId,
            )
            conflict = false
            return backgroundSync(session)
        }
        override fun discardPendingOperation(session: Session, operationId: String): BackgroundSyncResult {
            cache = cache.copy(
                commands = cache.commands.filterNot { it.operationId == operationId },
                retiredOperationIds = cache.retiredOperationIds + operationId,
            )
            return result()
        }
        override fun clearLocalState(session: Session) {
            clearedTenants += session.tenantId
            caches.remove(session.tenantId)
            draftsByTenant.remove(session.tenantId)
            preferencesByTenant.remove(session.tenantId)
        }

        private fun emptyCache() = ProjectCacheState(
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            blocksLoaded = true,
        )

        @Synchronized
        private fun queue(command: ProjectMutation): BackgroundSyncResult {
            mutations += command.type.name
            if (cache.commands.none { it.operationId == command.operationId }) {
                cache = cache.copy(commands = cache.commands + command)
            }
            return result()
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
                dev.blocks.android.project.ProjectConflict(
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

    private class CacheProjectService : ProjectService {
        @Volatile
        var changeCalls = 0
        @Volatile
        var offline = false

        override fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary> {
            failIfOffline()
            return when (lifecycle) {
                ProjectLifecycle.ACTIVE -> listOf(project())
                ProjectLifecycle.ARCHIVED -> emptyList()
            }
        }
        override fun createProject(
            session: Session,
            name: String,
            clientId: String?,
            idempotencyKey: String,
        ): ProjectSummary {
            failIfOffline()
            return project().copy(name = name, clientId = clientId)
        }
        override fun projectPage(session: Session, projectId: String) = page()
        override fun renameProject(session: Session, projectId: String, rowVersion: Int, name: String) = error("Not used")
        override fun archiveProject(session: Session, projectId: String, rowVersion: Int) = error("Not used")
        override fun reopenProject(session: Session, projectId: String, rowVersion: Int) = error("Not used")
        override fun notes(session: Session, projectId: String) = page().notes
        override fun saveNotes(session: Session, projectId: String, rowVersion: Int, body: String) = error("Not used")
        override fun addParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            clientId: String?,
            occurredAt: String?,
            idempotencyKey: String,
        ): ProjectParking = error("Not used")
        override fun parking(session: Session, projectId: String) = emptyList<ProjectParking>()
        override fun blocks(session: Session): List<PhysicalBlock> {
            failIfOffline()
            return emptyList()
        }
        override fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock =
            error("Not used")
        override fun bindBlock(
            session: Session,
            blockId: String,
            projectId: String,
            idempotencyKey: String,
        ): BlockBinding = error("Not used")
        override fun unbindBlock(session: Session, blockId: String) = Unit
        override fun lookupBlock(session: Session, tagToken: String): BlockLookup = error("Not used")
        override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
            changeCalls += 1
            failIfOffline()
            return ChangePage(emptyList(), cursor, false)
        }

        private fun failIfOffline() {
            if (offline) throw java.io.IOException("API unavailable")
        }
    }

    companion object {
        private val operationIds = AtomicInteger()
        private const val TENANT_ID = "00000000-0000-0000-0000-000000000011"
        private const val OTHER_TENANT_ID = "00000000-0000-0000-0000-000000000099"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000012"
        private const val NOW = "2026-09-07T10:00:00Z"
        private val ACCOUNT_A = AccountIdentity("https://accounts.google.com", "account-a")
        private val ACCOUNT_B = AccountIdentity("https://accounts.google.com", "account-b")
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
