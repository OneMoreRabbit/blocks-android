package dev.blocks.android.state

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.MainActivity
import dev.blocks.android.ProjectAppDependencies
import dev.blocks.android.ProjectUiIds
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AcceptedTenantContinuityStore
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.tag.BlocksNavigationAddress
import dev.blocks.android.tag.NfcClearResult
import dev.blocks.android.tag.NfcIoCancellation
import dev.blocks.android.tag.NfcPresentationCorrelation
import dev.blocks.android.tag.NfcProgrammingResult
import dev.blocks.android.tag.NfcTagHandle
import dev.blocks.android.tag.NfcTagPlatform
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalActionPerformanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidSyncStateStore
        get() = AndroidSyncStateStore(context)

    @Before
    fun setUp() {
        store.clearSyncState(TENANT)
        MainActivity.dependencyFactory = null
    }

    @After
    fun tearDown() {
        MainActivity.dependencyFactory = null
        store.clearSyncState(TENANT)
    }

    @Test
    fun realButtonsRenderSeededCreateAndParkResultsWithinBudget() {
        store.saveSyncState(TENANT, seededState(500))
        val workspace = ProjectSyncRepository(
            offlineService(),
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            coverMediaStore = AndroidEncryptedProjectCoverMediaStore(context),
        )
        val nextId = generateSequence(1) { it + 1 }.iterator()
        MainActivity.dependencyFactory = {
            ProjectAppDependencies(
                controller = ProjectPresentationController(
                    authenticator = { Session(TENANT, "") },
                    workspace = workspace,
                    newOperationId = { uuid("operation-${nextId.next()}") },
                ),
                identity = OfflineIdentity,
                continuity = MemoryContinuity(true),
                acceptedTenantStore = MemoryAcceptedTenantStore,
                observeConnectivity = false,
                nfcTagPlatform = DisabledNfc,
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            stabilizePerformancePhase()
            val createdNames = mutableListOf<String>()
            val createTimings = (1..25).map { index ->
                val name = "00 measured project ${(26 - index).toString().padStart(2, '0')}"
                createdNames += name
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(ProjectUiIds.CREATE_PROJECT).performClick()
                }
                waitFor(scenario) { it.activeDialogInput() != null }
                scenario.onActivity { activity -> activity.activeDialogInput()!!.setText(name) }
                measureUntilVisible(
                    scenario,
                    activate = { activity -> activity.dialogButton("Create").performClick() },
                    visible = { activity -> activity.hasVisibleText(name) },
                )
            }

            val projectName = createdNames.last()
            scenario.onActivity { activity -> activity.button(projectName).performClick() }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PARK) != null }
            stabilizePerformancePhase()
            val parkTimings = (1..25).map { index ->
                val location = "Visible bench $index"
                scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.PARK).performClick() }
                waitFor(scenario) { it.findViewById<View>(ProjectUiIds.SAVE_PARKING) != null }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText(location)
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Fit part $index")
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).setText("Keep note $index")
                }
                measureUntilVisible(
                    scenario,
                    activate = { activity -> activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING).performClick() },
                    visible = { activity ->
                        activity.findViewById<View>(ProjectUiIds.PARK) != null && activity.hasVisibleText(location)
                    },
                )
            }

            stabilizePerformancePhase()
            val noteTimings = (1..25).map { index ->
                val body = "Measured note $index"
                scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.ADD_NOTE).performClick() }
                waitFor(scenario) { it.findViewById<View>(ProjectUiIds.NOTE_SHEET) != null }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(ProjectUiIds.NOTE_BODY).setText(body)
                }
                measureUntilVisible(
                    scenario,
                    activate = { activity -> activity.findViewById<Button>(ProjectUiIds.SAVE_NOTE).performClick() },
                    visible = { activity ->
                        activity.findViewById<View>(ProjectUiIds.ADD_NOTE) != null && activity.hasVisibleText(body)
                    },
                )
            }

            stabilizePerformancePhase()
            val linkTimings = (1..25).map { index ->
                val title = "Measured link $index"
                scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.ADD_LINK).performClick() }
                waitFor(scenario) { it.findViewById<View>(ProjectUiIds.LINK_SHEET) != null }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(ProjectUiIds.LINK_TITLE).setText(title)
                    activity.findViewById<EditText>(ProjectUiIds.LINK_URI).setText("https://example.com/$index")
                }
                measureUntilVisible(
                    scenario,
                    activate = { activity -> activity.findViewById<Button>(ProjectUiIds.SAVE_LINK).performClick() },
                    visible = { activity ->
                        activity.findViewById<View>(ProjectUiIds.ADD_LINK) != null && activity.hasVisibleText(title)
                    },
                )
            }

            stabilizePerformancePhase()
            val linkReorderTimings = (1..25).map { sample ->
                var before = emptyList<String>()
                scenario.onActivity { activity -> before = activity.visibleLinkOrder() }
                measureUntilVisible(
                    scenario,
                    activate = { activity ->
                        val candidates = activity.buttonsWithDescriptionPrefix("Reorder ")
                        val move = candidates.firstOrNull { button ->
                            button.createAccessibilityNodeInfo().actionList.any {
                                it.label?.toString() == "Move link later"
                            }
                        } ?: error(
                            "No visible Link can move later at performance sample $sample; " +
                                "handles=${candidates.map { it.contentDescription to it.createAccessibilityNodeInfo().actionList.map { action -> action.label } }}",
                        )
                        move.performAccessibilityActionNamed("Move link later")
                    },
                    visible = { activity -> activity.visibleLinkOrder() != before },
                )
            }

            stabilizePerformancePhase()
            val parkDeleteTimings = (1..25).map { index ->
                lateinit var deleteButton: Button
                val setupLocation = "Delete setup $index"
                scenario.onActivity { activity -> activity.findViewById<Button>(ProjectUiIds.PARK).performClick() }
                waitFor(scenario) { it.findViewById<View>(ProjectUiIds.SAVE_PARKING) != null }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_LOCATION).setText(setupLocation)
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_NEXT_ACTION).setText("Prepare delete $index")
                    activity.findViewById<EditText>(ProjectUiIds.PARKING_MUST_NOT_FORGET).setText("Keep boundary $index")
                    activity.findViewById<Button>(ProjectUiIds.SAVE_PARKING).performClick()
                }
                waitFor(scenario) {
                    it.findViewById<View>(ProjectUiIds.PARK) != null && it.hasVisibleText(setupLocation)
                }
                scenario.onActivity { activity ->
                    deleteButton = activity.historicalParkingDeleteButton()
                    deleteButton.performClick()
                }
                waitFor(scenario) { it.activeDialog != null }
                measureUntilVisible(
                    scenario,
                    activate = { activity -> activity.dialogButton("Delete").performClick() },
                    visible = { !deleteButton.isAttachedToWindow },
                )
            }

            val create = createTimings.statistics()
            val park = parkTimings.statistics()
            val note = noteTimings.statistics()
            val link = linkTimings.statistics()
            val linkReorder = linkReorderTimings.statistics()
            val parkDelete = parkDeleteTimings.statistics()
            Log.i(TAG, "activity_create median=${create.median}ms p95=${create.p95}ms slowest=${create.slowest}ms samples=25")
            Log.i(TAG, "activity_park median=${park.median}ms p95=${park.p95}ms slowest=${park.slowest}ms samples=25")
            Log.i(TAG, "activity_add_note median=${note.median}ms p95=${note.p95}ms slowest=${note.slowest}ms samples=25")
            Log.i(TAG, "activity_add_link median=${link.median}ms p95=${link.p95}ms slowest=${link.slowest}ms samples=25")
            Log.i(TAG, "activity_reorder_link median=${linkReorder.median}ms p95=${linkReorder.p95}ms slowest=${linkReorder.slowest}ms samples=25")
            Log.i(TAG, "activity_delete_park median=${parkDelete.median}ms p95=${parkDelete.p95}ms slowest=${parkDelete.slowest}ms samples=25")
            assertTrue("Activity Create p95 was ${create.p95}ms", create.p95 < 100.0)
            assertTrue("Activity Park p95 was ${park.p95}ms", park.p95 < 100.0)
            assertTrue("Activity Add Note p95 was ${note.p95}ms", note.p95 < 100.0)
            assertTrue("Activity Add Link p95 was ${link.p95}ms", link.p95 < 100.0)
            assertTrue("Activity Link reorder p95 was ${linkReorder.p95}ms", linkReorder.p95 < 100.0)
            assertTrue("Activity Park delete p95 was ${parkDelete.p95}ms", parkDelete.p95 < 100.0)
        }
    }

    /** Keeps unrelated prior-phase garbage collection outside each measured button boundary. */
    private fun stabilizePerformancePhase() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Runtime.getRuntime().gc()
        System.runFinalization()
        Thread.sleep(250)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun measureUntilVisible(
        scenario: ActivityScenario<MainActivity>,
        activate: (MainActivity) -> Unit,
        visible: (MainActivity) -> Boolean,
    ): Double {
        var started = 0L
        scenario.onActivity { activity ->
            started = SystemClock.elapsedRealtimeNanos()
            activate(activity)
        }
        repeat(500) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var rendered = false
            scenario.onActivity { rendered = visible(it) }
            if (rendered) return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            Thread.sleep(1)
        }
        var diagnostic = "Activity unavailable"
        scenario.onActivity { activity ->
            val measured = activity.findViewById<ViewGroup>(android.R.id.content).descendants()
                .filterIsInstance<TextView>()
                .map { "${it.text}:${it.isShown}:${it.width}x${it.height}:${Rect().also(it::getGlobalVisibleRect)}" }
                .filter { it.contains("measured", ignoreCase = true) }
                .take(5)
                .toList()
            diagnostic = "screen=${activity.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null}, measured=$measured"
        }
        error("Timed out waiting for the committed result to become visible: $diagnostic")
    }

    private fun waitFor(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        repeat(500) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var met = false
            scenario.onActivity { met = condition(it) }
            if (met) return
            Thread.sleep(2)
        }
        error("Timed out waiting for Activity state")
    }

    private fun MainActivity.activeDialogInput(): EditText? = activeDialog?.window?.decorView
        ?.descendants()
        ?.filterIsInstance<EditText>()
        ?.singleOrNull()

    private fun MainActivity.dialogButton(text: String): Button = requireNotNull(activeDialog)
        .window!!.decorView.descendants().filterIsInstance<Button>().single { it.text.toString() == text }

    private fun Activity.button(text: String): Button = findViewById<ViewGroup>(android.R.id.content)
        .descendants().filterIsInstance<Button>().single { it.text.toString() == text }

    private fun Activity.buttonsWithDescription(description: String): List<Button> =
        findViewById<ViewGroup>(android.R.id.content)
            .descendants()
            .filterIsInstance<Button>()
            .filter {
                (it.contentDescription?.toString() == description || it.text.toString() == description) && it.isShown
            }
            .toList()

    private fun Activity.buttonsWithDescriptionPrefix(prefix: String): List<Button> =
        findViewById<ViewGroup>(android.R.id.content)
            .descendants()
            .filterIsInstance<Button>()
            .filter { it.contentDescription?.toString()?.startsWith(prefix) == true && it.isShown }
            .toList()

    private fun Button.performAccessibilityActionNamed(label: String) {
        val action = createAccessibilityNodeInfo().actionList.singleOrNull { it.label?.toString() == label }
            ?: error("Accessibility action '$label' is missing from $contentDescription")
        check(performAccessibilityAction(action.id, null))
    }

    private fun Activity.visibleLinkOrder(): List<String> = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<Button>()
        .filter { it.isShown && it.contentDescription?.toString()?.startsWith("Open Measured link ") == true }
        .map { it.contentDescription.toString() }
        .toList()

    private fun Activity.historicalParkingDeleteButton(): Button =
        requireNotNull(historicalParkingDeleteButtonOrNull())

    private fun Activity.historicalParkingDeleteButtonOrNull(): Button? =
        findViewById<ViewGroup>(ProjectUiIds.PROJECT_ACTIVITY).descendants()
            .filterIsInstance<Button>()
            .firstOrNull { it.contentDescription?.toString() == "Delete park message" }

    private fun Activity.newestParkingSignature(): String =
        findViewById<ViewGroup>(ProjectUiIds.NEWEST_PARKING).descendants()
            .filterIsInstance<TextView>()
            .joinToString("|") { it.text.toString() }

    private fun Activity.hasVisibleText(text: String): Boolean = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<TextView>()
        .any { view ->
            view.text.toString() == text && Rect().let { bounds -> view.getGlobalVisibleRect(bounds) && !bounds.isEmpty }
        }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun seededState(size: Int): TenantSyncState {
        val projects = (1..size).map { index ->
            val id = uuid("seed-project-$index")
            ProjectSummary(
                id = id,
                tenantId = TENANT,
                name = "Seed project $index",
                lifecycle = ProjectLifecycle.ACTIVE,
                rowVersion = 1,
                createdAt = NOW,
                updatedAt = NOW,
                clientId = id,
            )
        }
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = projects,
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = projects.map { project -> ProjectPage(project, ProjectNotes(project.id, "", 1, NOW), null) },
                parkingLoadedProjectIds = projects.map { it.id },
                projectLinks = projects.map { project ->
                    ProjectLinkIdentity(project.id, ProjectKey.parse(project.id)!!)
                },
            ),
            projectDraftsMigrated = true,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun offlineService(): ProjectService = Proxy.newProxyInstance(
        ProjectService::class.java.classLoader,
        arrayOf(ProjectService::class.java),
    ) { _, method, _ -> error("Foreground action called Service.${method.name}") } as ProjectService

    private fun List<Double>.statistics(): TimingStatistics {
        val ordered = sorted()
        val p95Index = ((ordered.size * 0.95).toInt()).coerceAtMost(ordered.lastIndex)
        return TimingStatistics(ordered[ordered.size / 2], ordered[p95Index], ordered.last())
    }

    private fun uuid(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private data class TimingStatistics(val median: Double, val p95: Double, val slowest: Double)

    private data class MemoryContinuity(override var shouldRestore: Boolean) : SessionContinuity

    private object MemoryAcceptedTenantStore : AcceptedTenantContinuityStore {
        private val accepted = AcceptedTenantContinuity(TENANT, AccountIdentity("https://accounts.google.com", "test"))
        override fun load() = accepted
        override fun find(accountIdentity: AccountIdentity) = accepted.takeIf { it.accountIdentity == accountIdentity }
        override fun save(continuity: AcceptedTenantContinuity) = Unit
        override fun lock() = Unit
        override fun remove(tenantId: String) = Unit
        override fun clear() = Unit
    }

    private object OfflineIdentity : IdentityProvider {
        override suspend fun signIn(activity: Activity): String = error("Identity is not part of the local timing journey")
        override suspend fun clear(activity: Activity) = Unit
    }

    private object DisabledNfc : NfcTagPlatform {
        override val isAvailable = false
        override val isEnabled = false
        override fun startReader(onTag: (NfcTagHandle) -> Unit) = Unit
        override fun stopReader() = Unit
        override fun programAndVerify(
            handle: NfcTagHandle,
            address: BlocksNavigationAddress,
            cancellation: NfcIoCancellation,
        ) = NfcProgrammingResult.Unsupported
        override fun verifyWritten(
            handle: NfcTagHandle,
            address: BlocksNavigationAddress,
            expectedCorrelation: NfcPresentationCorrelation,
            cancellation: NfcIoCancellation,
        ) = NfcProgrammingResult.Unsupported
        override fun clearAndVerify(handle: NfcTagHandle, cancellation: NfcIoCancellation) = NfcClearResult.Unsupported
        override fun verifyCleared(
            handle: NfcTagHandle,
            expectedCorrelation: NfcPresentationCorrelation,
            cancellation: NfcIoCancellation,
        ) = NfcClearResult.Unsupported
    }

    private companion object {
        const val TENANT = "00000000-0000-0000-0000-000000000711"
        const val NOW = "2026-09-15T12:00:00Z"
        const val TAG = "BlocksTiming"
    }
}
