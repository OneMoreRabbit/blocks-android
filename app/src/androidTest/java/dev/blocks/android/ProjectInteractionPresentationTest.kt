package dev.blocks.android

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import dev.blocks.android.project.DashboardView
import dev.blocks.android.project.DashboardOrderMode
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
import dev.blocks.android.project.ProjectUiPreferences
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
import kotlin.math.abs
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
                val firstHandle = activity.buttonWithDescription("Reorder First reference. Long press and drag.")
                assertTrue(
                    renderAlpha(requireNotNull(firstHandle.compoundDrawablesRelative[0])).contentEquals(
                        renderAlpha(requireNotNull(activity.getDrawable(R.drawable.ic_link))),
                    ),
                )
                val firstRow = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_LINKS).getChildAt(0) as ViewGroup
                assertEquals(4, firstRow.childCount)
                assertTrue(firstRow.getChildAt(0) === firstHandle)
                firstHandle.performAccessibilityActionNamed("Move link later")
            }
            waitFor(first) { it.visibleLinkTitles().firstOrNull() == "Second reference" }

            first.onActivity { activity ->
                assertTrue(
                    activity.findViewById<ViewGroup>(ProjectUiIds.NEWEST_PARKING).descendants()
                        .filterIsInstance<Button>()
                        .none { it.contentDescription?.toString() == "Delete park message" },
                )
                val destructive = activity.findViewById<ViewGroup>(android.R.id.content).descendants()
                    .filterIsInstance<Button>()
                    .filter { it.contentDescription?.toString()?.startsWith("Delete ") == true }
                    .toList()
                assertTrue(destructive.size >= 3)
                val minimumTouch = (48 * activity.resources.displayMetrics.density).toInt()
                assertTrue(destructive.all { button ->
                    Color.alpha(requireNotNull(button.compoundDrawableTintList).defaultColor) <= 72 &&
                        button.minimumWidth >= minimumTouch && button.minimumHeight >= minimumTouch &&
                        button.isEnabled && button.hasOnClickListeners()
                })
                val historicalPark = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                    .descendants().filterIsInstance<LinearLayout>()
                    .single { it.tag?.toString()?.startsWith("park:") == true }
                val halfLine = (8 * activity.resources.displayMetrics.density).toInt()
                assertEquals(halfLine, historicalPark.paddingTop)
                assertEquals(halfLine, historicalPark.paddingBottom)
                val lowerAction = historicalPark.getChildAt(historicalPark.childCount - 1) as LinearLayout
                assertTrue(lowerAction.getChildAt(0) is TextView)
                assertTrue((lowerAction.getChildAt(0) as TextView).text.toString().contains('/'))
                assertEquals(
                    "Delete park message",
                    lowerAction.descendants().filterIsInstance<Button>().single().contentDescription.toString(),
                )
                activity.historicalParkDeleteButton().performClick()
            }
            waitFor(first) { it.activeDialog != null }
            first.onActivity { it.dialogButton("Cancel").performClick() }
            assertTrue(store.loadSyncState(TENANT).projectCache.parkingTombstones.isEmpty())

            first.onActivity { activity -> activity.historicalParkDeleteButton().performClick() }
            waitFor(first) { it.activeDialog != null }
            first.onActivity { it.dialogButton("Delete").performClick() }
            waitFor(first) { store.loadSyncState(TENANT).projectCache.parkingTombstones.size == 1 }
            assertFalse(store.loadSyncState(TENANT).projectCache.parkingTombstones.single().parkingId == PARK_NEW)
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
                assertTrue(activity.visibleTexts().contains("Newest bench"))
                assertFalse(activity.visibleTexts().contains("Older bench"))
            }
            assertEquals(1, store.loadSyncState(TENANT).projectCache.parkingTombstones.size)
        }
    }

    @Test
    fun uc08DeadOverflowIsAbsentFromDashboardAndProjectChrome() {
        store.saveSyncState(TENANT, populatedState(parkingCount = 2))
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            scenario.onActivity { activity -> activity.assertNoDeadOverflowTarget() }
            scenario.openProject()
            scenario.onActivity { activity -> activity.assertNoDeadOverflowTarget() }
        }
    }

    @Test
    fun uc08DividersStayBetweenRecordsAndTheNotesParkBoundaryKeepsSectionSpacing() {
        val fixture = populatedState(parkingCount = 4).let { state ->
            state.copy(
                projectCache = state.projectCache.copy(
                    noteItems = state.projectCache.noteItems.take(3),
                ),
            )
        }
        store.saveSyncState(TENANT, fixture)
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity ->
                val noteList = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_NOTES)
                val noteRows = noteList.directChildren().filter { it.tag?.toString()?.startsWith("note:") == true }
                val noteDividers = noteList.directChildren().filter { it.id == ProjectUiIds.NOTE_HISTORY_DIVIDER }
                assertEquals(3, noteRows.size)
                assertEquals(2, noteDividers.size)

                val parkList = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                val parkRows = parkList.directChildren().filter { it.tag?.toString()?.startsWith("park:") == true }
                val parkDividers = parkList.directChildren().filter {
                    it.id == ProjectUiIds.PARKING_HISTORY_DIVIDER
                }
                assertEquals(3, parkRows.size)
                assertEquals(2, parkDividers.size)

                val linksHeading = activity.findViewById<ViewGroup>(ProjectUiIds.LINKS_SECTION)
                    .getChildAt(0) as TextView
                val notesHeading = activity.findViewById<ViewGroup>(ProjectUiIds.NOTES_SECTION)
                    .getChildAt(0) as TextView
                val parksHeading = activity.findViewById<ViewGroup>(ProjectUiIds.PARK_HISTORY_SECTION)
                    .getChildAt(0) as TextView
                assertEquals(linksHeading.paddingTop, notesHeading.paddingTop)
                assertEquals(notesHeading.paddingTop, parksHeading.paddingTop)
            }
        }
    }

    @Test
    fun uc08HistoryRowsUseHalfLinePaddingAndTwentyPercentSeparators() {
        val fixture = populatedState(parkingCount = 4).let { state ->
            state.copy(projectCache = state.projectCache.copy(noteItems = state.projectCache.noteItems.take(3)))
        }
        store.saveSyncState(TENANT, fixture)
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity ->
                val noteList = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_NOTES)
                val parkList = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                val rows = (noteList.directChildren() + parkList.directChildren()).filter {
                    val tag = it.tag?.toString().orEmpty()
                    tag.startsWith("note:") || tag.startsWith("park:")
                }
                val halfLine = (8 * activity.resources.displayMetrics.density).toInt()
                rows.forEach { row ->
                    assertEquals(halfLine, row.paddingTop)
                    assertEquals(halfLine, row.paddingBottom)
                }

                val separators = (noteList.descendants() + parkList.descendants()).filter { view ->
                    view !is ViewGroup && view.background is ColorDrawable &&
                        view.layoutParams?.height in 1..(2 * activity.resources.displayMetrics.density).toInt()
                }.toList()
                assertTrue(separators.isNotEmpty())
                val expectedLine = activity.getColor(R.color.blocks_line)
                separators.forEach { divider ->
                    val rendered = (divider.background as ColorDrawable).color
                    assertEquals(204, Color.alpha(rendered))
                    assertEquals(Color.red(expectedLine), Color.red(rendered))
                    assertEquals(Color.green(expectedLine), Color.green(rendered))
                    assertEquals(Color.blue(expectedLine), Color.blue(rendered))
                }
            }
        }
    }

    @Test
    fun uc08ParkDateAndDeleteShareTheFooterAndCancellationChangesNothing() {
        store.saveSyncState(TENANT, populatedState(parkingCount = 4))
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity ->
                val parkRows = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                    .directChildren().filter { it.tag?.toString()?.startsWith("park:") == true }
                assertEquals(3, parkRows.size)
                parkRows.forEach { row ->
                    val group = row as ViewGroup
                    val footer = group.getChildAt(group.childCount - 1) as ViewGroup
                    assertTrue(footer.getChildAt(0) is TextView)
                    assertTrue((footer.getChildAt(0) as TextView).text.toString().contains('/'))
                    assertEquals(
                        "Delete park message",
                        footer.descendants().filterIsInstance<Button>().single().contentDescription.toString(),
                    )
                }
                activity.historicalParkDeleteButton().performClick()
            }
            waitFor(scenario) { it.activeDialog != null }
            scenario.onActivity { it.dialogButton("Cancel").performClick() }
            assertTrue(store.loadSyncState(TENANT).projectCache.parkingTombstones.isEmpty())
        }
    }

    @Test
    fun uc09HistorySeparatorsAreTwentyPercentTransparent() {
        val fixture = populatedState(parkingCount = 4).let { state ->
            state.copy(projectCache = state.projectCache.copy(noteItems = state.projectCache.noteItems.take(3)))
        }
        store.saveSyncState(TENANT, fixture)
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity ->
                val noteList = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_NOTES)
                val parkList = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                val separators = noteList.directChildren().filter {
                    it.id == ProjectUiIds.NOTE_HISTORY_DIVIDER
                } + parkList.directChildren().filter {
                    it.id == ProjectUiIds.PARKING_HISTORY_DIVIDER
                }
                assertEquals(4, separators.size)
                val expectedLine = activity.getColor(R.color.blocks_line)
                separators.forEach { divider ->
                    val rendered = (divider.background as ColorDrawable).color
                    assertEquals(204, Color.alpha(rendered))
                    assertEquals(Color.red(expectedLine), Color.red(rendered))
                    assertEquals(Color.green(expectedLine), Color.green(rendered))
                    assertEquals(Color.blue(expectedLine), Color.blue(rendered))
                    assertEquals((1 * activity.resources.displayMetrics.density).toInt(), divider.height)
                }
            }
        }
    }

    @Test
    fun uc09ParkFooterIsFortyEightDpAndRenderedCentresAlignWithinOnePixel() {
        store.saveSyncState(TENANT, populatedState(parkingCount = 4))
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity ->
                val expectedHeight = (48 * activity.resources.displayMetrics.density).toInt()
                val parkRows = activity.findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY)
                    .directChildren().filter { it.tag?.toString()?.startsWith("park:") == true }
                assertEquals(3, parkRows.size)
                parkRows.forEach { row ->
                    val group = row as ViewGroup
                    val footer = group.getChildAt(group.childCount - 1) as ViewGroup
                    val date = footer.getChildAt(0) as TextView
                    val delete = footer.descendants().filterIsInstance<Button>()
                        .single { it.contentDescription?.toString() == "Delete park message" }
                    assertEquals(expectedHeight, footer.height)
                    assertEquals(expectedHeight, date.height)
                    assertEquals(expectedHeight, delete.height)
                    val dateCentre = date.renderedContentCentreOnScreen()
                    val deleteCentre = delete.renderedContentCentreOnScreen()
                    assertTrue(
                        "date centre=$dateCentre delete centre=$deleteCentre",
                        abs(dateCentre - deleteCentre) <= 1f,
                    )
                }
                activity.historicalParkDeleteButton().performClick()
            }
            waitFor(scenario) { it.activeDialog != null }
            scenario.onActivity { it.dialogButton("Cancel").performClick() }
            assertTrue(store.loadSyncState(TENANT).projectCache.parkingTombstones.isEmpty())
        }
    }

    @Test
    fun uc08NoteDividersExistOnlyBetweenVisibleNotes() {
        listOf(1 to 0, 2 to 1, 3 to 2).forEach { (noteCount, expectedDividers) ->
            store.clearSyncState(TENANT)
            val fixture = populatedState(parkingCount = 1).let { state ->
                state.copy(
                    projectCache = state.projectCache.copy(
                        noteItems = state.projectCache.noteItems.take(noteCount),
                    ),
                )
            }
            store.saveSyncState(TENANT, fixture)
            installDependencies()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.openProject()
                scenario.onActivity { activity ->
                    val noteList = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_NOTES)
                    assertEquals(
                        expectedDividers,
                        noteList.directChildren().count { it.id == ProjectUiIds.NOTE_HISTORY_DIVIDER },
                    )
                }
            }
        }
    }

    @Test
    fun writesUc08OneNoteAndMultipleHistoryReviewPngsOutsideTheAppRepository() {
        val output = requireNotNull(context.getExternalFilesDir("review-0.9.5"))
        output.mkdirs()
        val populated = populatedState(parkingCount = 4)
        val fixtures = listOf(
            "project-one-note" to populated.copy(
                projectCache = populated.projectCache.copy(
                    noteItems = populated.projectCache.noteItems.take(1),
                    parkingRecords = populated.projectCache.parkingRecords.takeLast(1),
                ),
            ),
            "project-multiple-notes-parks" to populated.copy(
                projectCache = populated.projectCache.copy(
                    noteItems = populated.projectCache.noteItems.take(3),
                ),
            ),
        )

        fixtures.forEach { (name, fixture) ->
            store.clearSyncState(TENANT)
            store.saveSyncState(TENANT, fixture)
            installDependencies()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.openProject()
                scenario.onActivity { activity -> activity.scrollProjectToExactText("Notes") }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val file = File(output, "$name.png")
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    FileOutputStream(file).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                }
                assertTrue(file.isFile && file.length() > 0)
                Log.i(TAG, "uc08-screenshot=$file")
            }
        }
    }

    @Test
    fun writesUc09MultipleParkFooterReviewPngOutsideTheAppRepository() {
        val output = requireNotNull(context.getExternalFilesDir("review-0.9.6"))
        output.mkdirs()
        store.saveSyncState(TENANT, populatedState(parkingCount = 4))
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.openProject()
            scenario.onActivity { activity -> activity.scrollProjectToExactText("Park History") }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val file = File(output, "project-multiple-park-footers.png")
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                FileOutputStream(file).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            assertTrue(file.isFile && file.length() > 0)
            Log.i(TAG, "uc09-screenshot=$file")
        }
    }

    @Test
    fun customProjectOrderUsesOnePersistedAggregateInTileAndListViews() {
        store.saveSyncState(
            TENANT,
            populatedState(parkingCount = 2).copy(
                projectUiPreferences = ProjectUiPreferences(
                    dashboardView = DashboardView.TILES,
                    dashboardOrderMode = DashboardOrderMode.CUSTOM,
                ),
            ),
        )
        installDependencies()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            scenario.onActivity { activity ->
                val first = activity.viewWithDescriptionPrefix("Open Workshop shelves")
                first.performAccessibilityActionNamed("Move project later")
            }
            waitFor(scenario) { it.visibleProjectOrder().firstOrNull() == "Reference cards" }
            scenario.onActivity { activity ->
                activity.findViewById<Button>(ProjectUiIds.VIEW_LIST).performClick()
            }
            waitFor(scenario) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }
            scenario.onActivity { activity ->
                assertEquals(
                    listOf("Reference cards", project().name, "Tooling refresh"),
                    activity.visibleProjectOrder(),
                )
                activity.viewWithDescriptionPrefix("Open Tooling refresh")
                    .performAccessibilityActionNamed("Move project earlier")
            }
            waitFor(scenario) {
                it.visibleProjectOrder() == listOf("Reference cards", "Tooling refresh", project().name)
            }
        }

        installDependencies()
        ActivityScenario.launch(MainActivity::class.java).use { restarted ->
            waitFor(restarted) { it.findViewById<View>(ProjectUiIds.PROJECT_LIST) != null }
            restarted.onActivity { activity ->
                assertEquals(
                    listOf("Reference cards", "Tooling refresh", project().name),
                    activity.visibleProjectOrder(),
                )
                activity.findViewById<Button>(ProjectUiIds.VIEW_TILES).performClick()
            }
            waitFor(restarted) { it.findViewById<View>(ProjectUiIds.PROJECT_TILES) != null }
            restarted.onActivity { activity ->
                assertEquals(
                    listOf("Reference cards", "Tooling refresh", project().name),
                    activity.visibleProjectOrder(),
                )
            }
        }
    }

    @Test
    fun writesDashboardAndProjectReviewPngsOutsideTheAppRepository() {
        val output = requireNotNull(context.getExternalFilesDir("review-0.9.1"))
        output.mkdirs()
        listOf(
            Triple("dashboard-tile", populatedState(parkingCount = 3), false),
            Triple(
                "dashboard-list",
                populatedState(parkingCount = 3).copy(
                    projectUiPreferences = ProjectUiPreferences(dashboardView = DashboardView.LIST),
                ),
                false,
            ),
            Triple("project-populated", populatedState(parkingCount = 3), true),
            Triple("project-long-history", populatedState(parkingCount = 24), true),
        ).forEach { (name, fixture, openProject) ->
            store.clearSyncState(TENANT)
            store.saveSyncState(TENANT, fixture)
            installDependencies()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if (openProject) scenario.openProject() else waitFor(scenario) {
                    it.screenTag() == ProjectUiIds.SCREEN_DASHBOARD
                }
                if (name == "project-long-history") {
                    scenario.onActivity { activity ->
                        activity.findViewById<ScrollView>(ProjectUiIds.ROOT_SCROLL).fullScroll(View.FOCUS_DOWN)
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val file = File(output, "$name.png")
                scenario.onActivity { activity ->
                    check(
                        activity.screenTag() == if (openProject) {
                            ProjectUiIds.SCREEN_PROJECT
                        } else {
                            ProjectUiIds.SCREEN_DASHBOARD
                        },
                    )
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

    @Test
    fun writesUc07NotesAndMultilineParkReviewPngsOutsideTheAppRepository() {
        val output = requireNotNull(context.getExternalFilesDir("review-0.9.4"))
        output.mkdirs()
        val fixture = populatedState(parkingCount = 4).let { state ->
            val records = state.projectCache.parkingRecords.map { record ->
                if (record.location == "Older bench") {
                    record.copy(
                        location = "Workshop shelving beside the north wall",
                        nextAction = "Measure the remaining bay\nCut the two uprights\nDry-fit before drilling",
                        mustNotForget = "Bring the long level\nKeep the labelled fixings together",
                    )
                } else {
                    record
                }
            }
            state.copy(
                projectCache = state.projectCache.copy(
                    parkingRecords = records,
                    noteItems = state.projectCache.noteItems.take(3),
                ),
            )
        }

        listOf(
            "project-notes-populated" to "Notes",
            "project-park-history-multiline" to "Park History",
        ).forEach { (name, targetText) ->
            store.clearSyncState(TENANT)
            store.saveSyncState(TENANT, fixture)
            installDependencies()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.openProject()
                scenario.onActivity { activity -> activity.scrollProjectToExactText(targetText) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val file = File(output, "$name.png")
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    FileOutputStream(file).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                }
                assertTrue(file.isFile && file.length() > 0)
                Log.i(TAG, "uc07-screenshot=$file")
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
                activeProjects = listOf(
                    project,
                    project().copy(id = PROJECT_B, clientId = PROJECT_B, name = "Reference cards"),
                    project().copy(id = PROJECT_C, clientId = PROJECT_C, name = "Tooling refresh"),
                ),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT, "", 1, NOW), parking.lastOrNull())),
                parkingRecords = parking,
                parkingLoadedProjectIds = listOf(PROJECT),
                noteItems = notes,
                resourceLinks = links,
                projectLinks = listOf(ProjectLinkIdentity(PROJECT, ProjectKey.parse(PROJECT)!!)),
                projectOrder = ProjectOrderState(listOf(PROJECT, PROJECT_B, PROJECT_C), 0, NOW),
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

    private fun MainActivity.scrollProjectToExactText(text: String) {
        val root = findViewById<ScrollView>(ProjectUiIds.ROOT_SCROLL)
        val target = findViewById<ViewGroup>(ProjectUiIds.CONTENT).descendants()
            .filterIsInstance<TextView>()
            .single { it.text.toString() == text }
        val bounds = Rect()
        target.getDrawingRect(bounds)
        root.offsetDescendantRectToMyCoords(target, bounds)
        val inset = (16 * resources.displayMetrics.density).toInt()
        root.scrollTo(0, (bounds.top - inset).coerceAtLeast(0))
    }

    private fun Activity.viewWithDescription(description: String): View =
        findViewById<ViewGroup>(android.R.id.content).descendants().single {
            it.contentDescription?.toString() == description && it.isShown
        }

    private fun Activity.viewWithDescriptionPrefix(prefix: String): View =
        findViewById<ViewGroup>(android.R.id.content).descendants().single {
            it.contentDescription?.toString()?.startsWith(prefix) == true && it.isShown
        }

    private fun Activity.buttonWithDescription(description: String): Button =
        buttonsWithDescription(description).single()

    private fun Activity.buttonsWithDescription(description: String): List<Button> =
        findViewById<ViewGroup>(android.R.id.content).descendants()
            .filterIsInstance<Button>()
            .filter { it.contentDescription?.toString() == description && it.isShown }
            .toList()

    private fun ViewGroup.directChildren(): List<View> =
        (0 until childCount).map(::getChildAt)

    private fun Activity.assertNoDeadOverflowTarget() {
        assertFalse(
            findViewById<ViewGroup>(ProjectUiIds.TOP_NAVIGATION).descendants().any {
                it.contentDescription?.toString() ==
                    "Saved on this device. Reconnect in Settings to synchronize."
            },
        )
    }

    private fun Activity.historicalParkDeleteButton(): Button =
        findViewById<ViewGroup>(ProjectUiIds.PARKING_HISTORY).descendants()
            .filterIsInstance<Button>()
            .first { it.contentDescription?.toString() == "Delete park message" }

    private fun View.performAccessibilityActionNamed(label: String) {
        val action = createAccessibilityNodeInfo().actionList.single { it.label?.toString() == label }
        check(performAccessibilityAction(action.id, null))
    }

    private fun Activity.visibleProjectOrder(): List<String> =
        findViewById<ViewGroup>(android.R.id.content).descendants()
            .filter { it.isShown && it.contentDescription?.toString()?.startsWith("Open ") == true }
            .map { it.contentDescription.toString().removePrefix("Open ").substringBefore(". Long press") }
            .filter { it in setOf(project().name, "Reference cards", "Tooling refresh") }
            .toList()

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

    private fun renderAlpha(drawable: android.graphics.drawable.Drawable): IntArray {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
            .map { Color.alpha(it) }.toIntArray().also { bitmap.recycle() }
    }

    private fun View.renderedContentCentreOnScreen(): Float {
        check(width > 0 && height > 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val occupiedRows = (0 until height).filter { y ->
            (0 until width).any { x -> Color.alpha(pixels[y * width + x]) > 0 }
        }
        check(occupiedRows.isNotEmpty())
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location[1] + (occupiedRows.first() + occupiedRows.last()) / 2f
    }

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
        const val PROJECT_B = "10000000-0000-4000-8000-000000000902"
        const val PROJECT_C = "10000000-0000-4000-8000-000000000903"
        const val LINK_A = "20000000-0000-4000-8000-000000000901"
        const val LINK_B = "20000000-0000-4000-8000-000000000902"
        const val PARK_NEW = "30000000-0000-4000-8000-000000000901"
        const val NOW = "2026-09-30T10:00:00Z"
        const val ONENOTE_URI = "onenote:page-id=1234&section-id=5678"
    }
}
