package dev.blocks.android

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import dev.blocks.android.cover.ProjectCoverImporter
import dev.blocks.android.cover.CoverCropSelection
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.CoverImportFiles
import dev.blocks.android.cover.PreparedProjectCoverCrop
import dev.blocks.android.cover.ProjectCoverCropper
import dev.blocks.android.cover.ProjectCoverCropView
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
import dev.blocks.android.project.ParkingHistoryPage
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectScreen
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.RegisteredBlock
import dev.blocks.android.project.SessionAuthenticator
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuity
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.tag.BlocksNavigationAddress
import dev.blocks.android.tag.DirectTagWritePlan
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.NdefLocatorCodec
import dev.blocks.android.tag.NfcClearResult
import dev.blocks.android.tag.NfcIoCancellation
import dev.blocks.android.tag.NfcPresentationCorrelation
import dev.blocks.android.tag.NfcProgrammingResult
import dev.blocks.android.tag.NfcTagHandle
import dev.blocks.android.tag.NfcTagPlatform
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLink
import dev.blocks.android.tag.ProjectLinkIdentity
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagLocator
import dev.blocks.android.tag.TagProgrammingFailure
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.TimeZone
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
    private lateinit var nfc: FakeNfcTagPlatform
    private lateinit var coverImporter: FakeCoverImporter
    private lateinit var coverCropper: FakeCoverCropper
    private lateinit var coverSourceActions: FakeCoverSourceActions
    private lateinit var launchedResourceLinks: MutableList<String>
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
        nfc = FakeNfcTagPlatform()
        coverImporter = FakeCoverImporter(context)
        coverCropper = FakeCoverCropper(coverImporter)
        coverSourceActions = FakeCoverSourceActions()
        launchedResourceLinks = mutableListOf()
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
                nfcTagPlatform = nfc,
                coverImporter = coverImporter,
                coverCropper = coverCropper,
                coverSourceActions = coverSourceActions,
                resourceLinkLauncher = { uri -> launchedResourceLinks += uri; true },
            )
        }
    }

    @After
    fun tearDown() {
        MainActivity.dependencyFactory = null
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        AndroidSyncStateStore(context).clearSyncState(TENANT_ID)
        AndroidEncryptedAcceptedTenantContinuityStore(context).clear()
    }

    @Test
    fun authorizedRestartSilentlyOpensCachedDashboardWithoutProviderOrServiceCalls() {
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
            assertTrue(presentationController.state.sessionConfirmationRequired)
            assertEquals(0, workspace.syncCalls)
            assertEquals(0, sessionExchanges.get())
            assertEquals(0, identity.signInCalls)
        }
    }

    @Test
    fun appLinkQrAndNfcColdStartsOpenCachedProjectWithoutIdentityCalls() {
        val appLink = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(PROJECT_LINK.encoded)
        }
        val qr = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(PROJECT_LINK.encoded)
        }
        val nfc = Intent(context, MainActivity::class.java).apply {
            action = NfcAdapter.ACTION_NDEF_DISCOVERED
            data = Uri.parse(PROJECT_LINK.encoded)
            putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefLocatorCodec.message(PROJECT_LINK)))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        listOf(appLink, qr, nfc).forEach { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val activity = instrumentation.startActivitySync(launchIntent) as MainActivity
            try {
                waitUntilOnActivity(activity) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
                instrumentation.runOnMainSync { assertTrue(activity.texts().contains("Active project")) }
            } finally {
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            }
        }

        val projectsBeforeInvalid = workspace.cache.activeProjects
        val invalidIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("blocks://tag/v2/not-supported")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val invalid = instrumentation.startActivitySync(invalidIntent) as MainActivity
        try {
            waitUntilOnActivity(invalid) { it.dialogTexts().contains("Link not recognized") }
            instrumentation.runOnMainSync {
                assertEquals(ProjectUiIds.SCREEN_DASHBOARD, invalid.screenTag())
            }
        } finally {
            instrumentation.runOnMainSync { invalid.finishAndRemoveTask() }
        }

        assertEquals(0, identity.signInCalls)
        assertEquals(0, sessionExchanges.get())
        assertEquals(0, workspace.syncCalls)
        assertEquals(projectsBeforeInvalid, workspace.cache.activeProjects)
    }

    @Test
    fun repeatedCachedStartsAndLifecycleResumeRemainLocalUntilDeliberateReconnect() {
        ActivityScenario.launch(MainActivity::class.java).use { first ->
            waitFor(first) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            first.moveToState(Lifecycle.State.CREATED)
            first.moveToState(Lifecycle.State.RESUMED)
            first.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.hasDescription("Saved on this device. Reconnect in Settings to synchronize."))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { assertTrue(it.hasButton("Active project")) }
        }
        assertEquals(0, identity.signInCalls)
        assertEquals(0, sessionExchanges.get())
        assertEquals(0, workspace.syncCalls)
        assertTrue(presentationController.state.sessionConfirmationRequired)
    }

    @Test
    fun explicitSignOutSuppressesAutomaticIdentityCallsAcrossRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Sign out")
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            assertEquals(0, identity.signInCalls)
        }
    }

    @Test
    fun deliberateReconnectValidatesTheRetainedIdentityThenSynchronizes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            assertEquals(0, workspace.syncCalls)
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            waitUntil { workspace.syncCalls > 0 }
            assertEquals(1, identity.signInCalls)
            assertEquals(1, sessionExchanges.get())
            assertFalse(presentationController.state.sessionConfirmationRequired)
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
    fun verifiedProjectLinkWriteKeepsReaderThroughIoAndColdViewIntentOpensOffline() {
        lateinit var link: ProjectLink
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.button("Active project").performClick()
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            assertTrue(workspace.cache.localBlocks.isEmpty())
            nfc.tap()
            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("written and verified") } }
            link = workspace.projectLink(SESSION, PROJECT_ID)
            assertTrue(nfc.readerWasActiveDuringProgram)
            assertTrue(nfc.readerActive)
            assertTrue(workspace.cache.localBlocks.isEmpty())
            assertTrue(workspace.cache.localBlockOperations.isEmpty())
            scenario.onActivity { it.dialogButton("Done").performClick() }
            waitUntil { !nfc.readerActive }
        }

        workspace.offline = true
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link.encoded)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val relaunched = instrumentation.startActivitySync(launchIntent) as MainActivity
        try {
            waitUntilOnActivity(relaunched) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            instrumentation.runOnMainSync {
                assertTrue(relaunched.texts().contains("Active project"))
                assertEquals(null, relaunched.intent.data)
            }
            assertEquals(0, workspace.syncCalls)
        } finally {
            instrumentation.runOnMainSync { relaunched.finishAndRemoveTask() }
        }
    }

    @Test
    fun warmOfflineProjectLinkUsesOnlyCurrentTenantCacheAndLegacyOrBadValuesStayContained() {
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 13).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "warm-block",
                    token = token,
                    label = "Active project",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        workspace.offline = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.handleIncomingTagIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(workspace.projectLink(SESSION, PROJECT_ID).encoded)),
                )
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.MAIN).performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                activity.handleIncomingTagIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(TagLocator.create(token).encoded)),
                )
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.MAIN).performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                activity.handleIncomingTagIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("blocks://tag/v2/not-supported")),
                )
            }
            waitFor(scenario) { it.dialogTexts().contains("Link not recognized") }
            scenario.onActivity { activity ->
                assertEquals(ProjectUiIds.SCREEN_DASHBOARD, activity.screenTag())
                activity.dialogButton("Done").performClick()
                val unknown = ProjectLink.create(
                    ProjectKey.parse("00000000-0000-0000-0000-000000000099")!!,
                )
                activity.handleIncomingTagIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(unknown.encoded)),
                )
            }
            waitFor(scenario) { it.dialogTexts().contains("Project not available") }
            assertEquals(0, workspace.syncCalls)
            scenario.onActivity { it.dialogButton("Done").performClick() }
        }
    }

    @Test
    fun viewQrUsesTheStableProjectLinkWithoutCreatingATagAndNeverShowsTheRawKey() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity -> activity.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("View QR")
            }
            waitFor(scenario) { activity ->
                activity.activeDialog?.findViewById<android.widget.ImageView>(ProjectUiIds.TAG_QR) != null
            }
            scenario.onActivity { activity ->
                assertTrue(requireNotNull(activity.activeDialog).findViewById<android.widget.ImageView>(ProjectUiIds.TAG_QR) != null)
                assertFalse(activity.dialogTexts().any { it.contains(PROJECT_KEY.encoded) })
                assertTrue(activity.dialogButton("Share").isShown)
            }
            assertTrue(workspace.cache.localBlocks.isEmpty())
        }
    }

    @Test
    fun viewQrWorksWithoutNfcHardwareOrAnActiveTag() {
        nfc.available = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("View QR")
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.TAG_QR) != null }
            scenario.onActivity { activity ->
                assertTrue(activity.dialogButton("Share").isShown)
            }
            assertTrue(workspace.cache.localBlocks.isEmpty())
        }
    }

    @Test
    fun duplicateCaptureFailureAndCancellationDoNotCreateAssociationsOrSecondWrites() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            scenario.onActivity { it.dialogButton("Cancel").performClick() }
            assertTrue(workspace.cache.localBlocks.isEmpty())
            assertEquals(0, nfc.programCalls)

            nfc.result = NfcProgrammingResult.WriteUncertain
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            nfc.tap()
            waitFor(scenario) { it.dialogTexts().contains("Tag not set") }
            assertTrue(workspace.cache.localBlocks.isEmpty())
            assertEquals(1, nfc.programCalls)

            nfc.result = NfcProgrammingResult.Verified()
            scenario.onActivity { it.dialogButton("Done").performClick() }
            waitUntil { !nfc.readerActive }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("written and verified") } }
            assertEquals(2, nfc.programCalls)
            assertEquals(listOf(PROJECT_LINK, PROJECT_LINK), nfc.programmedAddresses)
            assertTrue(workspace.cache.localBlocks.isEmpty())
        }
    }

    @Test
    fun cancelAfterCaptureDuringQueuedPreMutationIoPreventsPhysicalWrite() {
        nfc.programEntered = CountDownLatch(1)
        nfc.allowProgram = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            assertTrue(nfc.programEntered!!.await(2, TimeUnit.SECONDS))

            scenario.onActivity { it.dialogButton("Cancel").performClick() }
            waitUntil { !nfc.readerActive }

            assertEquals(1, nfc.programInvocations)
            assertEquals(0, nfc.programCalls)
            assertTrue(workspace.cache.localBlocks.isEmpty())
            assertTrue(workspace.cache.localBlockOperations.isEmpty())
        }
    }

    @Test
    fun formatableTagKeepsReaderActiveUntilSecondPresentationReadBack() {
        nfc.result = NfcProgrammingResult.AwaitingReadBack(TAG_A_CORRELATION)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }

            nfc.tap()
            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("formatted") } }
            assertTrue(nfc.readerActive)
            assertEquals(1, nfc.programCalls)

            nfc.result = NfcProgrammingResult.Verified()
            nfc.tap(TAG_B_CORRELATION)
            Thread.sleep(100)
            assertEquals(0, nfc.verifyWriteCalls)
            assertTrue(nfc.readerActive)

            nfc.tap()
            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("written and verified") } }
            assertEquals(1, nfc.programCalls)
            assertEquals(1, nfc.verifyWriteCalls)
            assertTrue(nfc.readerActive)
            scenario.onActivity { it.dialogButton("Done").performClick() }
            waitUntil { !nfc.readerActive }
        }
    }

    @Test
    fun losingForegroundDuringIoDiscardsTheDelayedResultWithoutLocalMutation() {
        val legacyToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 93).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "foreground-loss-block",
                    token = legacyToken,
                    label = "Legacy",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        nfc.result = NfcProgrammingResult.Verified(TagLocator.create(legacyToken))
        nfc.programEntered = CountDownLatch(1)
        nfc.allowProgram = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            assertTrue(nfc.programEntered!!.await(2, TimeUnit.SECONDS))

            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(nfc.readerActive)
            waitUntil { nfc.programInvocations == 1 }
            Thread.sleep(100)

            assertEquals(0, nfc.programCalls)
            assertEquals(LocalBlockState.ACTIVE, workspace.cache.localBlocks.single().state)
            assertTrue(workspace.cache.localBlockOperations.isEmpty())
        }
    }

    @Test
    fun logicalProjectRemapKeepsAttemptValidAndRetiresLegacyOnlyInCapturedTenant() {
        val legacyToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 41).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "remap-legacy-block",
                    token = legacyToken,
                    label = "Legacy",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        nfc.result = NfcProgrammingResult.Verified(TagLocator.create(legacyToken))
        nfc.programEntered = CountDownLatch(1)
        nfc.allowProgram = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            assertTrue(nfc.programEntered!!.await(2, TimeUnit.SECONDS))

            workspace.remapCurrentProject(CANONICAL_PROJECT_ID)
            presentationController.synchronize()
            nfc.allowProgram!!.countDown()

            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("written and verified") } }
            assertEquals(CANONICAL_PROJECT_ID, presentationController.state.projectPage?.project?.id)
            assertEquals(LocalBlockState.UNBOUND, workspace.cacheFor(TENANT_ID).localBlocks.single().state)
            assertEquals(
                CANONICAL_PROJECT_ID,
                workspace.cacheFor(TENANT_ID).projectLinks.single().projectId,
            )
        }
    }

    @Test
    fun tenantChangeBeforeMutationCancelsAttemptAndCannotRetireLegacyInReplacementRealm() {
        val legacyToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 51).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "scope-change-legacy-block",
                    token = legacyToken,
                    label = "Legacy",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        nfc.result = NfcProgrammingResult.Verified(TagLocator.create(legacyToken))
        nfc.programEntered = CountDownLatch(1)
        nfc.allowProgram = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            assertTrue(nfc.programEntered!!.await(2, TimeUnit.SECONDS))

            presentationController.activateAuthenticated(Session(OTHER_TENANT_ID, "other", ACCOUNT_B))
            nfc.allowProgram!!.countDown()
            waitUntil { !nfc.readerActive }

            assertEquals(0, nfc.programCalls)
            assertEquals(LocalBlockState.ACTIVE, workspace.cacheFor(TENANT_ID).localBlocks.single().state)
            assertTrue(workspace.cacheFor(TENANT_ID).localBlockOperations.isEmpty())
            assertTrue(workspace.cacheFor(OTHER_TENANT_ID).localBlockOperations.isEmpty())
        }
    }

    @Test
    fun projectChangeBeforeMutationCancelsAttemptAndCannotRetireLegacy() {
        val legacyToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 61).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "project-change-legacy-block",
                    token = legacyToken,
                    label = "Legacy",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        workspace.addProject(OTHER_PROJECT_ID, OTHER_PROJECT_KEY, "Other project")
        nfc.result = NfcProgrammingResult.Verified(TagLocator.create(legacyToken))
        nfc.programEntered = CountDownLatch(1)
        nfc.allowProgram = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            assertTrue(nfc.programEntered!!.await(2, TimeUnit.SECONDS))

            presentationController.openProject(OTHER_PROJECT_ID)
            nfc.allowProgram!!.countDown()
            waitUntil { !nfc.readerActive }

            assertEquals(0, nfc.programCalls)
            assertEquals(LocalBlockState.ACTIVE, workspace.cache.localBlocks.single().state)
            assertTrue(workspace.cache.localBlockOperations.isEmpty())
            assertEquals(OTHER_PROJECT_ID, presentationController.state.projectPage?.project?.id)
        }
    }

    @Test
    fun overwritingOrClearingAProjectLinkOnlyRetiresAnExplicitLegacyAssociation() {
        val legacyToken = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 77).toByte() })
        workspace.cache = workspace.cache.copy(
            localBlocks = listOf(
                LocalBlockRecord(
                    clientBlockId = "legacy-block",
                    token = legacyToken,
                    label = "Legacy",
                    state = LocalBlockState.ACTIVE,
                    intendedProjectId = PROJECT_ID,
                    boundProjectId = PROJECT_ID,
                    createdAt = NOW,
                    verifiedAt = NOW,
                    activationMedium = TagActivationMedium.NFC,
                ),
            ),
        )
        nfc.result = NfcProgrammingResult.Verified(TagLocator.create(legacyToken))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Set Tag")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            waitUntil { workspace.cache.localBlocks.single().state == LocalBlockState.UNBOUND }

            nfc.clearResult = NfcClearResult.ReadOnly
            scenario.onActivity { it.dialogButton("Done").performClick() }
            waitUntil { !nfc.readerActive }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Clear Tags")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            waitFor(scenario) { it.dialogTexts().contains("Tag not cleared") }
            assertEquals(LocalBlockState.UNBOUND, workspace.cache.localBlocks.single().state)

            nfc.clearResult = NfcClearResult.Verified(PROJECT_LINK)
            scenario.onActivity { it.dialogButton("Done").performClick() }
            waitUntil { !nfc.readerActive }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Clear Tags")
            }
            waitUntil { nfc.readerActive }
            nfc.tap()
            waitFor(scenario) { it.dialogTexts().any { text -> text.contains("erased and verified") } }
            assertEquals(PROJECT_LINK, workspace.projectLink(SESSION, PROJECT_ID))
            assertEquals(LocalBlockState.UNBOUND, workspace.cache.localBlocks.single().state)
        }
    }

    @Test
    fun signOutLocksThenSameAccountLoginRestoresWithoutErasingLocalData() {
        workspace.cache = workspace.cache.copy(projectCovers = listOf(coverImporter.metadata(PROJECT_ID)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Sign out")
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
            assertEquals("1".repeat(64), presentationController.state.projectCovers.single().contentDigest)
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun accountSwitchInBothDirectionsUsesSeparateRetainedPartitions() {
        workspace.cache = workspace.cache.copy(projectCovers = listOf(coverImporter.metadata(PROJECT_ID)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Active project") }

            authenticatedTenant = OTHER_TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    !it.hasButton("Active project") &&
                    it.texts().contains("Your first project starts here.")
            }
            assertTrue(presentationController.state.projectCovers.isEmpty())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))

            authenticatedTenant = TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Active project") }

            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertEquals("1".repeat(64), presentationController.state.projectCovers.single().contentDigest)
            assertTrue(workspace.clearedTenants.isEmpty())
            assertTrue(workspace.activatedTenants.containsAll(listOf(TENANT_ID, OTHER_TENANT_ID)))
        }
    }

    @Test
    fun accountSwitchCannotReuseSameDigestOrInflightBitmapAcrossTenants() {
        val bytes = coverImporter.imageBytes()
        val tenantACover = coverImporter.metadata(PROJECT_ID, '7')
        val tenantBCoverId = "223e4567-e89b-42d3-a456-426614174002"
        val tenantBCover = coverImporter.metadata(OTHER_PROJECT_ID, '7').copy(
            coverId = tenantBCoverId,
            tenantId = OTHER_TENANT_ID,
            projectId = OTHER_PROJECT_ID,
            displayContentRef = "$tenantBCoverId-r1-display.enc",
            thumbnailContentRef = "$tenantBCoverId-r1-thumbnail.enc",
        )
        workspace.seedTenantCover(TENANT_ID, PROJECT_ID, PROJECT_KEY, "Active project", tenantACover, bytes)
        workspace.seedTenantCover(
            OTHER_TENANT_ID,
            OTHER_PROJECT_ID,
            OTHER_PROJECT_KEY,
            "Tenant B project",
            tenantBCover,
            bytes,
        )
        bytes.fill(0)
        workspace.blockCoverReadTenant = TENANT_ID

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            assertTrue(workspace.coverReadEntered.await(2, TimeUnit.SECONDS))
            authenticatedTenant = OTHER_TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) {
                presentationController.activeTenantId == OTHER_TENANT_ID && it.hasButton("Tenant B project")
            }
            workspace.allowCoverRead.countDown()
            waitFor(scenario) { activity ->
                activity.coverCacheKeysForTest().any { it.startsWith("$OTHER_TENANT_ID:") }
            }
            scenario.onActivity { activity ->
                val keys = activity.coverCacheKeysForTest()
                assertTrue(keys.none { it.startsWith("$TENANT_ID:") })
                assertTrue(keys.all { it.startsWith("$OTHER_TENANT_ID:") })
            }
        }
    }

    @Test
    fun failedAndCancelledLoginDoNotEraseOrReplaceRetainedAccount() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Sign out")
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
    fun cancelledAccountSwitchRetainsPendingWorkAcrossProcessStyleRestart() {
        val retainedOperation = ProjectMutation(
            operationId = "retained-operation",
            type = ProjectMutationType.RENAME_PROJECT,
            targetId = PROJECT_ID,
            projectName = "Retained project name",
        )
        workspace.cache = workspace.cache.copy(commands = listOf(retainedOperation))
        workspace.offline = true
        identity.signInFailure = kotlinx.coroutines.CancellationException("cancelled")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            assertEquals(1, identity.signInCalls)
            assertEquals(null, acceptedTenant.load())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.find(ACCOUNT_A))
            assertEquals(listOf(retainedOperation), workspace.cacheFor(TENANT_ID).commands)
            assertTrue(workspace.clearedTenants.isEmpty())

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            assertEquals(listOf(retainedOperation), workspace.cacheFor(TENANT_ID).commands)

            identity.signInFailure = null
            scenario.onActivity { it.button("Sign in with Google").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            assertEquals(listOf(retainedOperation), workspace.cacheFor(TENANT_ID).commands)
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun dashboardUsesContainedArchiveActionAndOneAccountSettingsEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.SETTINGS) != null)
                assertTrue(activity.hasButton("Archive"))
                assertFalse(activity.hasButton("Account"))
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                val items = activity.activeDialog?.listView?.adapter ?: error("Settings menu is missing")
                assertEquals(
                    listOf(
                        "About",
                        "Reconnect Google account",
                        "Switch account",
                        "Sign out",
                        "Remove local data",
                    ),
                    (0 until items.count).map { items.getItem(it).toString() },
                )
            }
        }
    }

    @Test
    fun dashboardAndProjectRenderTheSameCentredCogWithFortyEightDpTouchTargets() {
        var dashboardGlyph: IntArray? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                val settings = activity.findViewById<Button>(ProjectUiIds.SETTINGS)
                val minimumTouch = (48 * activity.resources.displayMetrics.density).toInt()
                assertTrue(settings.minimumWidth >= minimumTouch)
                assertTrue(settings.minimumHeight >= minimumTouch)
                dashboardGlyph = renderAlpha(settings.compoundDrawablesRelative[0])
                assertCentredCog(requireNotNull(dashboardGlyph))
                activity.button("Active project").performClick()
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                val settings = activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS)
                val minimumTouch = (48 * activity.resources.displayMetrics.density).toInt()
                assertTrue(settings.minimumWidth >= minimumTouch)
                assertTrue(settings.minimumHeight >= minimumTouch)
                val projectGlyph = renderAlpha(settings.compoundDrawablesRelative[0])
                assertCentredCog(projectGlyph)
                assertTrue(requireNotNull(dashboardGlyph).contentEquals(projectGlyph))
            }
        }
    }

    @Test
    fun notesAndParkHistoryHaveDistinctSemanticSectionsAndOrdering() {
        workspace.cache = workspace.cache.copy(
            noteItems = listOf(
                ProjectNoteItem(
                    "10000000-0000-4000-8000-000000000001",
                    PROJECT_ID,
                    "Older note edited later",
                    false,
                    "local",
                    "2026-09-16T09:00:00Z",
                    "2026-09-16T15:00:00Z",
                ),
                ProjectNoteItem(
                    "20000000-0000-4000-8000-000000000002",
                    PROJECT_ID,
                    "Newest note",
                    false,
                    "local",
                    "2026-09-16T14:00:00Z",
                    "2026-09-16T14:00:00Z",
                ),
            ),
            parkingRecords = listOf(
                ProjectParking(
                    "30000000-0000-4000-8000-000000000003",
                    PROJECT_ID,
                    "Middle parking record",
                    "Continue",
                    "Remember",
                    "local",
                    "2026-09-16T12:00:00Z",
                ),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(ProjectUiIds.CONTENT)
                val notesHeading = content.descendants().filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == "Notes" }
                val parkHeading = content.descendants().filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == "Park History" }
                val notesSection = notesHeading.parent as ViewGroup
                val parkSection = parkHeading.parent as ViewGroup
                assertTrue(descendantText(notesSection).contains("Older note edited later"))
                assertTrue(descendantText(notesSection).contains("Newest note"))
                assertFalse(descendantText(notesSection).contains("Middle parking record"))
                assertTrue(descendantText(parkSection).contains("Middle parking record"))
                assertFalse(descendantText(parkSection).contains("Older note edited later"))
                assertTrue(content.indexOfChild(notesSection) < content.indexOfChild(parkSection))
            }
        }
    }

    @Test
    fun compactProjectPageHasTheFixedHierarchyDenseLinksAndDeduplicatedTimeline() {
        val newestPark = ProjectParking(
            "newest-park",
            PROJECT_ID,
            "Newest park",
            "Resume newest",
            "Newest reminder",
            "local",
            "2026-09-17T16:00:00Z",
        )
        val olderPark = ProjectParking(
            "older-park",
            PROJECT_ID,
            "Older park",
            "Resume older",
            "Older reminder",
            "local",
            "2026-09-17T14:00:00Z",
        )
        workspace.cache = workspace.cache.copy(
            pages = listOf(page().copy(newestParking = newestPark)),
            parkingRecords = listOf(olderPark, newestPark),
            noteItems = listOf(
                ProjectNoteItem("pinned", PROJECT_ID, "Pinned context", true, "local",
                    "2026-09-17T10:00:00Z", "2026-09-17T17:00:00Z"),
                ProjectNoteItem("later", PROJECT_ID, "Later edited note", false, "local",
                    "2026-09-17T09:00:00Z", "2026-09-17T15:00:00Z"),
                ProjectNoteItem("earlier", PROJECT_ID, "Earlier note", false, "local",
                    "2026-09-17T09:00:00Z", "2026-09-17T13:00:00Z"),
            ),
            resourceLinks = listOf(
                ProjectResourceLink(
                    "link-id",
                    PROJECT_ID,
                    "Build notes",
                    "https://example.com/private-path",
                    NOW,
                    NOW,
                ),
            ),
        )

        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
                scenario.onActivity { it.button("Active project").performClick() }
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
                waitFor(scenario) {
                    it.findViewById<View>(ProjectUiIds.PROJECT_SETTINGS).width > 0 &&
                        it.findViewById<View>(ProjectUiIds.CONTENT).width > 0
                }
                scenario.onActivity { activity ->
                val content = activity.findViewById<LinearLayout>(ProjectUiIds.CONTENT)
                val hierarchy = listOf(
                    ProjectUiIds.PROJECT_TITLE,
                    ProjectUiIds.PROJECT_COVER,
                    ProjectUiIds.NEWEST_PARKING,
                    ProjectUiIds.LINKS_SECTION,
                    ProjectUiIds.NOTES_SECTION,
                    ProjectUiIds.PARK_HISTORY_SECTION,
                ).map { id -> content.indexOfChild(activity.findViewById(id)) }
                assertEquals((0..5).toList(), hierarchy)

                val newest = activity.findViewById<ViewGroup>(ProjectUiIds.NEWEST_PARKING)
                val notes = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_NOTES)
                val history = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                assertTrue(newest.background == null)
                assertFalse(descendantText(newest).contains("Where you left off"))
                assertTrue(descendantText(newest).contains("Newest park"))
                assertTrue(descendantText(newest).contains("17/09/26 16:00"))
                assertTrue(descendantText(notes).contains("Pinned context"))
                assertTrue(descendantText(notes).contains("Later edited note"))
                assertTrue(descendantText(notes).contains("Earlier note"))
                assertTrue(descendantText(notes).contains("17/09/26 17:00"))
                assertFalse(descendantText(history).contains("Newest park"))
                assertFalse(descendantText(history).contains("Pinned context"))
                assertEquals(3, notes.childCount)
                assertTrue(descendantText(notes.getChildAt(0)).contains("Pinned context"))
                assertTrue(descendantText(notes.getChildAt(1)).contains("Later edited note"))
                assertTrue(descendantText(notes.getChildAt(2)).contains("Earlier note"))
                assertEquals(1, history.childCount)
                assertTrue(descendantText(history.getChildAt(0)).contains("Older park"))
                assertTrue(descendantText(history.getChildAt(0)).contains("17/09/26 14:00"))
                assertFalse(descendantText(content).contains("2026-09-17T"))

                val newestDate = newest.descendants().filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == "17/09/26 16:00" }
                val pinnedDate = notes.descendants().filterIsInstance<android.widget.TextView>()
                    .single { it.text.toString() == "17/09/26 17:00" }
                val compactDatePadding = (4 * activity.resources.displayMetrics.density).toInt()
                assertEquals(0, newestDate.paddingTop)
                assertTrue(newestDate.paddingBottom <= compactDatePadding)
                assertEquals(0, pinnedDate.paddingTop)
                assertTrue(pinnedDate.paddingBottom <= compactDatePadding)

                val linkRow = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_LINKS).getChildAt(0) as ViewGroup
                assertTrue(linkRow.background == null)
                val dragHandle = linkRow.getChildAt(0) as Button
                assertEquals("Reorder Build notes. Long press and drag.", dragHandle.contentDescription.toString())
                assertTrue(
                    renderAlpha(requireNotNull(dragHandle.compoundDrawablesRelative[0])).contentEquals(
                        renderAlpha(requireNotNull(activity.getDrawable(R.drawable.ic_link))),
                    ),
                )
                val title = linkRow.getChildAt(1) as Button
                val minimumTouch = (48 * activity.resources.displayMetrics.density).toInt()
                assertEquals("Build notes", title.text.toString())
                assertFalse(descendantText(linkRow).contains("https://"))
                assertTrue(dragHandle.minimumHeight >= minimumTouch)
                assertTrue(title.minimumHeight >= minimumTouch)
                assertTrue(linkRow.getChildAt(2).minimumHeight >= minimumTouch)
                assertTrue(linkRow.getChildAt(3).minimumHeight >= minimumTouch)

                val settings = activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS)
                val topBar = settings.parent as LinearLayout
                assertEquals(topBar.childCount - 1, topBar.indexOfChild(settings))
                val spacer = (0 until topBar.childCount).map(topBar::getChildAt).single {
                    (it.layoutParams as LinearLayout.LayoutParams).weight > 0f
                }
                assertTrue((spacer.layoutParams as LinearLayout.LayoutParams).weight > 0f)
                val settingsLocation = IntArray(2).also(settings::getLocationOnScreen)
                val contentLocation = IntArray(2).also(content::getLocationOnScreen)
                val contentRightEdge = contentLocation[0] + content.width - content.paddingRight
                assertEquals(contentRightEdge, settingsLocation[0] + settings.width)
                assertEquals((48 * activity.resources.displayMetrics.density).toInt(), settings.width)

                linkRow.descendants().filterIsInstance<Button>()
                    .single { it.contentDescription == "Edit link" }.performClick()
                assertTrue(launchedResourceLinks.isEmpty())
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
                val restoredRow = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_LINKS)
                    .getChildAt(0) as ViewGroup
                restoredRow.descendants().filterIsInstance<Button>()
                    .single { it.contentDescription == "Delete link" }.performClick()
                assertTrue(launchedResourceLinks.isEmpty())
                activity.dialogButton("Cancel").performClick()
                restoredRow.descendants().filterIsInstance<Button>()
                    .single { it.contentDescription == "Open Build notes" }.performClick()
                assertEquals(listOf("https://example.com/private-path"), launchedResourceLinks)
                }

                scenario.recreate()
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
                scenario.onActivity { activity ->
                    assertTrue(descendantText(activity.findViewById(ProjectUiIds.NEWEST_PARKING)).contains("Newest park"))
                    assertFalse(descendantText(activity.findViewById(ProjectUiIds.PARKING_HISTORY)).contains("Newest park"))
                    assertTrue(descendantText(activity.findViewById(ProjectUiIds.PROJECT_NOTES)).contains("Pinned context"))
                }
            }
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun compactProjectPageOmitsEmptyLinksAndPinnedSectionsWithoutReplacementSpacing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(descendantText(activity.findViewById(ProjectUiIds.NEWEST_PARKING))
                    .contains("No park message yet."))
                assertFalse(descendantText(activity.findViewById(ProjectUiIds.NEWEST_PARKING))
                    .contains("Where you left off"))
                assertTrue(activity.findViewById<View>(ProjectUiIds.LINKS_SECTION) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PROJECT_LINKS) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.NOTES_SECTION) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PROJECT_NOTES) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK_HISTORY_SECTION) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARKING_HISTORY) == null)
                assertFalse(activity.texts().contains("Notes"))
                assertFalse(activity.texts().contains("Park History"))
                val content = activity.findViewById<LinearLayout>(ProjectUiIds.CONTENT)
                val visibleHierarchy = listOf(
                    ProjectUiIds.PROJECT_TITLE,
                    ProjectUiIds.PROJECT_COVER,
                    ProjectUiIds.NEWEST_PARKING,
                ).map { id -> content.indexOfChild(activity.findViewById(id)) }
                assertEquals((0..2).toList(), visibleHierarchy)
            }
        }
    }

    @Test
    fun notesOnlyAndParksOnlyProjectsHideTheUnusedSection() {
        workspace.cache = workspace.cache.copy(
            noteItems = listOf(
                ProjectNoteItem(
                    "note-only",
                    PROJECT_ID,
                    "Only note",
                    false,
                    "local",
                    NOW,
                    NOW,
                ),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.NOTES_SECTION) != null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK_HISTORY_SECTION) == null)
                assertTrue(activity.texts().contains("Notes"))
                assertFalse(activity.texts().contains("Park History"))
            }
        }

        val older = parking(1)
        val newest = parking(2)
        workspace.cache = workspace.cache.copy(
            pages = listOf(page().copy(newestParking = newest)),
            noteItems = emptyList(),
            parkingRecords = listOf(older, newest),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.NOTES_SECTION) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK_HISTORY_SECTION) != null)
                assertFalse(activity.texts().contains("Notes"))
                assertTrue(activity.texts().contains("Park History"))
                assertTrue(descendantText(activity.findViewById(ProjectUiIds.PARKING_HISTORY)).contains("Place 1"))
                assertFalse(descendantText(activity.findViewById(ProjectUiIds.PARKING_HISTORY)).contains("Place 2"))
            }
        }
    }

    @Test
    fun systemBarInsetAddsTopSpacingAndAboutShowsOnlyReleaseIdentity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                val root = activity.findViewById<LinearLayout>(ProjectUiIds.ROOT_CONTAINER)
                val topInset = 37
                ViewCompat.dispatchApplyWindowInsets(
                    root,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topInset, 0, 11))
                        .build(),
                )
                val topNavigation = activity.findViewById<View>(ProjectUiIds.TOP_NAVIGATION)
                val compactSpacing = (8 * activity.resources.displayMetrics.density).toInt()
                assertEquals(topInset + compactSpacing, topNavigation.paddingTop)

                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("About")
                val about = activity.dialogTexts().joinToString("\n")
                assertTrue(about.contains("Blocks Android"))
                assertTrue(about.contains("Version 0.9.5 (22)"))
                assertTrue(about.contains("dev.blocks.android/debug"))
                assertFalse(about.contains("tenant", ignoreCase = true))
                assertFalse(about.contains("token", ignoreCase = true))
                assertFalse(about.contains("http", ignoreCase = true))
                assertFalse(about.contains("sync", ignoreCase = true))
            }
        }
    }

    @Test
    fun rootAndCollectionsUseTheFullPortraitLandscapeAndWideViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("wm size 1000x1600")
        shell("wm density 160")
        try {
            workspace.cache = workspace.cache.copy(
                activeProjects = (0 until 12).map { index ->
                    project().copy(id = "project-$index", name = "Project $index")
                },
            )
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
                waitFor(scenario) { it.findViewById<GridLayout>(ProjectUiIds.PROJECT_TILES) != null }
                waitFor(scenario) { it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                scenario.onActivity(::assertResponsiveDashboard)

                shell("wm size 1600x1000")
                waitFor(scenario) {
                    it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        it.resources.configuration.screenWidthDp >= 600 &&
                        it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                        it.findViewById<GridLayout>(ProjectUiIds.PROJECT_TILES) != null
                }
                scenario.onActivity { activity ->
                    assertResponsiveDashboard(activity)
                    val grid = activity.findViewById<GridLayout>(ProjectUiIds.PROJECT_TILES)
                    assertTrue(grid.columnCount >= 3)
                }
            }
        } finally {
            shell("wm size reset")
            shell("wm density reset")
            instrumentation.waitForIdleSync()
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
                activity.findViewById<Button>(ProjectUiIds.VIEW_LIST).performClick()
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }

            scenario.recreate()
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }
            scenario.onActivity { activity ->
                activity.button("Archive").performClick()
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
    fun dashboardCreationHasOnlyCreateAndCancelAndCancelDiscardsMemoryDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(ProjectUiIds.PROJECT_NAME) == null)
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)
                    ?.setText("Dialog draft") ?: error("Create-project dialog input is missing")
                assertEquals(setOf("Cancel", "Create"), activity.dialogActionLabels())
                activity.dialogButton("Cancel").callOnClick()
            }

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<EditText>(ProjectUiIds.PROJECT_NAME) == null)
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                assertEquals("", activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)?.text.toString())
                assertFalse(activity.dialogActionLabels().contains("Discard draft"))
            }
            assertTrue(presentationController.state.drafts.projectName.isEmpty())
            assertTrue(AndroidEncryptedDraftStore(context).load(TENANT_ID).projectName.isEmpty())
        }
    }

    @Test
    fun coldOfflineRestartAndResumeStayPromptFreeUntilDeliberateReconnect() {
        sessionExchangeOffline = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.texts().any { it.contains("service", ignoreCase = true) })
            }
            assertEquals(0, workspace.syncCalls)
            assertEquals(0, sessionExchanges.get())

            sessionExchangeOffline = false
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            waitUntil { workspace.syncCalls > 0 }
            assertEquals(1, identity.signInCalls)
            assertEquals(1, sessionExchanges.get())
        }
    }

    @Test
    fun slowBackgroundApiDoesNotBlockImmediateEncryptedLocalCreate() {
        val syncStore = AndroidSyncStateStore(context).apply {
            clearSyncState(TENANT_ID)
            saveSyncState(
                TENANT_ID,
                TenantSyncState(
                    projectCache = ProjectCacheState(
                        activeProjectsLoaded = true,
                        archivedProjectsLoaded = true,
                        blocksLoaded = true,
                    ),
                ),
            )
        }
        val realAccepted = AndroidEncryptedAcceptedTenantContinuityStore(context).apply {
            clear()
            save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))
        }
        val service = CacheProjectService().apply {
            changesEntered = CountDownLatch(1)
            allowChanges = CountDownLatch(1)
        }
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            presentationController = ProjectPresentationController(
                SessionAuthenticator { Session(TENANT_ID, it, ACCOUNT_A) },
                ProjectSyncRepository(
                    service,
                    NoOpLegacyQueueReplay,
                    drafts,
                    AndroidSyncStateStore(activity),
                    coverMediaStore = AndroidEncryptedProjectCoverMediaStore(activity),
                ),
                newOperationId = { "slow-api-${operationIds.incrementAndGet()}" },
            )
            ProjectAppDependencies(
                presentationController,
                identity,
                continuity,
                realAccepted,
                observeConnectivity = false,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            assertTrue(service.changesEntered.await(2, TimeUnit.SECONDS))

            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)
                    ?.setText("Created while Service waits") ?: error("Create-project input is missing")
                activity.dialogButton("Create").callOnClick()
            }
            waitFor(scenario) { it.hasButton("Created while Service waits") }
            assertEquals(1L, service.allowChanges.count)
            assertTrue(
                syncStore.loadSyncState(TENANT_ID).projectCache.activeProjects
                    .any { it.name == "Created while Service waits" },
            )
            service.allowChanges.countDown()
        }
    }

    @Test
    fun unavailableProviderIsNotInvokedDuringOrdinaryCachedReopen() {
        identity.signInFailure = java.io.IOException("credential unavailable")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertTrue(activity.hasButton("Active project"))
                assertFalse(activity.hasButton("Reconnect Google account"))
                assertFalse(activity.texts().any { it.contains("credential", ignoreCase = true) })
            }
            assertTrue(presentationController.state.sessionConfirmationRequired)
            assertEquals(0, identity.signInCalls)
            assertEquals(0, sessionExchanges.get())
            assertEquals(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A), acceptedTenant.load())
            assertTrue(workspace.clearedTenants.isEmpty())
        }
    }

    @Test
    fun processStyleOfflineRestartReadsRealEncryptedDatabaseThenReconcilesOnlyAfterReconnect() {
        val syncStore = AndroidSyncStateStore(context)
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
        realContinuity.save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))
        val service = CacheProjectService()
        val exchangeOnline = java.util.concurrent.atomic.AtomicBoolean(false)
        val exchanges = AtomicInteger()
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            val persisted = AndroidSyncStateStore(activity)
            ProjectAppDependencies(
                controller = ProjectPresentationController(
                    SessionAuthenticator {
                        exchanges.incrementAndGet()
                        if (!exchangeOnline.get()) throw java.io.IOException("service unavailable")
                        Session(TENANT_ID, "service-session", ACCOUNT_A)
                    },
                    ProjectSyncRepository(
                        service,
                        NoOpLegacyQueueReplay,
                        drafts,
                        persisted,
                        coverMediaStore = AndroidEncryptedProjectCoverMediaStore(activity),
                    ),
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
            assertEquals(0, exchanges.get())
            assertEquals(0, identity.signInCalls)
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { assertTrue(it.hasButton("Active project")) }
            assertEquals(0, exchanges.get())
            assertEquals(0, service.changeCalls)

            exchangeOnline.set(true)
            restarted.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            waitUntil { service.changeCalls > 0 }
            assertEquals(1, exchanges.get())
            restarted.onActivity { assertTrue(it.hasButton("Active project")) }
        }
    }

    @Test
    fun emptyEncryptedCacheSupportsCreateNotesAndParkingAcrossAFullOfflineActivityRestart() {
        AndroidSyncStateStore(context).clearSyncState(TENANT_ID)
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        val realAccepted = AndroidEncryptedAcceptedTenantContinuityStore(context).apply {
            clear()
            save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))
        }
        val service = CacheProjectService().apply { offline = true }
        MainActivity.dependencyFactory = { activity ->
            val drafts = AndroidEncryptedDraftStore(activity)
            val repository = ProjectSyncRepository(
                service,
                NoOpLegacyQueueReplay,
                drafts,
                AndroidSyncStateStore(activity),
                coverMediaStore = AndroidEncryptedProjectCoverMediaStore(activity),
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
                coverImporter = coverImporter,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { first ->
            waitFor(first) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            first.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                activity.activeDialog?.findViewById<EditText>(ProjectUiIds.PROJECT_NAME)
                    ?.setText("Offline project") ?: error("Create-project input is missing")
                activity.dialogButton("Create").callOnClick()
            }
            waitFor(first) { it.hasButton("Offline project") }
            first.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.PENDING_CHANGES) == null)
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
                activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick()
            }
            waitFor(first) { it.findViewById<View>(ProjectUiIds.NOTE_SHEET) != null }
            first.onActivity { activity ->
                val note = activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY)
                note.setText("Offline notes")
                note.requestFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(note, InputMethodManager.SHOW_IMPLICIT)
            }
            waitFor(first) { activity ->
                ViewCompat.getRootWindowInsets(activity.findViewById(ProjectUiIds.NOTE_BODY))
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            first.onActivity { activity ->
                val save = activity.findViewById<Button>(ProjectUiIds.SAVE_NOTE)
                assertTrue(save.isShown && save.height > 0)
                save.performClick()
            }
            waitUntil { presentationController.state.noteItems.singleOrNull()?.body == "Offline notes" }
            first.onActivity { it.findViewById<Button>(ProjectUiIds.PARK).performClick() }
            first.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText("Workbench")
                activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Fit bracket")
                val finalField = activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET)
                finalField.setText("Bring screws")
                finalField.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(
                    finalField,
                    InputMethodManager.SHOW_IMPLICIT,
                )
            }
            waitFor(first) { activity ->
                ViewCompat.getRootWindowInsets(activity.findViewById(ProjectUiIds.PARKING_MUST_NOT_FORGET))
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            first.onActivity { activity ->
                val field = activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET)
                assertTrue(
                    ViewCompat.getRootWindowInsets(field)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true,
                )
                val save = activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING)
                assertTrue(save.isShown && save.height > 0)
                save.performClick()
            }
            waitUntil { presentationController.state.projectPage?.newestParking?.location == "Workbench" }
            waitFor(first) { activity ->
                activity.texts().toSet().containsAll(listOf("Workbench", "Fit bracket", "Bring screws"))
            }
            val persistedAfterPark = AndroidSyncStateStore(context).loadSyncState(TENANT_ID).projectCache
            assertEquals(
                1,
                persistedAfterPark.commands.count { it.type == ProjectMutationType.ADD_PARKING },
            )
            assertEquals(
                ParkingDraft("Workbench", "Fit bracket", "Bring screws"),
                persistedAfterPark.commands.single { it.type == ProjectMutationType.ADD_PARKING }.parking,
            )
            assertEquals("Workbench", persistedAfterPark.parkingRecords.single().location)
            assertEquals("Offline notes", persistedAfterPark.noteItems.single().body)
            assertEquals(ProjectItemMutationType.CREATE_NOTE, persistedAfterPark.itemOperations.single().type)
            first.onActivity { activity ->
                val projectId = requireNotNull(presentationController.state.projectPage).project.id
                activity.importCover(projectId, Uri.parse("content://test/real-store-cover"))
            }
            waitUntil { presentationController.state.projectCovers.singleOrNull() != null }
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
                assertTrue(activity.texts().contains("Offline notes"))
                assertEquals(coverImporter.importedDigest(1), presentationController.state.projectCovers.single().contentDigest)
                assertTrue(activity.texts().contains("Workbench"))
                assertTrue(activity.texts().contains("Fit bracket"))
                assertTrue(activity.texts().contains("Bring screws"))

                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                activity.selectDialogItem("Rename")
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
                activity.selectDialogItem("Archive")
                activity.dialogButton("Archive").callOnClick()
            }
            waitFor(restarted) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && !it.hasButton("Renamed offline project")
            }
            restarted.onActivity { activity ->
                activity.button("Archive").performClick()
            }
            waitFor(restarted) { it.hasButton("Renamed offline project") }

            restarted.recreate()
            waitFor(restarted) {
                it.hasButton("Renamed offline project") &&
                    it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null
            }
            restarted.onActivity { activity ->
                activity.button("Renamed offline project").performClick()
            }
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            restarted.onActivity { it.button("Reopen").performClick() }
            waitFor(restarted) { it.findViewById<Button>(ProjectUiIds.PARK) != null }
            restarted.onActivity { it.findViewById<Button>(ProjectUiIds.MAIN).performClick() }
            waitFor(restarted) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD && it.hasButton("Renamed offline project")
            }
            assertTrue(presentationController.state.pendingChanges)
        }

        ActivityScenario.launch(MainActivity::class.java).use { finalRestart ->
            waitFor(finalRestart) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    it.hasButton("Renamed offline project") &&
                    it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null
            }
            assertEquals(coverImporter.importedDigest(1), presentationController.state.projectCovers.single().contentDigest)
            finalRestart.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Sign out")
            }
            waitFor(finalRestart) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            finalRestart.onActivity { it.button("Sign in with Google").performClick() }
            waitFor(finalRestart) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            assertEquals(coverImporter.importedDigest(1), presentationController.state.projectCovers.single().contentDigest)
        }
    }

    @Test
    fun deliberateAccountSwitchActivatesAnotherPartitionWithoutErasingEitherAccount() {
        authenticatedTenant = OTHER_TENANT_ID

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    it.texts().contains("Your first project starts here.")
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
        workspace.cache = workspace.cache.copy(projectCovers = listOf(coverImporter.metadata(PROJECT_ID)))
        workspace.membershipRevoked = true

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }
            waitUntil { acceptedTenant.find(ACCOUNT_A) == null }

            assertEquals(null, acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))
            assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
            assertTrue(workspace.cacheFor(TENANT_ID).projectCovers.isEmpty())
            assertFalse(continuity.shouldRestore)
        }
    }

    @Test
    fun explicitRemoveLocalDataErasesOnlyTheSelectedTenantAfterConfirmation() {
        acceptedTenant.save(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B))
        acceptedTenant.save(AcceptedTenantContinuity(TENANT_ID, ACCOUNT_A))
        workspace.cache = workspace.cache.copy(projectCovers = listOf(coverImporter.metadata(PROJECT_ID)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Remove local data")
                assertTrue(activity.dialogTexts().any { it.contains("permanently removes") })
                activity.dialogButton("Remove local data").callOnClick()
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_LOGIN }

            assertEquals(null, acceptedTenant.find(ACCOUNT_A))
            assertEquals(AcceptedTenantContinuity(OTHER_TENANT_ID, ACCOUNT_B), acceptedTenant.find(ACCOUNT_B))
            assertEquals(listOf(TENANT_ID), workspace.clearedTenants)
            assertTrue(workspace.cacheFor(TENANT_ID).projectCovers.isEmpty())
        }
    }

    @Test
    fun unverifiedTokenClaimsCannotSelectAnotherTenantCache() {
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
                    listOf("Edit cover", "Set Tag", "View QR", "Clear Tags", "Rename", "Archive"),
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
    fun archivedProjectIsLabelledReadableAndExposesOnlyQrAndReopen() {
        val archived = project().copy(
            name = "Archived project",
            lifecycle = ProjectLifecycle.ARCHIVED,
            archivedAt = NOW,
        )
        workspace.cache = workspace.cache.copy(
            activeProjects = emptyList(),
            archivedProjects = listOf(archived),
            pages = listOf(page().copy(project = archived, notes = page().notes.copy(body = "Retained notes"))),
            noteItems = listOf(
                ProjectNoteItem(NOTE_ITEM_ID, PROJECT_ID, "Retained notes", false, "legacy", NOW, NOW),
            ),
            resourceLinks = listOf(
                ProjectResourceLink(
                    "20000000-0000-4000-8000-000000000012",
                    PROJECT_ID,
                    "Retained link",
                    "https://example.com/retained",
                    NOW,
                    NOW,
                ),
            ),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                assertEquals("Settings", activity.findViewById<Button>(ProjectUiIds.SETTINGS).contentDescription)
                activity.button("Archive").performClick()
            }
            waitFor(scenario) { it.hasButton("Archived project") }
            scenario.onActivity { it.button("Archived project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.ARCHIVED_LABEL).isShown)
                assertEquals("Settings", activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).contentDescription)
                assertTrue(activity.findViewById<View>(ProjectUiIds.EDIT_COVER) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.ADD_NOTE) == null)
                assertTrue(activity.findViewById<View>(ProjectUiIds.ADD_LINK) == null)
                assertTrue(activity.texts().contains("Retained notes"))
                assertTrue(activity.hasButton("Retained link"))
                val projectButtons = activity.findViewById<ViewGroup>(android.R.id.content)
                    .descendants()
                    .filterIsInstance<Button>()
                    .toList()
                assertFalse(projectButtons.any { it.contentDescription == "Edit note" })
                assertFalse(projectButtons.any { it.contentDescription == "Delete note" })
                assertFalse(projectButtons.any { it.contentDescription == "Pin note" })
                assertFalse(projectButtons.any { it.contentDescription == "Unpin note" })
                assertFalse(projectButtons.any { it.contentDescription == "Edit link" })
                assertFalse(projectButtons.any { it.contentDescription == "Delete link" })
                activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS).performClick()
                assertEquals(listOf("View QR", "Reopen"), activity.dialogItems())
                activity.selectDialogItem("Reopen")
                activity.dialogButton("Reopen").callOnClick()
            }
            waitFor(scenario) {
                    it.findViewById<View>(ProjectUiIds.ARCHIVED_LABEL) == null &&
                    it.findViewById<Button>(ProjectUiIds.PARK) != null &&
                    it.findViewById<Button>(ProjectUiIds.ADD_NOTE) != null
            }
        }
    }

    @Test
    fun projectItemsUseContainedLocalFormsPinOrderingSafeValidationAndConfirmedDelete() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.findViewById<Button>(ProjectUiIds.ADD_NOTE) != null }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.PARK).height >= 48)
                assertTrue(activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).height >= 48)
                assertTrue(activity.findViewById<Button>(ProjectUiIds.ADD_LINK).height >= 48)
                activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick()
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.NOTE_SHEET) != null }
            val operationsBeforeInvalidNote = workspace.cache.itemOperations.size
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.SAVE_NOTE).performClick() }
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.NOTE_BODY).error != null }
            assertEquals(operationsBeforeInvalidNote, workspace.cache.itemOperations.size)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).setText("Pinned workshop context")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTE).performClick()
            }
            waitFor(scenario) {
                it.findViewById<View>(ProjectUiIds.NOTE_BODY) == null &&
                    it.texts().contains("Pinned workshop context") &&
                    it.findViewById<ViewGroup>(android.R.id.content).descendants()
                        .filterIsInstance<Button>()
                        .any { button -> button.contentDescription == "Pin note" }
            }
            scenario.onActivity { it.buttonWithDescription("Pin note").performClick() }
            waitUntil { workspace.cache.noteItems.single().pinned }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.PROJECT_NOTES) != null)
                activity.buttonWithDescription("Edit note").performClick()
            }
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.NOTE_BODY) != null }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).setText("Edited pinned context")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTE).performClick()
            }
            waitFor(scenario) {
                it.findViewById<Button>(ProjectUiIds.ADD_LINK) != null && it.texts().contains("Edited pinned context")
            }

            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.ADD_LINK).performClick() }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.LINK_SHEET) != null }
            val operationsBeforeUnsafeLink = workspace.cache.itemOperations.size
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.LINK_TITLE).setText("Unsafe")
                activity.findViewById<EditText>(ProjectUiIds.LINK_URI).setText("intent://private")
                activity.findViewById<Button>(ProjectUiIds.SAVE_LINK).performClick()
            }
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.LINK_URI).error != null }
            assertEquals(operationsBeforeUnsafeLink, workspace.cache.itemOperations.size)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.LINK_TITLE).setText("Workshop vault")
                activity.findViewById<EditText>(ProjectUiIds.LINK_URI)
                    .setText("obsidian://open?vault=Blocks&file=Workshop")
                activity.findViewById<Button>(ProjectUiIds.SAVE_LINK).performClick()
            }
            waitFor(scenario) {
                it.findViewById<Button>(ProjectUiIds.ADD_LINK) != null && it.texts().contains("Workshop vault")
            }
            scenario.onActivity { activity ->
                assertTrue(activity.buttonWithDescription("Open Workshop vault").isShown)
                activity.buttonWithDescription("Open Workshop vault").performClick()
                assertEquals(listOf("obsidian://open?vault=Blocks&file=Workshop"), launchedResourceLinks)
                activity.buttonWithDescription("Delete link").performClick()
                activity.dialogButton("Cancel").performClick()
                assertEquals(null, workspace.cache.resourceLinks.single().deletedAt)
                activity.buttonWithDescription("Delete link").performClick()
                activity.dialogButton("Delete").performClick()
            }
            waitUntil { workspace.cache.resourceLinks.single().deletedAt != null }
            scenario.onActivity { activity ->
                activity.buttonWithDescription("Delete note").performClick()
                activity.dialogButton("Delete").performClick()
            }
            waitUntil { workspace.cache.noteItems.single().deletedAt != null }
        }
    }

    @Test
    fun longNotesRestoreAndOverLimitSaveCreatesNoMutation() {
        val valid = "n".repeat(100_000)
        workspace.cache = workspace.cache.copy(
            noteItems = listOf(ProjectNoteItem(NOTE_ITEM_ID, PROJECT_ID, valid, false, "local", NOW, NOW)),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.texts().any { text -> text.length == valid.length } }
            scenario.onActivity { activity -> activity.buttonWithDescription("Edit note").performClick() }
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.NOTE_BODY)?.text?.length == valid.length }
            scenario.recreate()
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.NOTE_BODY)?.text?.length == valid.length }
            val before = workspace.cache.itemOperations.size
            scenario.onActivity { activity ->
                val notes = activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY)
                notes.setText(valid + "x")
                activity.findViewById<Button>(ProjectUiIds.SAVE_NOTE).performClick()
            }
            waitFor(scenario) {
                it.findViewById<EditText>(ProjectUiIds.NOTE_BODY)?.error?.toString()?.contains("100,000") == true
            }
            scenario.onActivity { activity ->
                assertEquals(100_001, activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).text.length)
                assertEquals(before, workspace.cache.itemOperations.size)
            }
        }
    }

    @Test
    fun parkBackWithKeyboardVisibleCreatesNoParkOrQueueMutation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            val commandsBefore = workspace.cache.commands.size
            val parkingBefore = workspace.cache.parkingRecords.size
            scenario.onActivity { activity ->
                assertEquals("Back", activity.findViewById<Button>(ProjectUiIds.MAIN).contentDescription)
                activity.findViewById<Button>(ProjectUiIds.PARK).performClick()
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PARK_SHEET) != null }
            scenario.onActivity { activity ->
                val back = activity.findViewById<Button>(ProjectUiIds.BACK)
                val save = activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING)
                assertEquals("Back", back.contentDescription)
                assertEquals("Save", save.text.toString())
                val field = activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET)
                field.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(
                    field,
                    InputMethodManager.SHOW_IMPLICIT,
                )
            }
            waitFor(scenario) { activity ->
                ViewCompat.getRootWindowInsets(activity.findViewById(ProjectUiIds.PARKING_MUST_NOT_FORGET))
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            scenario.onActivity { activity ->
                val save = activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING)
                assertTrue(save.isShown && save.height > 0)
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
            }
            waitFor(scenario) { it.findViewById<Button>(ProjectUiIds.PARK) != null }
            assertEquals(commandsBefore, workspace.cache.commands.size)
            assertEquals(parkingBefore, workspace.cache.parkingRecords.size)
            assertTrue(workspace.mutations.isEmpty())
        }
    }

    @Test
    fun noteAndParkingDraftsSurviveConfigurationAndNoteDraftSurvivesProcessRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick()
            }
            waitFor(scenario) { it.findViewById<EditText>(ProjectUiIds.NOTE_BODY) != null }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).setText("Draft after recreation")
                activity.findViewById<Button>(ProjectUiIds.BACK).performClick()
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
                activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick()
            }
            scenario.onActivity { activity ->
                assertEquals("Draft after recreation", activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).text.toString())
            }
            assertEquals(0, identity.signInCalls)
        }

        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            restarted.onActivity { it.button("Active project").performClick() }
            waitFor(restarted) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            restarted.onActivity { activity ->
                assertTrue(activity.findViewById<View>(ProjectUiIds.PARK_SHEET) == null)
                activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick()
            }
            restarted.onActivity { activity ->
                assertEquals("Draft after recreation", activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).text.toString())
            }
        }
    }

    @Test
    fun substantialParkingHistoryIsLocalNewestFirstProgressiveAndRestartSafe() {
        val records = (0 until 23).map { index -> parking(index) }
        workspace.cache = workspace.cache.copy(
            pages = listOf(page().copy(newestParking = records.last())),
            parkingRecords = records,
            parkingLoadedProjectIds = listOf(PROJECT_ID),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                val newest = activity.findViewById<ViewGroup>(ProjectUiIds.NEWEST_PARKING)
                val history = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                assertTrue(descendantText(newest).contains("Place 22"))
                assertFalse(descendantText(history).contains("Place 22"))
                assertTrue(descendantText(history).contains("Place 21"))
                assertTrue(descendantText(history).contains("Place 13"))
                assertFalse(descendantText(history).contains("Place 12"))
                assertEquals(9, history.childCount)
                activity.findViewById<Button>(ProjectUiIds.LOAD_OLDER_PARKING).performClick()
            }
            waitFor(scenario) {
                it.findViewById<View>(ProjectUiIds.PARKING_HISTORY).hasDescendantTextExactly("Place 3")
            }
            scenario.onActivity { activity ->
                assertFalse(
                    activity.findViewById<View>(ProjectUiIds.PARKING_HISTORY)
                        .hasDescendantTextExactly("Place 2"),
                )
                activity.findViewById<Button>(ProjectUiIds.LOAD_OLDER_PARKING).performClick()
            }
            waitFor(scenario) {
                it.findViewById<View>(ProjectUiIds.PARKING_HISTORY).hasDescendantTextExactly("Place 0")
            }
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(ProjectUiIds.LOAD_OLDER_PARKING) == null)
                assertEquals(
                    22,
                    activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY).childCount,
                )
            }

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                assertTrue(descendantText(activity.findViewById(ProjectUiIds.NEWEST_PARKING)).contains("Place 22"))
                assertFalse(descendantText(activity.findViewById(ProjectUiIds.PARKING_HISTORY)).contains("Place 22"))
                assertTrue(activity.findViewById<Button>(ProjectUiIds.LOAD_OLDER_PARKING) != null)
                assertEquals(0, identity.signInCalls)
            }
        }
    }

    @Test
    fun rejectedCredentialOffersOneControlledForegroundReauthenticationJourney() {
        workspace.rejectAuthentication = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Reconnect Google account")
            }
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
            assertEquals(2, identity.signInCalls)
        }
    }

    @Test
    fun dashboardIsACompactSearchableQuickLinkSurfaceWithRememberedViewChoice() {
        workspace.addProject(
            "00000000-0000-0000-0000-000000000021",
            ProjectKey.parse("323e4567-e89b-12d3-a456-426614174000")!!,
            "Garden plan",
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val list = activity.findViewById<Button>(ProjectUiIds.VIEW_LIST)
                assertEquals(null, activity.findViewById<Button>(ProjectUiIds.VIEW_TILES))
                assertEquals("", list.text.toString())
                assertEquals("Show List view", list.contentDescription.toString())
                assertTrue(list.width >= (48 * density).toInt())
                val order = activity.findViewById<Button>(ProjectUiIds.DASHBOARD_ORDER)
                assertEquals("", order.text.toString())
                assertTrue(order.contentDescription.toString().contains("Current: Name"))
                val orderGlyph = renderAlpha(requireNotNull(order.compoundDrawablesRelative[0]))
                val listGlyph = renderAlpha(requireNotNull(list.compoundDrawablesRelative[0]))
                assertFalse(orderGlyph.contentEquals(listGlyph))
                assertTrue(
                    orderGlyph.contentEquals(
                        renderAlpha(requireNotNull(activity.getDrawable(R.drawable.ic_sort_descending))),
                    ),
                )
                assertTrue(
                    listGlyph.contentEquals(renderAlpha(requireNotNull(activity.getDrawable(R.drawable.ic_list)))),
                )
                assertTrue(activity.findViewById<View>(ProjectUiIds.DASHBOARD_WATERMARK) != null)
                assertFalse(activity.texts().contains("Blocks"))
                assertFalse(activity.texts().any { it.contains("park message", ignoreCase = true) })
                assertFalse(activity.texts().contains("Notes"))
                val quickLink = activity.findViewById<GridLayout>(ProjectUiIds.PROJECT_TILES).getChildAt(0)
                assertTrue(quickLink.isClickable)
                assertTrue(quickLink.isFocusable)
                assertTrue(quickLink.contentDescription.toString().startsWith("Open "))
                assertTrue(
                    quickLink.descendants().filterIsInstance<android.widget.ImageView>().all {
                        it.contentDescription == null &&
                            it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                assertTrue(
                    quickLink.descendants().filterIsInstance<Button>().all {
                        it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                activity.findViewById<EditText>(ProjectUiIds.SEARCH).setText("gArDeN")
            }
            waitFor(scenario) { activity ->
                activity.hasButton("Garden plan") && !activity.hasButton("Active project")
            }
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(ProjectUiIds.SEARCH).setText("")
                activity.findViewById<Button>(ProjectUiIds.VIEW_LIST).performClick()
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }
            scenario.onActivity { activity ->
                assertEquals(null, activity.findViewById<Button>(ProjectUiIds.VIEW_LIST))
                val tiles = activity.findViewById<Button>(ProjectUiIds.VIEW_TILES)
                assertEquals("", tiles.text.toString())
                assertEquals("Show Tiles view", tiles.contentDescription.toString())
                assertTrue(
                    renderAlpha(requireNotNull(tiles.compoundDrawablesRelative[0])).contentEquals(
                        renderAlpha(requireNotNull(activity.getDrawable(R.drawable.ic_grid))),
                    ),
                )
                val ordered = activity.findViewById<LinearLayout>(ProjectUiIds.PROJECT_LIST)
                    .descendants()
                    .filterIsInstance<Button>()
                    .map { it.text.toString() }
                    .toList()
                assertEquals(listOf("Active project", "Garden plan"), ordered)
                val firstRow = activity.findViewById<LinearLayout>(ProjectUiIds.PROJECT_LIST).getChildAt(0)
                assertTrue(firstRow.isClickable)
                assertTrue(firstRow.contentDescription.toString().startsWith("Open "))
            }

            scenario.recreate()
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }
            scenario.onActivity { activity ->
                assertEquals("", activity.findViewById<Button>(ProjectUiIds.VIEW_TILES).text.toString())
            }
        }
    }

    @Test
    fun searchIsEphemeralAcrossActivityRecreationAndAccountSwitch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.findViewById<EditText>(ProjectUiIds.SEARCH).setText("Active project") }
            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity {
                assertEquals("", it.findViewById<EditText>(ProjectUiIds.SEARCH).text.toString())
                it.findViewById<EditText>(ProjectUiIds.SEARCH).setText("Active project")
            }
            authenticatedTenant = OTHER_TENANT_ID
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.SETTINGS).performClick()
                activity.selectDialogItem("Switch account")
            }
            waitFor(scenario) {
                it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD &&
                    presentationController.activeTenantId == OTHER_TENANT_ID
            }
            scenario.onActivity {
                assertEquals("", it.findViewById<EditText>(ProjectUiIds.SEARCH).text.toString())
            }
        }
    }

    @Test
    fun coverActionsAreContextualWithoutDuplicatePickerMeanings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.EDIT_COVER).performClick() }
            waitFor(scenario) { it.visibleDialogItems().contains("Choose image") }
            scenario.onActivity { activity ->
                val before = presentationController.state.projectCovers
                assertFalse(activity.visibleDialogItems().contains("Remove cover"))
                activity.dialogButton("Cancel").performClick()
                assertEquals(before, presentationController.state.projectCovers)
                activity.findViewById<Button>(ProjectUiIds.EDIT_COVER).performClick()
            }
            waitFor(scenario) { it.visibleDialogItems().contains("Choose image") }
            scenario.onActivity { activity ->
                activity.activateVisibleDialogItem("Choose image")
                assertEquals(listOf(PROJECT_ID), coverSourceActions.pickerProjects)
                assertTrue(presentationController.state.projectCovers.isEmpty())
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/cover"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            scenario.onActivity { it.dialogButton("Confirm").callOnClick() }
            waitUntil { presentationController.state.projectCovers.isNotEmpty() }
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.EDIT_COVER).performClick() }
            waitFor(scenario) { it.visibleDialogItems().contains("Remove cover") }
            scenario.onActivity { activity ->
                val texts = activity.visibleDialogItems()
                assertTrue(
                    texts.containsAll(
                        listOf("Choose replacement image", "Remove cover"),
                    ),
                )
                assertFalse(texts.contains("Choose image"))
            }
        }
    }

    @Test
    fun galleryUsesExplicitCropConfirmCancelPath() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }

            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/gallery"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            val discardedBefore = coverCropper.discarded
            scenario.onActivity { activity ->
                val crop = requireNotNull(activity.activeDialog)
                    .findViewById<ProjectCoverCropView>(ProjectUiIds.CROP_SURFACE)
                crop.setSelectionForTest(CoverCropSelection(0.7f, 0.4f, 2f))
                activity.dialogButton("Cancel").callOnClick()
            }
            waitUntil { coverCropper.discarded > discardedBefore }
            assertTrue(workspace.cache.projectCovers.isEmpty())

            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/gallery-confirm"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            scenario.onActivity { activity ->
                val crop = requireNotNull(activity.activeDialog)
                    .findViewById<ProjectCoverCropView>(ProjectUiIds.CROP_SURFACE)
                crop.setSelectionForTest(CoverCropSelection(0.6f, 0.5f, 1.5f))
                activity.dialogButton("Confirm").callOnClick()
            }
            waitUntil { workspace.cache.projectCovers.isNotEmpty() }
            assertEquals(CoverCropSelection(0.6f, 0.5f, 1.5f), coverCropper.confirmedSelections.single())
        }
    }

    @Test
    fun coverImportFailureCancellationRestartAndExplicitRemovalPreserveAtomicUiState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            scenario.onActivity { it.button("Active project").performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/first"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            scenario.onActivity { it.dialogButton("Confirm").callOnClick() }
            waitUntil { workspace.cache.projectCovers.singleOrNull()?.contentDigest == coverImporter.importedDigest(1) }
            waitFor(scenario) { activity ->
                activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_COVER)
                    .descendants()
                    .filterIsInstance<android.widget.ImageView>()
                    .all { it.contentDescription == null }
            }
            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/replacement"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            scenario.onActivity { it.dialogButton("Confirm").callOnClick() }
            waitUntil { workspace.cache.projectCovers.singleOrNull()?.contentDigest == coverImporter.importedDigest(2) }

            scenario.recreate()
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            assertEquals(coverImporter.importedDigest(2), workspace.cache.projectCovers.single().contentDigest)

            coverImporter.failure = java.io.IOException("denied")
            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(Uri.parse("content://test/denied"))
            }
            waitFor(scenario) { it.activeDialog?.findViewById<View>(ProjectUiIds.CROP_SURFACE) != null }
            scenario.onActivity { activity ->
                activity.dialogButton("Confirm").callOnClick()
            }
            waitFor(scenario) { it.texts().any { text -> text.contains("could not save", ignoreCase = true) } }
            scenario.onActivity { activity ->
                activity.stagePendingCover(PROJECT_ID)
                activity.handleCoverPickerResult(null)
            }
            assertEquals(coverImporter.importedDigest(2), workspace.cache.projectCovers.single().contentDigest)

            coverImporter.failure = null
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.EDIT_COVER).performClick()
                activity.selectDialogItem("Remove cover")
                activity.dialogButton("Remove").callOnClick()
            }
            waitUntil { workspace.cache.projectCovers.isEmpty() }
            scenario.onActivity { activity ->
                assertTrue(activity.texts().contains("Active project"))
                assertTrue(activity.findViewById<View>(ProjectUiIds.PROJECT_COVER) != null)
            }
        }
    }

    @Test
    fun activityStartupRemovesAbandonedImportSourcesWithoutTouchingOwnedExceptions() {
        val imports = CoverImportFiles(context)
        val directory = imports.directoryForTest().apply { mkdirs() }
        val abandoned = java.io.File(directory, "123e4567-e89b-42d3-a456-426614174999.source").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val retained = imports.createRetained().apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val unrelatedInDirectory = java.io.File(directory, "not-owned.source").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val unrelatedCache = java.io.File(context.cacheDir, "unrelated-cover-cache.source").apply {
            writeBytes(byteArrayOf(10, 11, 12))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
                waitUntil { !abandoned.exists() }
                assertTrue(retained.isFile)
                assertTrue(unrelatedInDirectory.isFile)
                assertTrue(unrelatedCache.isFile)
            }
        } finally {
            imports.delete(retained)
            unrelatedInDirectory.delete()
            unrelatedCache.delete()
        }
    }

    @Test
    fun cachedDashboardAndProjectLinkOpenAreMeasuredWithoutWaitingForTransportOrCoverDecode() {
        workspace.cache = workspace.cache.copy(projectCovers = listOf(coverImporter.metadata(PROJECT_ID)))
        sessionExchangeOffline = true
        val started = android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            val coldDashboardMs = android.os.SystemClock.elapsedRealtime() - started
            val openStarted = android.os.SystemClock.elapsedRealtime()
            scenario.onActivity {
                it.handleIncomingTagIntent(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_LINK.encoded)))
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            val coldProjectMs = android.os.SystemClock.elapsedRealtime() - openStarted
            val returnStarted = android.os.SystemClock.elapsedRealtime()
            scenario.onActivity { it.findViewById<Button>(ProjectUiIds.MAIN).performClick() }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
            val warmDashboardMs = android.os.SystemClock.elapsedRealtime() - returnStarted
            val reopenStarted = android.os.SystemClock.elapsedRealtime()
            scenario.onActivity {
                it.handleIncomingTagIntent(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_LINK.encoded)))
            }
            waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
            val warmProjectMs = android.os.SystemClock.elapsedRealtime() - reopenStarted
            android.util.Log.i(
                "BlocksTiming",
                "cold_dashboard_ms=$coldDashboardMs warm_dashboard_ms=$warmDashboardMs " +
                    "cold_project_link_open_ms=$coldProjectMs warm_project_link_open_ms=$warmProjectMs",
            )
            assertTrue("Cold dashboard local render took ${coldDashboardMs}ms", coldDashboardMs < 5_000)
            assertTrue("Warm dashboard local render took ${warmDashboardMs}ms", warmDashboardMs < 2_000)
            assertTrue("Cold project-link local open took ${coldProjectMs}ms", coldProjectMs < 2_000)
            assertTrue("Warm project-link local open took ${warmProjectMs}ms", warmProjectMs < 2_000)
            assertEquals(0, workspace.syncCalls)
            assertEquals(0, sessionExchanges.get())
        }
    }

    @Test
    fun enlargedSystemTextKeepsQuickLinksAndProjectActionsReachableInTheScrollSurface() {
        shell("settings put system font_scale 1.3")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<ScrollView>(ProjectUiIds.ROOT_SCROLL) != null)
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.VIEW_LIST).contentDescription.toString().isNotBlank())
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.SETTINGS).contentDescription.toString().isNotBlank())
                    activity.button("Active project").performClick()
                }
                waitFor(scenario) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.EDIT_COVER) != null)
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.PARK) != null)
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.ADD_NOTE) != null)
                    assertTrue(activity.findViewById<Button>(ProjectUiIds.ADD_LINK) != null)
                    assertTrue(
                        activity.findViewById<Button>(ProjectUiIds.PROJECT_SETTINGS)
                            .contentDescription.toString().isNotBlank(),
                    )
                }
            }
        } finally {
            shell("settings put system font_scale 1.0")
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

    private fun renderAlpha(drawable: android.graphics.drawable.Drawable): IntArray {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
            .map { Color.alpha(it) }.toIntArray().also { bitmap.recycle() }
    }

    private fun assertCentredCog(alpha: IntArray) {
        val size = 96
        val occupied = alpha.indices.filter { alpha[it] > 0 }
        assertTrue(occupied.isNotEmpty())
        val minX = occupied.minOf { it % size }
        val maxX = occupied.maxOf { it % size }
        val minY = occupied.minOf { it / size }
        val maxY = occupied.maxOf { it / size }
        assertTrue(kotlin.math.abs((minX + maxX) - (size - 1)) <= 2)
        assertTrue(kotlin.math.abs((minY + maxY) - (size - 1)) <= 2)
        assertEquals(0, alpha[(size / 2) * size + size / 2])
    }

    private fun descendantText(view: View): String = when (view) {
        is android.widget.TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { descendantText(view.getChildAt(it)) }
        else -> ""
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for UI state: ${presentationController.state}")
    }

    private fun waitUntilOnActivity(
        activity: MainActivity,
        condition: (MainActivity) -> Boolean,
    ) = waitUntil {
        var met = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { met = condition(activity) }
        met
    }

    private fun assertResponsiveDashboard(activity: MainActivity) {
        val windowContent = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = activity.findViewById<ScrollView>(ProjectUiIds.ROOT_SCROLL)
        val body = activity.findViewById<LinearLayout>(ProjectUiIds.DASHBOARD_BODY)
        val grid = activity.findViewById<GridLayout>(ProjectUiIds.PROJECT_TILES)
        assertEquals(windowContent.width, root.width)
        assertTrue(root.height < windowContent.height)
        assertTrue(root.height > windowContent.height / 2)
        assertEquals(body.width, grid.width)
        assertEquals(Gravity.CENTER, body.gravity)
        assertTrue(root.background != null)
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    private fun Activity.buttonWithDescription(description: String): Button =
        findViewById<ViewGroup>(android.R.id.content)
            .descendants()
            .filterIsInstance<Button>()
            .single { it.contentDescription?.toString() == description }

    private fun Activity.hasButton(text: String) = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<Button>()
        .any { it.text.toString() == text }

    private fun Activity.hasDescription(description: String) = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .any { it.contentDescription?.toString() == description && it.isShown }

    private fun Activity.texts() = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .mapNotNull { (it as? android.widget.TextView)?.text?.toString() }

    private fun MainActivity.dialogButton(text: String): Button = requireNotNull(activeDialog)
        .window?.decorView?.descendants().orEmpty()
        .filterIsInstance<Button>()
        .single { it.text.toString() == text }

    private fun MainActivity.dialogActionLabels(): Set<String> = requireNotNull(activeDialog)
        .window?.decorView?.descendants().orEmpty()
        .filterIsInstance<Button>()
        .map { it.text.toString() }
        .filter(String::isNotBlank)
        .toSet()

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

    private fun MainActivity.dialogItems(): List<String> {
        val adapter = requireNotNull(activeDialog).listView.adapter
        return (0 until adapter.count).map { adapter.getItem(it).toString() }
    }

    private fun MainActivity.visibleDialogItems(): List<String> = requireNotNull(activeDialog).listView.run {
        (0 until childCount).mapNotNull { index ->
            getChildAt(index).descendants()
                .filterIsInstance<android.widget.TextView>()
                .firstOrNull { it.text.isNotBlank() && it.isShown && it.height > 0 }
                ?.text
                ?.toString()
        }
    }

    private fun MainActivity.activateVisibleDialogItem(text: String) {
        val list = requireNotNull(activeDialog).listView
        val childIndex = (0 until list.childCount).single { index ->
            list.getChildAt(index).descendants()
                .filterIsInstance<android.widget.TextView>()
                .any { it.text.toString() == text && it.isShown && it.height > 0 }
        }
        val child = list.getChildAt(childIndex)
        val adapterPosition = list.firstVisiblePosition + childIndex
        assertTrue(child.isShown && child.height > 0)
        list.performItemClick(child, adapterPosition, list.adapter.getItemId(adapterPosition))
    }

    private fun MainActivity.dialogInput(): EditText = requireNotNull(activeDialog)
        .window?.decorView?.descendants().orEmpty()
        .filterIsInstance<EditText>()
        .single()

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun View.hasDescendantTextExactly(expected: String): Boolean = descendants()
        .filterIsInstance<android.widget.TextView>()
        .any { it.text.toString() == expected }

    private class FakeCoverSourceActions : CoverSourceActions {
        val pickerProjects = mutableListOf<String>()

        override fun chooseImage(projectId: String) {
            pickerProjects += projectId
        }
    }

    private class FakeCoverCropper(
        private val importer: FakeCoverImporter,
    ) : ProjectCoverCropper {
        val confirmedSelections = mutableListOf<CoverCropSelection>()
        var discarded = 0

        override fun prepare(uri: Uri): PreparedProjectCoverCrop = PreparedProjectCoverCrop(
            source = null,
            preview = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(23, 107, 99))
            },
        )

        override fun confirm(
            projectId: String,
            prepared: PreparedProjectCoverCrop,
            selection: CoverCropSelection,
        ): ProjectCoverContent {
            confirmedSelections += selection
            return try {
                importer.importCover(projectId, Uri.EMPTY)
            } finally {
                discard(prepared)
            }
        }

        override fun discard(prepared: PreparedProjectCoverCrop) {
            if (!prepared.preview.isRecycled) prepared.preview.recycle()
            discarded += 1
        }

        override fun cleanupAbandonedSources(): Int = 0
    }

    private class FakeCoverImporter(context: Context) : ProjectCoverImporter {
        private val importFiles = CoverImportFiles(context)
        @Volatile
        var failure: Exception? = null
        private var importCount = 0
        private val encoded: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(23, 107, 99))
            }
            try {
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        override fun importCover(projectId: String, uri: Uri): ProjectCoverContent {
            failure?.let { throw it }
            importCount += 1
            return content(projectId, (importCount.coerceAtMost(9) + '0'.code).toChar())
        }

        override fun cleanupAbandonedSources(): Int = importFiles.cleanupOrphans()

        private fun content(projectId: String, digestCharacter: Char): ProjectCoverContent {
            val display = encoded + byteArrayOf(digestCharacter.code.toByte())
            val thumbnail = encoded + byteArrayOf((digestCharacter.code + 16).toByte())
            return ProjectCoverContent(
                projectId = projectId,
                displayJpeg = display,
                thumbnailJpeg = thumbnail,
                displayWidth = 1200,
                displayHeight = 675,
                thumbnailWidth = 480,
                thumbnailHeight = 270,
                displayDigest = digest(display),
                thumbnailDigest = digest(thumbnail),
                contentDigest = digest(display, thumbnail),
                importedAt = NOW,
            )
        }

        fun importedDigest(number: Int): String {
            val marker = (number.coerceAtMost(9) + '0'.code).toChar()
            val display = encoded + byteArrayOf(marker.code.toByte())
            val thumbnail = encoded + byteArrayOf((marker.code + 16).toByte())
            return digest(display, thumbnail)
        }

        fun metadata(projectId: String, digestCharacter: Char = '1') = ProjectCover(
            coverId = "123e4567-e89b-42d3-a456-426614174000",
            tenantId = TENANT_ID,
            projectId = projectId,
            mediaType = "image/jpeg",
            displayContentRef = "123e4567-e89b-42d3-a456-426614174000-r1-display.enc",
            displayWidth = 1200,
            displayHeight = 675,
            displayByteSize = encoded.size.toLong(),
            displayDigest = digest(encoded),
            thumbnailContentRef = "123e4567-e89b-42d3-a456-426614174000-r1-thumbnail.enc",
            thumbnailWidth = 480,
            thumbnailHeight = 270,
            thumbnailByteSize = encoded.size.toLong(),
            thumbnailDigest = digest(encoded),
            contentDigest = digestCharacter.toString().repeat(64),
            revision = 1,
            createdAt = NOW,
            updatedAt = NOW,
        )

        fun imageBytes(): ByteArray = encoded.copyOf()

        private fun digest(vararg values: ByteArray): String = MessageDigest.getInstance("SHA-256").run {
            values.forEach(::update)
            digest()
        }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class FakeNfcTagPlatform : NfcTagPlatform {
        var available = true
        override val isAvailable get() = available
        override val isEnabled = true
        @Volatile
        var readerActive = false
        @Volatile
        var result: NfcProgrammingResult = NfcProgrammingResult.Verified()
        @Volatile
        var clearResult: NfcClearResult = NfcClearResult.Verified()
        var programCalls = 0
        var programInvocations = 0
        var verifyWriteCalls = 0
        var clearCalls = 0
        var readerWasActiveDuringProgram = false
        val programmedAddresses = mutableListOf<BlocksNavigationAddress>()
        var programEntered: CountDownLatch? = null
        var allowProgram: CountDownLatch? = null
        private var callback: ((NfcTagHandle) -> Unit)? = null

        override fun startReader(onTag: (NfcTagHandle) -> Unit) {
            callback = onTag
            readerActive = true
        }

        override fun stopReader() {
            readerActive = false
        }

        override fun programAndVerify(
            handle: NfcTagHandle,
            address: BlocksNavigationAddress,
            cancellation: NfcIoCancellation,
        ): NfcProgrammingResult {
            programInvocations += 1
            readerWasActiveDuringProgram = readerActive
            programEntered?.countDown()
            allowProgram?.let { release ->
                if (!cancellation.registerConnection(release::countDown)) {
                    return NfcProgrammingResult.Cancelled
                }
                check(release.await(2, TimeUnit.SECONDS)) { "Timed out waiting to finish NFC I/O" }
                cancellation.clearConnection()
            }
            if (!cancellation.beginMutation()) return NfcProgrammingResult.Cancelled
            programCalls += 1
            programmedAddresses += address
            return result
        }

        override fun verifyWritten(
            handle: NfcTagHandle,
            address: BlocksNavigationAddress,
            expectedCorrelation: NfcPresentationCorrelation,
            cancellation: NfcIoCancellation,
        ): NfcProgrammingResult {
            if (handle.presentationCorrelation != expectedCorrelation) {
                return NfcProgrammingResult.AwaitingReadBack(expectedCorrelation)
            }
            verifyWriteCalls += 1
            return result
        }

        override fun clearAndVerify(
            handle: NfcTagHandle,
            cancellation: NfcIoCancellation,
        ): NfcClearResult {
            if (!cancellation.beginMutation()) return NfcClearResult.Cancelled
            clearCalls += 1
            return clearResult
        }

        override fun verifyCleared(
            handle: NfcTagHandle,
            expectedCorrelation: NfcPresentationCorrelation,
            cancellation: NfcIoCancellation,
        ): NfcClearResult = clearResult

        fun tap(correlation: NfcPresentationCorrelation = TAG_A_CORRELATION) {
            val current = checkNotNull(callback)
            current(FakeNfcTagHandle(correlation))
        }

        private data class FakeNfcTagHandle(
            override val presentationCorrelation: NfcPresentationCorrelation,
        ) : NfcTagHandle
    }

    private class FakeIdentity : IdentityProvider {
        var signInCalls = 0
        var clearCalls = 0
        var signInFailure: Exception? = null
        override suspend fun signIn(activity: Activity): String {
            signInCalls += 1
            signInFailure?.let { throw it }
            return "identity-token"
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
        private val coverBytes = mutableMapOf<Pair<String, String>, ByteArray>()
        var blockCoverReadTenant: String? = null
        val coverReadEntered = CountDownLatch(1)
        val allowCoverRead = CountDownLatch(1)
        private val caches = mutableMapOf(
            TENANT_ID to ProjectCacheState(
                activeProjects = listOf(project()),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(page()),
                projectLinks = listOf(ProjectLinkIdentity(PROJECT_ID, PROJECT_KEY)),
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
        var failCoverSave = false
        @Volatile
        var syncCalls = 0
        @Volatile
        var pageCalls = 0
        val activatedTenants = mutableListOf<String>()
        val deactivatedTenants = mutableListOf<String>()
        val clearedTenants = mutableListOf<String>()
        val mutations = mutableListOf<String>()
        val preparedTagTokens = mutableListOf<OpaqueTagToken>()

        @Synchronized
        fun cacheFor(tenantId: String): ProjectCacheState = caches[tenantId] ?: emptyCache()

        @Synchronized
        fun remapCurrentProject(serverId: String) {
            val existing = cache.pages.single()
            val provisionalId = existing.project.id
            val remappedProject = existing.project.copy(id = serverId)
            cache = cache.copy(
                activeProjects = cache.activeProjects.map {
                    if (it.id == provisionalId) it.copy(id = serverId) else it
                },
                archivedProjects = cache.archivedProjects.map {
                    if (it.id == provisionalId) it.copy(id = serverId) else it
                },
                pages = cache.pages.map { page ->
                    if (page.project.id == provisionalId) {
                        page.copy(
                            project = remappedProject,
                            notes = page.notes.copy(projectId = serverId),
                            newestParking = page.newestParking?.copy(projectId = serverId),
                        )
                    } else {
                        page
                    }
                },
                parkingRecords = cache.parkingRecords.map {
                    if (it.projectId == provisionalId) it.copy(projectId = serverId) else it
                },
                projectIdMappings = cache.projectIdMappings + ProjectIdentityMapping(provisionalId, serverId),
                projectLinks = cache.projectLinks.map {
                    if (it.projectId == provisionalId) it.copy(projectId = serverId) else it
                },
                projectCovers = cache.projectCovers.map {
                    if (it.projectId == provisionalId) it.copy(projectId = serverId) else it
                },
                localBlocks = cache.localBlocks.map { block ->
                    block.copy(
                        intendedProjectId = if (block.intendedProjectId == provisionalId) serverId else block.intendedProjectId,
                        boundProjectId = if (block.boundProjectId == provisionalId) serverId else block.boundProjectId,
                    )
                },
            )
        }

        @Synchronized
        fun addProject(projectId: String, key: ProjectKey, name: String) {
            val summary = project().copy(id = projectId, name = name)
            cache = cache.copy(
                activeProjects = cache.activeProjects + summary,
                pages = cache.pages + ProjectPage(
                    summary,
                    ProjectNotes(projectId, "", 1, NOW),
                    null,
                ),
                projectLinks = cache.projectLinks + ProjectLinkIdentity(projectId, key),
            )
        }

        @Synchronized
        fun seedTenantCover(
            tenantId: String,
            projectId: String,
            key: ProjectKey,
            name: String,
            cover: ProjectCover,
            bytes: ByteArray,
        ) {
            val summary = project().copy(id = projectId, tenantId = tenantId, name = name)
            val scopedCover = cover.copy(tenantId = tenantId, projectId = projectId)
            caches[tenantId] = ProjectCacheState(
                activeProjects = listOf(summary),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(ProjectPage(summary, ProjectNotes(projectId, "", 1, NOW), null)),
                projectLinks = listOf(ProjectLinkIdentity(projectId, key)),
                projectCovers = listOf(scopedCover),
            )
            coverBytes[tenantId to scopedCover.displayContentRef] = bytes.copyOf()
            coverBytes[tenantId to scopedCover.thumbnailContentRef] = bytes.copyOf()
        }

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
            return cache.pages.single { it.project.id == projectId }
        }
        override fun parkingHistory(
            session: Session,
            projectId: String,
            offset: Int,
            limit: Int,
        ): ParkingHistoryPage {
            val records = cache.parkingRecords.filter { it.projectId == projectId }
                .sortedByDescending { it.occurredAt }
            val visible = records.drop(offset).take(limit)
            return ParkingHistoryPage(visible, offset + visible.size < records.size)
        }
        override fun projectCover(session: Session, projectId: String) =
            cache.projectCovers.singleOrNull { it.projectId == projectId }
        override fun readProjectCoverContent(
            session: Session,
            cover: ProjectCover,
            thumbnail: Boolean,
        ): ByteArray {
            if (blockCoverReadTenant == session.tenantId) {
                coverReadEntered.countDown()
                check(allowCoverRead.await(5, TimeUnit.SECONDS)) { "timed out waiting to release cover read" }
            }
            return coverBytes[session.tenantId to if (thumbnail) {
                cover.thumbnailContentRef
            } else {
                cover.displayContentRef
            }]?.copyOf() ?: byteArrayOf()
        }

        override fun saveProjectCover(session: Session, cover: ProjectCoverContent): BackgroundSyncResult {
            if (failCoverSave) throw IllegalStateException("simulated cover metadata failure")
            val previous = cache.projectCovers.singleOrNull { it.projectId == cover.projectId }
            val coverId = previous?.coverId ?: "123e4567-e89b-42d3-a456-426614174000"
            val revision = (previous?.revision ?: 0) + 1
            val metadata = ProjectCover(
                coverId = coverId,
                tenantId = session.tenantId,
                projectId = cover.projectId,
                mediaType = "image/jpeg",
                displayContentRef = "$coverId-r$revision-display.enc",
                displayWidth = cover.displayWidth,
                displayHeight = cover.displayHeight,
                displayByteSize = cover.displayJpeg.size.toLong(),
                displayDigest = cover.displayDigest,
                thumbnailContentRef = "$coverId-r$revision-thumbnail.enc",
                thumbnailWidth = cover.thumbnailWidth,
                thumbnailHeight = cover.thumbnailHeight,
                thumbnailByteSize = cover.thumbnailJpeg.size.toLong(),
                thumbnailDigest = cover.thumbnailDigest,
                contentDigest = cover.contentDigest,
                revision = revision,
                createdAt = previous?.createdAt ?: cover.importedAt,
                updatedAt = cover.importedAt,
            )
            coverBytes[session.tenantId to metadata.displayContentRef] = cover.displayJpeg.copyOf()
            coverBytes[session.tenantId to metadata.thumbnailContentRef] = cover.thumbnailJpeg.copyOf()
            cache = cache.copy(
                projectCovers = cache.projectCovers.filterNot { it.projectId == cover.projectId } + metadata,
            )
            previous?.let {
                coverBytes.remove(session.tenantId to it.displayContentRef)?.fill(0)
                coverBytes.remove(session.tenantId to it.thumbnailContentRef)?.fill(0)
            }
            cover.clear()
            return result()
        }
        override fun removeProjectCover(session: Session, projectId: String): BackgroundSyncResult {
            cache.projectCovers.singleOrNull { it.projectId == projectId }?.let {
                coverBytes.remove(session.tenantId to it.displayContentRef)?.fill(0)
                coverBytes.remove(session.tenantId to it.thumbnailContentRef)?.fill(0)
            }
            cache = cache.copy(projectCovers = cache.projectCovers.filterNot { it.projectId == projectId })
            return result()
        }
        override fun projectLink(session: Session, projectId: String): ProjectLink = cache.projectLinks
            .single { it.projectId == projectId }
            .key
            .let(ProjectLink::create)
        override fun resolveProjectLinkLocally(session: Session, key: ProjectKey): String? = cache.projectLinks
            .singleOrNull { it.key == key }
            ?.projectId
            ?.takeIf { projectId -> cache.pages.any { it.project.id == projectId } }
        override fun prepareDirectTagWrite(
            session: Session,
            projectId: String,
            label: String,
            clientBlockId: String,
            token: OpaqueTagToken,
            registrationOperationId: String,
            bindingOperationId: String,
        ): DirectTagWritePlan {
            preparedTagTokens += token
            return DirectTagWritePlan(
                LocalBlockRecord(
                    clientBlockId = clientBlockId,
                    token = token,
                    label = label,
                    state = LocalBlockState.AWAITING_WRITE,
                    intendedProjectId = projectId,
                    registrationOperationId = registrationOperationId,
                    initialBindingOperationId = bindingOperationId,
                    createdAt = NOW,
                ),
            )
        }
        override fun commitVerifiedDirectTagWrite(
            session: Session,
            plan: DirectTagWritePlan,
            replacedToken: OpaqueTagToken?,
        ): BackgroundSyncResult {
            replacedToken?.let { token ->
                cache.localBlocks.firstOrNull {
                    it.token == token && it.state == LocalBlockState.ACTIVE && it.boundProjectId != null
                }?.let { replaced ->
                    unbindLocalBlock(session, replaced.clientBlockId, "replace-${plan.block.clientBlockId}")
                }
            }
            cache = cache.copy(localBlocks = cache.localBlocks + plan.block)
            return completeTagProgramming(
                session,
                plan.block.clientBlockId,
                plan.block.token,
                TagActivationMedium.NFC,
            )
        }
        override fun commitVerifiedDirectTagClear(
            session: Session,
            erasedToken: OpaqueTagToken?,
            operationId: String,
        ): BackgroundSyncResult {
            val block = erasedToken?.let { token -> cache.localBlocks.firstOrNull { it.token == token } }
                ?: return result()
            return if (block.state == LocalBlockState.ACTIVE && block.boundProjectId != null) {
                unbindLocalBlock(session, block.clientBlockId, operationId)
            } else {
                result()
            }
        }
        override fun beginTagProgramming(
            session: Session,
            projectId: String,
            label: String,
            clientBlockId: String,
            token: OpaqueTagToken,
            registrationOperationId: String,
            bindingOperationId: String,
        ): LocalBlockRecord {
            val block = LocalBlockRecord(
                clientBlockId = clientBlockId,
                token = token,
                label = label,
                state = LocalBlockState.AWAITING_WRITE,
                intendedProjectId = projectId,
                registrationOperationId = registrationOperationId,
                initialBindingOperationId = bindingOperationId,
                createdAt = NOW,
            )
            cache = cache.copy(localBlocks = cache.localBlocks + block)
            return block
        }
        override fun markTagWriteAttempt(session: Session, clientBlockId: String): LocalBlockRecord =
            updateBlock(clientBlockId) {
                it.copy(state = LocalBlockState.WRITE_UNCERTAIN, failure = TagProgrammingFailure.IO_UNCERTAIN)
            }
        override fun recordTagProgrammingFailure(
            session: Session,
            clientBlockId: String,
            failure: TagProgrammingFailure,
        ): LocalBlockRecord = updateBlock(clientBlockId) {
            it.copy(
                state = if (
                    failure == TagProgrammingFailure.IO_UNCERTAIN ||
                    failure == TagProgrammingFailure.VERIFICATION_MISMATCH
                ) {
                    LocalBlockState.WRITE_UNCERTAIN
                } else {
                    LocalBlockState.AWAITING_WRITE
                },
                failure = failure,
            )
        }
        override fun discardPendingTagProgramming(
            session: Session,
            clientBlockId: String,
        ): BackgroundSyncResult {
            cache = cache.copy(localBlocks = cache.localBlocks.filterNot { it.clientBlockId == clientBlockId })
            return result()
        }
        override fun authorizeTagReplacement(
            session: Session,
            clientBlockId: String,
            existingToken: OpaqueTagToken,
        ): LocalBlockRecord = updateBlock(clientBlockId) {
            it.copy(replacementTokenFingerprint = existingToken.fingerprint())
        }
        override fun completeTagProgramming(
            session: Session,
            clientBlockId: String,
            verifiedToken: OpaqueTagToken,
            medium: TagActivationMedium,
        ): BackgroundSyncResult {
            val block = cache.localBlocks.single { it.clientBlockId == clientBlockId }
            val registration = LocalBlockOperation(
                operationId = requireNotNull(block.registrationOperationId),
                type = LocalBlockOperationType.REGISTER_BLOCK,
                clientBlockId = clientBlockId,
                token = verifiedToken,
                label = block.label,
                createdAt = block.createdAt,
            )
            val binding = LocalBlockOperation(
                operationId = requireNotNull(block.initialBindingOperationId),
                type = LocalBlockOperationType.BIND_BLOCK,
                clientBlockId = clientBlockId,
                projectId = requireNotNull(block.intendedProjectId),
                dependsOnOperationId = registration.operationId,
                createdAt = NOW,
            )
            cache = cache.copy(
                localBlocks = cache.localBlocks.map {
                    if (it.clientBlockId == clientBlockId) {
                        it.copy(
                            state = LocalBlockState.ACTIVE,
                            boundProjectId = it.intendedProjectId,
                            verifiedAt = NOW,
                            activationMedium = medium,
                            failure = null,
                        )
                    } else {
                        it
                    }
                },
                localBlockOperations = (cache.localBlockOperations + registration + binding)
                    .distinctBy { it.operationId },
            )
            return result()
        }
        override fun resolveTagLocally(session: Session, token: OpaqueTagToken): LocalTagResolution {
            val block = cache.localBlocks.firstOrNull { it.token == token }
                ?: return LocalTagResolution.Unknown(token)
            return when {
                block.state in setOf(LocalBlockState.AWAITING_WRITE, LocalBlockState.WRITE_UNCERTAIN) -> {
                    LocalTagResolution.PendingProgramming(block)
                }
                block.boundProjectId == null -> LocalTagResolution.Unbound(block)
                else -> LocalTagResolution.Bound(block, block.boundProjectId)
            }
        }
        override fun assignLocalBlock(
            session: Session,
            clientBlockId: String,
            projectId: String,
            operationId: String,
        ): BackgroundSyncResult {
            val dependency = cache.localBlockOperations.lastOrNull {
                it.clientBlockId == clientBlockId
            }?.operationId
            cache = cache.copy(
                localBlocks = cache.localBlocks.map {
                    if (it.clientBlockId == clientBlockId) {
                        it.copy(state = LocalBlockState.ACTIVE, intendedProjectId = projectId, boundProjectId = projectId)
                    } else {
                        it
                    }
                },
                localBlockOperations = cache.localBlockOperations + LocalBlockOperation(
                    operationId = operationId,
                    type = LocalBlockOperationType.BIND_BLOCK,
                    clientBlockId = clientBlockId,
                    projectId = projectId,
                    dependsOnOperationId = dependency,
                    createdAt = NOW,
                ),
            )
            return result()
        }
        override fun unbindLocalBlock(
            session: Session,
            clientBlockId: String,
            operationId: String,
        ): BackgroundSyncResult {
            val dependency = cache.localBlockOperations.lastOrNull {
                it.clientBlockId == clientBlockId
            }?.operationId
            cache = cache.copy(
                localBlocks = cache.localBlocks.map {
                    if (it.clientBlockId == clientBlockId) {
                        it.copy(state = LocalBlockState.UNBOUND, intendedProjectId = null, boundProjectId = null)
                    } else {
                        it
                    }
                },
                localBlockOperations = cache.localBlockOperations + LocalBlockOperation(
                    operationId = operationId,
                    type = LocalBlockOperationType.UNBIND_BLOCK,
                    clientBlockId = clientBlockId,
                    dependsOnOperationId = dependency,
                    createdAt = NOW,
                ),
            )
            return result()
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
        ): BackgroundSyncResult {
            val summary = cache.pages.single { it.project.id == projectId }.project.copy(
                lifecycle = ProjectLifecycle.ARCHIVED,
                rowVersion = rowVersion + 1,
                archivedAt = NOW,
            )
            cache = cache.copy(
                activeProjects = cache.activeProjects.filterNot { it.id == projectId },
                archivedProjects = cache.archivedProjects.filterNot { it.id == projectId } + summary,
                pages = cache.pages.map { if (it.project.id == projectId) it.copy(project = summary) else it },
            )
            return result()
        }
        override fun queueReopenProject(
            session: Session,
            projectId: String,
            rowVersion: Int,
            operationId: String,
        ): BackgroundSyncResult {
            val summary = cache.pages.single { it.project.id == projectId }.project.copy(
                lifecycle = ProjectLifecycle.ACTIVE,
                rowVersion = rowVersion + 1,
                archivedAt = null,
            )
            cache = cache.copy(
                archivedProjects = cache.archivedProjects.filterNot { it.id == projectId },
                activeProjects = cache.activeProjects.filterNot { it.id == projectId } + summary,
                pages = cache.pages.map { if (it.project.id == projectId) it.copy(project = summary) else it },
            )
            return result()
        }
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
        override fun addNoteItem(
            session: Session,
            projectId: String,
            body: String,
            operationId: String,
            clientId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.CREATE_NOTE, clientId, projectId,
                clientResourceId = clientId, noteBody = body, createdAt = NOW),
        ) { current -> current.copy(noteItems = current.noteItems +
            ProjectNoteItem(clientId, projectId, body, false, "local", NOW, NOW)) }
        override fun editNoteItem(
            session: Session,
            noteId: String,
            body: String,
            operationId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.EDIT_NOTE, noteId,
                cache.noteItems.single { it.id == noteId }.projectId, noteBody = body, createdAt = NOW),
        ) { current -> current.copy(noteItems = current.noteItems.map {
            if (it.id == noteId) it.copy(body = body, updatedAt = NOW) else it
        }) }
        override fun setNoteItemPinned(
            session: Session,
            noteId: String,
            pinned: Boolean,
            operationId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.PIN_NOTE, noteId,
                cache.noteItems.single { it.id == noteId }.projectId, pinned = pinned, createdAt = NOW),
        ) { current -> current.copy(noteItems = current.noteItems.map {
            if (it.id == noteId) it.copy(pinned = pinned, updatedAt = NOW) else it
        }) }
        override fun deleteNoteItem(
            session: Session,
            noteId: String,
            operationId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.DELETE_NOTE, noteId,
                cache.noteItems.single { it.id == noteId }.projectId, createdAt = NOW),
        ) { current -> current.copy(noteItems = current.noteItems.map {
            if (it.id == noteId) it.copy(deletedAt = NOW, updatedAt = NOW) else it
        }) }
        override fun addResourceLink(
            session: Session,
            projectId: String,
            title: String,
            uri: String,
            operationId: String,
            clientId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.CREATE_LINK, clientId, projectId,
                clientResourceId = clientId, linkTitle = title, linkUri = uri, createdAt = NOW),
        ) { current -> current.copy(resourceLinks = current.resourceLinks +
            ProjectResourceLink(clientId, projectId, title, uri, NOW, NOW)) }
        override fun editResourceLink(
            session: Session,
            linkId: String,
            title: String,
            uri: String,
            operationId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.EDIT_LINK, linkId,
                cache.resourceLinks.single { it.id == linkId }.projectId,
                linkTitle = title, linkUri = uri, createdAt = NOW),
        ) { current -> current.copy(resourceLinks = current.resourceLinks.map {
            if (it.id == linkId) it.copy(title = title, uri = uri, updatedAt = NOW) else it
        }) }
        override fun deleteResourceLink(
            session: Session,
            linkId: String,
            operationId: String,
        ): BackgroundSyncResult = item(
            ProjectItemMutation(operationId, ProjectItemMutationType.DELETE_LINK, linkId,
                cache.resourceLinks.single { it.id == linkId }.projectId, createdAt = NOW),
        ) { current -> current.copy(resourceLinks = current.resourceLinks.map {
            if (it.id == linkId) it.copy(deletedAt = NOW, updatedAt = NOW) else it
        }) }
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
            coverBytes.keys.filter { it.first == session.tenantId }.toList().forEach { key ->
                coverBytes.remove(key)?.fill(0)
            }
        }

        private fun emptyCache() = ProjectCacheState(
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            blocksLoaded = true,
        )

        private fun updateBlock(
            clientBlockId: String,
            update: (LocalBlockRecord) -> LocalBlockRecord,
        ): LocalBlockRecord {
            val changed = update(cache.localBlocks.single { it.clientBlockId == clientBlockId })
            cache = cache.copy(
                localBlocks = cache.localBlocks.map { if (it.clientBlockId == clientBlockId) changed else it },
            )
            return changed
        }

        @Synchronized
        private fun queue(command: ProjectMutation): BackgroundSyncResult {
            mutations += command.type.name
            if (cache.commands.none { it.operationId == command.operationId }) {
                cache = cache.copy(commands = cache.commands + command)
            }
            return result()
        }

        @Synchronized
        private fun item(
            operation: ProjectItemMutation,
            update: (ProjectCacheState) -> ProjectCacheState,
        ): BackgroundSyncResult {
            mutations += operation.type.name
            cache = update(cache).copy(itemOperations = cache.itemOperations + operation)
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
            pendingBlockOperations = cache.localBlockOperations.size,
            pendingItemOperations = cache.itemOperations.size,
            )
        }
    }

    private class CacheProjectService : ProjectService {
        @Volatile
        var changeCalls = 0
        @Volatile
        var offline = false
        lateinit var changesEntered: CountDownLatch
        lateinit var allowChanges: CountDownLatch

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
            if (::changesEntered.isInitialized) {
                changesEntered.countDown()
                check(allowChanges.await(3, TimeUnit.SECONDS)) { "Timed out waiting to release changes" }
            }
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
        private const val CANONICAL_PROJECT_ID = "00000000-0000-0000-0000-000000000014"
        private const val OTHER_PROJECT_ID = "00000000-0000-0000-0000-000000000015"
        private const val NOTE_ITEM_ID = "10000000-0000-4000-8000-000000000012"
        private val OTHER_PROJECT_KEY = ProjectKey.parse("00000000-0000-0000-0000-000000000016")!!
        private val PROJECT_KEY = ProjectKey.parse("00000000-0000-0000-0000-000000000013")!!
        private val PROJECT_LINK = ProjectLink.create(PROJECT_KEY)
        private val TAG_A_CORRELATION = NfcPresentationCorrelation.create(byteArrayOf(1))!!
        private val TAG_B_CORRELATION = NfcPresentationCorrelation.create(byteArrayOf(2))!!
        private const val NOW = "2026-09-07T10:00:00Z"
        private val ACCOUNT_A = AccountIdentity("https://accounts.google.com", "account-a")
        private val ACCOUNT_B = AccountIdentity("https://accounts.google.com", "account-b")
        private val SESSION = Session(TENANT_ID, "cached-session", ACCOUNT_A)
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

        private fun parking(index: Int) = ProjectParking(
            id = "parking-$index",
            projectId = PROJECT_ID,
            location = "Place $index",
            nextAction = "Action $index",
            mustNotForget = "Remember $index",
            authoredByUserId = "user",
            createdAt = "2026-09-07T10:${index.toString().padStart(2, '0')}:00Z",
            occurredAt = "2026-09-07T10:${index.toString().padStart(2, '0')}:00Z",
        )
    }
}
