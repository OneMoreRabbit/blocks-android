package dev.blocks.android.backup

import androidx.test.core.app.ActivityScenario
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.MainActivity
import dev.blocks.android.ProjectUiIds
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverVariant
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuityStore
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.LocalTagResolution
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import com.google.gson.Gson
import dev.blocks.android.project.ProjectCover
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineBackupRestorerTest {
    private var importedTenantId: String? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state by lazy { AndroidSyncStateStore(context) }
    private val drafts by lazy { AndroidEncryptedDraftStore(context) }
    private val accepted by lazy { AndroidEncryptedAcceptedTenantContinuityStore(context) }
    private val media by lazy { AndroidEncryptedProjectCoverMediaStore(context) }
    private val marker by lazy { RestoredLocalAccountStore(context) }
    private val restorer by lazy { OfflineBackupRestorer(state, drafts, accepted, media, marker) }

    private val crossDevicePhase get() = InstrumentationRegistry.getArguments().getString("crossDevicePhase")
    private val interruptedPhase get() = InstrumentationRegistry.getArguments().getString("interruptedRestorePhase")

    @Before fun before() {
        if (crossDevicePhase != "verify" && interruptedPhase != "verify") clean()
    }

    @After fun after() {
        if (crossDevicePhase != "restore" && interruptedPhase != "seed") clean()
    }

    private fun clean() {
        MainActivity.dependencyFactory = null
        SessionContinuityStore(context).shouldRestore = false
        runCatching { marker.clear() }
        runCatching { media.eraseTenant(TENANT) }
        runCatching { drafts.clear(TENANT) }
        runCatching { state.clearSyncState(TENANT) }
        runCatching { accepted.clear() }
        importedTenantId?.takeIf { it != TENANT }?.let { tenant ->
            runCatching { media.eraseTenant(tenant) }
            runCatching { drafts.clear(tenant) }
            runCatching { state.clearSyncState(tenant) }
        }
    }

    @Test fun fullCurrentAccountRoundTripUsesFreshKeysAndNoRemoteAuthority() {
        val display = "restored-display".toByteArray()
        val thumbnail = "restored-thumbnail".toByteArray()
        val expectedDisplay = display.copyOf()
        val expectedThumbnail = thumbnail.copyOf()
        val content = ProjectCoverContent(
            PROJECT, display, thumbnail, 1200, 675, 480, 270,
            digest(display), digest(thumbnail), digest(display, thumbnail), NOW,
        )
        val cover = media.stage(TENANT, PROJECT, content, null)
        content.clear()
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { it.toByte() })
        val project = ProjectSummary(PROJECT, TENANT, "Restored workshop", ProjectLifecycle.ACTIVE, 2, NOW, NOW)
        val park = ProjectParking(PARK, PROJECT, "Workshop", "Fit frame", "Keep charger", "local", NOW)
        val pending = ProjectMutation(
            operationId = OPERATION, type = ProjectMutationType.ADD_PARKING, targetId = PROJECT,
            projectId = PROJECT, idempotencyKey = OPERATION, clientResourceId = PARK,
            parking = ParkingDraft(park.location, park.nextAction, park.mustNotForget), localCreatedAt = NOW,
        )
        val source = TenantSyncState(projectCache = ProjectCacheState(
            activeProjects = listOf(project), activeProjectsLoaded = true,
            parkingRecords = listOf(park), parkingLoadedProjectIds = listOf(PROJECT),
            noteItems = listOf(ProjectNoteItem(NOTE, PROJECT, "Do not forget", true, "local", NOW, NOW)),
            resourceLinks = listOf(ProjectResourceLink(LINK, PROJECT, "Plans", "https://example.invalid", NOW, NOW)),
            projectCovers = listOf(cover), commands = listOf(pending),
            localBlocks = listOf(LocalBlockRecord(BLOCK, token, "Tag", LocalBlockState.ACTIVE,
                PROJECT, boundProjectId = PROJECT, createdAt = NOW)),
            localBlockOperations = listOf(LocalBlockOperation(BLOCK_OP, LocalBlockOperationType.REGISTER_BLOCK,
                BLOCK, token, "Tag", createdAt = NOW)),
        ), changeCursor = "cursor-42")
        state.saveSyncState(TENANT, source)
        drafts.save(TENANT, FormDrafts(projectName = "unfinished"))
        accepted.save(AcceptedTenantContinuity(TENANT, AccountIdentity("https://accounts.google.com", "subject")))
        val payload = OfflineBackupExporter(state, drafts, accepted, media).capture(TENANT, "0.10.2")
        val archive = PortableBackupCodec.encode(payload)
        val before = state.loadSyncState(TENANT)

        // Simulates a different installation: remove source stores and their Keystore aliases.
        media.eraseTenant(TENANT)
        drafts.clear(TENANT)
        state.clearSyncState(TENANT)
        accepted.clear()
        restorer.restore(restorer.readAndValidate(ByteArrayInputStream(archive)))

        assertEquals(before, state.loadSyncState(TENANT))
        val reopened = state.loadSyncState(TENANT)
        assertEquals(before.projectCache.activeProjects, reopened.projectCache.activeProjects)
        assertEquals(before.projectCache.noteItems, reopened.projectCache.noteItems)
        assertEquals(before.projectCache.resourceLinks, reopened.projectCache.resourceLinks)
        assertEquals(before.projectCache.parkingRecords, reopened.projectCache.parkingRecords)
        assertEquals(before.projectCache.localBlocks, reopened.projectCache.localBlocks)
        assertEquals(before.projectCache.localBlockOperations, reopened.projectCache.localBlockOperations)
        assertEquals(before.projectCache.commands, reopened.projectCache.commands)
        assertEquals(before.projectCache.projectCovers, reopened.projectCache.projectCovers)
        assertEquals(before.changeCursor, reopened.changeCursor)
        assertEquals("unfinished", drafts.load(TENANT).projectName)
        assertEquals(expectedDisplay.toList(), media.read(TENANT, cover, ProjectCoverVariant.DISPLAY).toList())
        assertEquals(expectedThumbnail.toList(), media.read(TENANT, cover, ProjectCoverVariant.THUMBNAIL).toList())
        assertEquals(token, state.loadSyncState(TENANT).projectCache.localBlocks.single().token)
        assertEquals(1, PortableBackupCodec.summary(payload).pendingOperations)
        assertEquals(1, PortableBackupCodec.summary(payload).blockOperations)
        assertEquals(RestoredLocalAccountStore.Phase.ACTIVE, marker.read()?.phase)
        assertEquals(null, accepted.load()) // imported identity is never Service authority
        val secondArchive = PortableBackupCodec.encode(
            OfflineBackupExporter(state, drafts, accepted, media, marker).capture(TENANT, "0.10.2"),
        )
        assertEquals(payload.state, PortableBackupCodec.decode(secondArchive).state)
        assertTrue(runCatching { restorer.restore(payload) }.isFailure) // no overwrite
        SessionContinuityStore(context).shouldRestore = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForOpen(scenario)
            scenario.onActivity { assertFalse(it.canSynchronizeForTest()) }
            scenario.recreate()
            waitForOpen(scenario)
            scenario.onActivity { assertFalse(it.canSynchronizeForTest()) }
        }
        val afterReopen = state.loadSyncState(TENANT)
        assertEquals(before.projectCache.activeProjects, afterReopen.projectCache.activeProjects)
        assertEquals(before.projectCache.noteItems, afterReopen.projectCache.noteItems)
        assertEquals(before.projectCache.resourceLinks, afterReopen.projectCache.resourceLinks)
        assertEquals(before.projectCache.parkingRecords, afterReopen.projectCache.parkingRecords)
        assertEquals(before.projectCache.localBlocks, afterReopen.projectCache.localBlocks)
        assertEquals(before.projectCache.localBlockOperations, afterReopen.projectCache.localBlockOperations)
        assertEquals(before.projectCache.commands, afterReopen.projectCache.commands)
        assertEquals(before.projectCache.projectCovers, afterReopen.projectCache.projectCovers)
        assertEquals(before.changeCursor, afterReopen.changeCursor)
    }

    @Test fun preparingRestoreIsErasedOnNextStartAndNeverActive() {
        if (interruptedPhase != "verify") {
            marker.begin(AcceptedTenantContinuity(TENANT, AccountIdentity("issuer", "subject")))
            state.saveSyncState(TENANT, TenantSyncState(projectCache = ProjectCacheState(
                activeProjects = listOf(ProjectSummary(PROJECT, TENANT, "partial", ProjectLifecycle.ACTIVE, 1, NOW, NOW)),
            )))
        }
        if (interruptedPhase == "seed") return
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(100) {
                var recovered = false
                scenario.onActivity { recovered = it.activeTenantForTest() == null && marker.read() == null }
                if (recovered) return@use
                Thread.sleep(100)
            }
            throw AssertionError("Interrupted restore did not recover on next launch")
        }
        assertFalse(state.hasLocalState(TENANT))
        assertEquals(null, marker.read())
    }

    @Test fun anotherRetainedAccountMakesDestinationNonemptyEvenWhenArchiveTenantIsAbsent() {
        val account = AcceptedTenantContinuity(TENANT, AccountIdentity("issuer", "subject"))
        state.saveSyncState(TENANT, TenantSyncState(projectCache = ProjectCacheState(
            activeProjects = listOf(ProjectSummary(PROJECT, TENANT, "source", ProjectLifecycle.ACTIVE, 1, NOW, NOW)),
        )))
        accepted.save(account)
        val payload = OfflineBackupExporter(state, drafts, accepted, media).capture(TENANT, "0.10.2")
        accepted.clear()
        state.clearSyncState(TENANT)
        val otherTenant = "a1000000-0000-4000-8000-000000000099"
        try {
            state.saveSyncState(otherTenant, TenantSyncState(projectCache = ProjectCacheState(
                activeProjects = listOf(ProjectSummary(
                    "a2000000-0000-4000-8000-000000000099", otherTenant, "keep other",
                    ProjectLifecycle.ACTIVE, 1, NOW, NOW,
                )),
            )))
            val otherBefore = state.loadSyncState(otherTenant)
            assertTrue(runCatching { restorer.restore(payload) }.isFailure)
            assertFalse(state.hasLocalState(TENANT))
            assertEquals(otherBefore, state.loadSyncState(otherTenant))
            assertEquals(null, marker.read())
        } finally {
            state.clearSyncState(otherTenant)
        }
    }

    @Test fun interruptedActivationLeavesNoVisibleOrHalfRestoredAccount() {
        state.saveSyncState(TENANT, TenantSyncState(projectCache = ProjectCacheState(
            activeProjects = listOf(ProjectSummary(PROJECT, TENANT, "staged", ProjectLifecycle.ACTIVE, 1, NOW, NOW)),
        )))
        accepted.save(AcceptedTenantContinuity(TENANT, AccountIdentity("issuer", "subject")))
        val payload = OfflineBackupExporter(state, drafts, accepted, media).capture(TENANT, "0.10.2")
        accepted.clear()
        state.clearSyncState(TENANT)
        val failing = OfflineBackupRestorer(state, drafts, accepted, media, marker) {
            if (it == RestoreCheckpoint.BEFORE_ACTIVATION) error("simulated storage failure")
        }
        assertTrue(runCatching { failing.restore(payload) }.isFailure)
        assertFalse(state.hasLocalState(TENANT))
        assertEquals(null, marker.read())
        assertEquals(null, accepted.load())
    }

    @Test fun portableArchiveRestoresOnThisFreshInstallationAndAppReopensOffline() {
        val external = File(requireNotNull(context.getExternalFilesDir(null)),
            "disposable-offline-backup.blocksbackup")
        val archive = if (external.isFile) {
            external.readBytes()
        } else {
            val project = ProjectSummary(PROJECT, TENANT, "portable", ProjectLifecycle.ACTIVE, 1, NOW, NOW)
            state.saveSyncState(TENANT, TenantSyncState(projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT, "", 1, NOW), null)),
            )))
            accepted.save(AcceptedTenantContinuity(TENANT, AccountIdentity("issuer", "subject")))
            PortableBackupCodec.encode(OfflineBackupExporter(state, drafts, accepted, media)
                .capture(TENANT, "0.10.2")).also {
                accepted.clear()
                state.clearSyncState(TENANT)
            }
        }
        val payload = restorer.readAndValidate(ByteArrayInputStream(archive))
        importedTenantId = payload.acceptedAccount.tenantId
        if (crossDevicePhase != "verify") {
            assertFalse(accepted.hasAnyBindings())
            assertFalse(state.hasLocalState(payload.acceptedAccount.tenantId))
            restorer.restore(payload)
        } else {
            assertEquals(RestoredLocalAccountStore.Phase.ACTIVE, marker.read()?.phase)
        }
        val restoredRows = state.exportSnapshot(payload.acceptedAccount.tenantId).rows
        if (crossDevicePhase == "verify") {
            // Opening a cached Project may migrate its draft mirror, but must not alter
            // any domain row, operation identity, cursor, ordering, NFC or cover reference.
            val expected = payload.state.copy(
                projectDrafts = restoredRows.projectDrafts,
                projectDraftsMigrated = restoredRows.projectDraftsMigrated,
            )
            val expectedJson = Gson().toJsonTree(expected).asJsonObject
            val actualJson = Gson().toJsonTree(restoredRows).asJsonObject
            val changed = expectedJson.keySet().filter { expectedJson.get(it) != actualJson.get(it) }.toSet()
            // The source fixture has no explicit order rows. Normal startup materializes
            // their deterministic default projections; all other rows stay byte-identical.
            assertTrue("Unexpected post-reopen fields: $changed",
                changed.all { it in setOf("projectOrder", "linkOrders", "noteOrders") })
            val reopenedOrder = dev.blocks.android.state.SyncStateRowCodec().decode(restoredRows).projectCache
            assertEquals(listOf("21000000-0000-4000-8000-000000000001"),
                reopenedOrder.projectOrder.orderedProjectIds)
            assertEquals(listOf("51000000-0000-4000-8000-000000000001"),
                reopenedOrder.linkOrders.single().orderedLinkIds)
            assertEquals(listOf("41000000-0000-4000-8000-000000000001"),
                reopenedOrder.noteOrders.single().orderedNoteIds)
        } else {
            assertEquals(payload.state, restoredRows)
        }
        if (crossDevicePhase == "verify" && !payload.state.projectDraftsMigrated) {
            assertEquals(payload.drafts.projectName,
                state.loadSyncState(payload.acceptedAccount.tenantId).projectDrafts.projectName)
        } else {
            assertEquals(payload.drafts, drafts.load(payload.acceptedAccount.tenantId))
        }
        val covers = payload.state.projectCovers.map { Gson().fromJson(it, ProjectCover::class.java) }
        val variants = payload.coverMedia.associateBy { it.reference }
        covers.forEach { cover ->
            val display = Base64.getDecoder().decode(variants.getValue(cover.displayContentRef).bytesBase64)
            val thumbnail = Base64.getDecoder().decode(variants.getValue(cover.thumbnailContentRef).bytesBase64)
            assertTrue(display.contentEquals(media.read(payload.acceptedAccount.tenantId, cover,
                ProjectCoverVariant.DISPLAY)))
            assertTrue(thumbnail.contentEquals(media.read(payload.acceptedAccount.tenantId, cover,
                ProjectCoverVariant.THUMBNAIL)))
        }
        assertEquals(null, accepted.load())
        SessionContinuityStore(context).shouldRestore = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForOpen(scenario, payload.acceptedAccount.tenantId)
            scenario.onActivity { assertFalse(it.canSynchronizeForTest()) }
            var clicked = false
            for (attempt in 0 until 100) {
                scenario.onActivity { activity ->
                    val collection: ViewGroup? = activity.findViewById<ViewGroup>(ProjectUiIds.PROJECT_TILES)
                        ?: activity.findViewById(ProjectUiIds.PROJECT_LIST)
                    if (collection != null && collection.childCount > 0) {
                        clicked = collection.getChildAt(0).performClick()
                    }
                }
                if (clicked) break
                Thread.sleep(100)
            }
            assertTrue("Restored Project was not browsable", clicked)
            var projectOpened = false
            var screen = "unknown"
            for (attempt in 0 until 100) {
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    projectOpened = root.findViewWithTag<LinearLayout>(ProjectUiIds.SCREEN_PROJECT) != null
                    screen = when {
                        projectOpened -> "project"
                        root.findViewWithTag<LinearLayout>(ProjectUiIds.SCREEN_DASHBOARD) != null -> "dashboard"
                        else -> "other"
                    }
                }
                if (projectOpened) break
                Thread.sleep(100)
            }
            assertTrue("Restored Project did not open offline: $screen", projectOpened)
            payload.state.localBlocks.firstOrNull()?.let { encoded ->
                val block = Gson().fromJson(encoded, LocalBlockRecord::class.java)
                scenario.onActivity { activity ->
                    assertEquals(block.boundProjectId,
                        (activity.resolveTagLocallyForTest(block.token) as LocalTagResolution.Bound).projectId)
                }
            }
            scenario.recreate()
            waitForOpen(scenario, payload.acceptedAccount.tenantId)
            scenario.onActivity { assertFalse(it.canSynchronizeForTest()) }
        }
        assertEquals(payload.state.localBlocks, state.exportSnapshot(payload.acceptedAccount.tenantId).rows.localBlocks)
        assertEquals(payload.state.localBlockOperations,
            state.exportSnapshot(payload.acceptedAccount.tenantId).rows.localBlockOperations)
    }

    private fun waitForOpen(scenario: ActivityScenario<MainActivity>, tenant: String = TENANT) {
        repeat(100) {
            var opened = false
            scenario.onActivity { opened = it.activeTenantForTest() == tenant }
            if (opened) return
            Thread.sleep(100)
        }
        throw AssertionError("Restored account did not reopen locally")
    }

    private fun digest(vararg values: ByteArray): String = MessageDigest.getInstance("SHA-256").run {
        values.forEach(::update)
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TENANT = "a1000000-0000-4000-8000-000000000001"
        const val PROJECT = "a2000000-0000-4000-8000-000000000001"
        const val PARK = "a3000000-0000-4000-8000-000000000001"
        const val NOTE = "a4000000-0000-4000-8000-000000000001"
        const val LINK = "a5000000-0000-4000-8000-000000000001"
        const val OPERATION = "a6000000-0000-4000-8000-000000000001"
        const val BLOCK = "a7000000-0000-4000-8000-000000000001"
        const val BLOCK_OP = "a8000000-0000-4000-8000-000000000001"
        const val NOW = "2026-11-12T10:00:00Z"
    }
}
