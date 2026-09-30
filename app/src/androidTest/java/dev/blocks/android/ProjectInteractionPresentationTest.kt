package dev.blocks.android

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AcceptedTenantContinuityStore
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectOrderState
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuity
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectInteractionPresentationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidSyncStateStore
        get() = AndroidSyncStateStore(context)
    private val operations = AtomicInteger()
    private val launchedLinks = mutableListOf<String>()

    @Before
    fun setUp() {
        store.clearSyncState(TENANT)
        MainActivity.dependencyFactory = null
        launchedLinks.clear()
    }

    @After
    fun tearDown() {
        MainActivity.dependencyFactory = null
        store.clearSyncState(TENANT)
    }

    @Test
    fun oneNoteLaunchLinkReorderAndConfirmedParkDeleteRemainLocalAcrossRestart() {
        store.saveSyncState(TENANT, populatedState(parkingCount = 2))
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { first ->
            first.openProject()
            assertTrue(launchedLinks.isEmpty())
            first.onActivity { activity -> activity.buttonWithDescription("Open First reference").performClick() }
            assertEquals(listOf(ONENOTE_URI), launchedLinks)

            first.onActivity { activity ->
                activity.buttonsWithDescription("Move link down").first { it.isEnabled }.performClick()
            }
            waitFor(first) { it.visibleLinkTitles().firstOrNull() == "Second reference" }

            first.onActivity { activity -> activity.newestParkDeleteButton().performClick() }
            waitFor(first) { it.activeDialog != null }
            first.onActivity { it.dialogButton("Cancel").performClick() }
            assertTrue(store.loadSyncState(TENANT).projectCache.parkingTombstones.isEmpty())

            first.onActivity { activity -> activity.newestParkDeleteButton().performClick() }
            waitFor(first) { it.activeDialog != null }
            first.onActivity { it.dialogButton("Delete").performClick() }
            waitFor(first) { store.loadSyncState(TENANT).projectCache.parkingTombstones.size == 1 }
            assertEquals(PARK_NEW, store.loadSyncState(TENANT).projectCache.parkingTombstones.single().parkingId)
            assertTrue(
                store.loadSyncState(TENANT).projectCache.parkingRecords
                    .any { it.id == PARK_NEW && it.location == "Newest bench" },
            )
        }

        installDependencies()
        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            restarted.openProject()
            restarted.onActivity { activity ->
                assertEquals(listOf("Second reference", "First reference"), activity.visibleLinkTitles())
                assertFalse(activity.visibleTexts().contains("Newest bench"))
                assertTrue(activity.visibleTexts().contains("Older bench"))
            }
            assertEquals(1, store.loadSyncState(TENANT).projectCache.parkingTombstones.size)
        }
    }

    @Test
    fun writesPopulatedEmptyAndLongHistoryReviewPngsOutsideTheAppRepository() {
        val output = requireNotNull(context.getExternalFilesDir("review-0.9.0"))
        output.mkdirs()
        listOf(
            "project-populated" to populatedState(parkingCount = 3),
            "project-empty" to emptyState(),
            "project-long-history" to populatedState(parkingCount = 24),
        ).forEach { (name, fixture) ->
            store.clearSyncState(TENANT)
            store.saveSyncState(TENANT, fixture)
            installDependencies()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.openProject()
                if (name == "project-long-history") {
                    scenario.onActivity { activity ->
                        activity.findViewById<ScrollView>(ProjectUiIds.ROOT_SCROLL).fullScroll(View.FOCUS_DOWN)
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val file = File(output, "$name.png")
                scenario.onActivity { activity ->
                    check(activity.screenTag() == ProjectUiIds.SCREEN_PROJECT)
                    val decor = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    FileOutputStream(file).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                }
                assertTrue(file.isFile && file.length() > 0)
                Log.i(TAG, "screenshot=$file")
            }
        }
    }

    private fun installDependencies() {
        MainActivity.dependencyFactory = { activity ->
            val repository = ProjectSyncRepository(
                noTransportService(),
                NoOpLegacyQueueReplay,
                NoOpDraftStore,
                AndroidSyncStateStore(activity),
                coverMediaStore = AndroidEncryptedProjectCoverMediaStore(activity),
            )
            ProjectAppDependencies(
                controller = ProjectPresentationController(
                    authenticator = { Session(TENANT, "") },
                    workspace = repository,
                    newOperationId = { uuid("interaction-${operations.incrementAndGet()}") },
                ),
                identity = OfflineIdentity,
                continuity = MemoryContinuity(true),
                acceptedTenantStore = MemoryAcceptedTenantStore,
                observeConnectivity = false,
                resourceLinkLauncher = { uri -> launchedLinks += uri; true },
            )
        }
    }

    private fun populatedState(parkingCount: Int): TenantSyncState {
        val project = project()
        val parking = (1..parkingCount).map { index ->
            ProjectParking(
                id = if (index == parkingCount) PARK_NEW else uuid("parking-$index"),
                projectId = PROJECT,
                location = if (index == parkingCount) "Newest bench" else if (index == parkingCount - 1) {
                    "Older bench"
                } else {
                    "Bench $index"
                },
                nextAction = "Fit part $index",
                mustNotForget = "Keep note $index",
                authoredByUserId = "user-a",
                createdAt = "2026-09-${index.toString().padStart(2, '0')}T10:00:00Z",
                occurredAt = "2026-09-${index.toString().padStart(2, '0')}T10:00:00Z",
                clientId = if (index == parkingCount) PARK_NEW else uuid("parking-$index"),
            )
        }
        val links = listOf(
            ProjectResourceLink(LINK_A, PROJECT, "First reference", ONENOTE_URI, NOW, NOW),
            ProjectResourceLink(LINK_B, PROJECT, "Second reference", "https://example.com/second", NOW, NOW),
        )
        val notes = (1..12).map { index ->
            ProjectNoteItem(
                id = uuid("note-$index"),
                projectId = PROJECT,
                body = "History note $index",
                pinned = index == 1,
                authoredByUserId = null,
                createdAt = "2026-08-${index.toString().padStart(2, '0')}T09:00:00Z",
                updatedAt = "2026-08-${index.toString().padStart(2, '0')}T09:00:00Z",
            )
        }
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT, "", 1, NOW), parking.lastOrNull())),
                parkingRecords = parking,
                parkingLoadedProjectIds = listOf(PROJECT),
                noteItems = notes,
                resourceLinks = links,
                projectLinks = listOf(ProjectLinkIdentity(PROJECT, ProjectKey.parse(PROJECT)!!)),
                projectOrder = ProjectOrderState(listOf(PROJECT), 0, NOW),
                linkOrders = listOf(LinkOrderState(PROJECT, links.map { it.id }, 0, NOW)),
            ),
            projectDraftsMigrated = true,
        )
    }

    private fun emptyState(): TenantSyncState {
        val project = project()
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT, "", 1, NOW), null)),
                parkingLoadedProjectIds = listOf(PROJECT),
                projectLinks = listOf(ProjectLinkIdentity(PROJECT, ProjectKey.parse(PROJECT)!!)),
                projectOrder = ProjectOrderState(listOf(PROJECT), 0, NOW),
                linkOrders = listOf(LinkOrderState(PROJECT, emptyList(), 0, NOW)),
            ),
            projectDraftsMigrated = true,
        )
    }

    private fun project() = ProjectSummary(
        id = PROJECT,
        tenantId = TENANT,
        name = "Workshop shelves with a deliberately long Project title",
        lifecycle = ProjectLifecycle.ACTIVE,
        rowVersion = 1,
        createdAt = NOW,
        updatedAt = NOW,
        clientId = PROJECT,
    )

    private fun ActivityScenario<MainActivity>.openProject() {
        waitFor(this) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
        onActivity { activity ->
            activity.viewWithDescription("Open ${project().name}").performClick()
        }
        waitFor(this) { it.screenTag() == ProjectUiIds.SCREEN_PROJECT }
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

    private fun MainActivity.screenTag() = findViewById<ViewGroup>(ProjectUiIds.CONTENT).tag as? Int

    private fun Activity.viewWithDescription(description: String): View =
        findViewById<ViewGroup>(android.R.id.content).descendants().single {
            it.contentDescription?.toString() == description && it.isShown
        }

    private fun Activity.buttonWithDescription(description: String): Button =
        buttonsWithDescription(description).single()

    private fun Activity.buttonsWithDescription(description: String): List<Button> =
        findViewById<ViewGroup>(android.R.id.content).descendants()
            .filterIsInstance<Button>()
            .filter { it.contentDescription?.toString() == description && it.isShown }
            .toList()

    private fun Activity.newestParkDeleteButton(): Button =
        findViewById<ViewGroup>(ProjectUiIds.NEWEST_PARKING).descendants()
            .filterIsInstance<Button>()
            .single { it.text.toString() == "Delete park message" }

    private fun Activity.visibleLinkTitles(): List<String> = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<Button>()
        .filter { it.isShown && it.contentDescription?.toString()?.startsWith("Open ") == true }
        .map { it.text.toString() }
        .filter { it.endsWith("reference") }
        .toList()

    private fun Activity.visibleTexts(): List<String> = findViewById<ViewGroup>(android.R.id.content)
        .descendants()
        .filterIsInstance<TextView>()
        .filter { view -> Rect().let { view.isShown && view.getGlobalVisibleRect(it) && !it.isEmpty } }
        .map { it.text.toString() }
        .toList()

    private fun MainActivity.dialogButton(text: String): Button = requireNotNull(activeDialog)
        .window!!.decorView.descendants().filterIsInstance<Button>().single { it.text.toString() == text }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun noTransportService(): ProjectService = Proxy.newProxyInstance(
        ProjectService::class.java.classLoader,
        arrayOf(ProjectService::class.java),
    ) { _, method, _ -> error("Foreground interaction called Service.${method.name}") } as ProjectService

    private fun uuid(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()

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
        override suspend fun signIn(activity: Activity): String = error("Identity is not part of this local journey")
        override suspend fun clear(activity: Activity) = Unit
    }

    private companion object {
        const val TAG = "BlocksInteraction"
        const val TENANT = "00000000-0000-0000-0000-000000000901"
        const val PROJECT = "10000000-0000-4000-8000-000000000901"
        const val LINK_A = "20000000-0000-4000-8000-000000000901"
        const val LINK_B = "20000000-0000-4000-8000-000000000902"
        const val PARK_NEW = "30000000-0000-4000-8000-000000000901"
        const val NOW = "2026-09-30T10:00:00Z"
        const val ONENOTE_URI = "onenote:https://example.com/Notebook/Section.one#Page"
    }
}
