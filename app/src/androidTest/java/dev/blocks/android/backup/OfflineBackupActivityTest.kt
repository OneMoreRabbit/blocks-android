package dev.blocks.android.backup

import android.accessibilityservice.AccessibilityService
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.MainActivity
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuityStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Activity's CreateDocument result, SAF streams, verification and cancellation. */
@RunWith(AndroidJUnit4::class)
class OfflineBackupActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store by lazy { AndroidSyncStateStore(context) }
    private val accepted by lazy { AndroidEncryptedAcceptedTenantContinuityStore(context) }

    @Before fun seed() {
        MainActivity.dependencyFactory = null
        store.saveSyncState(TENANT, TenantSyncState(projectCache = ProjectCacheState(
            activeProjects = listOf(ProjectSummary(PROJECT, TENANT, "SAF test", ProjectLifecycle.ACTIVE, 1, NOW, NOW)),
            activeProjectsLoaded = true,
        )))
        accepted.save(AcceptedTenantContinuity(TENANT, AccountIdentity("https://accounts.google.com", "saf-test")))
        SessionContinuityStore(context).shouldRestore = true
    }

    @After fun clean() {
        MainActivity.dependencyFactory = null
        SessionContinuityStore(context).shouldRestore = false
        accepted.clear()
        store.clearSyncState(TENANT)
    }

    @Test fun createDocumentWritesAndReadsBackFromActivityWithoutSync() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForOpen(scenario)
            startPicker(scenario)
            waitForPicker()
            clickPickerSave()
            waitForDialog(scenario, "Verified current-account export")
            var uri: android.net.Uri? = null
            scenario.onActivity { uri = it.lastBackupDestinationForTest }
            val destination = requireNotNull(uri)
            try {
                val bytes = requireNotNull(context.contentResolver.openInputStream(destination)).use { it.readBytes() }
                val payload = PortableBackupCodec.open(bytes, PASSPHRASE.toCharArray())
                assertEquals(TENANT, payload.acceptedAccount.tenantId)
                assertTrue(payload.state.activeProjects.single().contains("SAF test"))
                assertEquals(1, PortableBackupCodec.summary(payload).projects)
                assertEquals(1, store.loadSyncState(TENANT).projectCache.activeProjects.size)
                scenario.recreate()
                waitForOpen(scenario)
                assertEquals(1, store.loadSyncState(TENANT).projectCache.activeProjects.size)
            } finally {
                assertTrue(DocumentsContract.deleteDocument(context.contentResolver, destination))
            }
        }
    }

    @Test fun cancelledDocumentPickerDoesNotModifySource() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForOpen(scenario)
            val before = store.loadSyncState(TENANT)
            startPicker(scenario)
            waitForPicker()
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitForDialog(scenario, "Backup cancelled")
            assertEquals(before, store.loadSyncState(TENANT))
        }
    }

    @Test fun recreationWhilePickerIsOpenCannotReportVerifiedBackup() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForOpen(scenario)
            val before = store.loadSyncState(TENANT)
            var oldActivity: MainActivity? = null
            scenario.onActivity { oldActivity = it }
            startPicker(scenario)
            waitForPicker()
            instrumentation.runOnMainSync { requireNotNull(oldActivity).recreate() }
            clickPickerSave()
            waitForDialog(scenario, "Backup not created")
            assertEquals(before, store.loadSyncState(TENANT))
        }
    }

    private fun waitForOpen(scenario: ActivityScenario<MainActivity>) = waitUntil {
        var open = false
        scenario.onActivity { open = it.activeTenantForTest() == TENANT }
        open
    }

    private fun startPicker(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val method = MainActivity::class.java.getDeclaredMethod("showExportBackup").apply { isAccessible = true }
            method.invoke(activity)
            val fields = mutableListOf<EditText>()
            collectEdits(requireNotNull(activity.activeDialog?.window?.decorView), fields)
            assertEquals(2, fields.size)
            fields.forEach { it.setText(PASSPHRASE) }
            requireNotNull(activity.activeDialog).getButton(-1).performClick()
        }
    }

    private fun collectEdits(view: View, output: MutableList<EditText>) {
        if (view is EditText) output += view
        if (view is ViewGroup) (0 until view.childCount).forEach { collectEdits(view.getChildAt(it), output) }
    }

    private fun waitForDialog(scenario: ActivityScenario<MainActivity>, title: String) {
        var last = ""
        repeat(100) {
            var shown = false
            scenario.onActivity { activity ->
                val matching = arrayListOf<View>()
                activity.activeDialog?.window?.decorView?.findViewsWithText(
                    matching, title, View.FIND_VIEWS_WITH_TEXT,
                )
                shown = activity.activeDialog?.isShowing == true && matching.isNotEmpty()
                last = activity.activeDialog?.window?.decorView?.let { view ->
                    val texts = arrayListOf<android.widget.TextView>()
                    collectTexts(view, texts)
                    texts.joinToString(" | ") { it.text.toString() }
                } ?: "no active dialog"
            }
            if (shown) return
            Thread.sleep(100)
        }
        throw AssertionError("Expected $title; last dialog: $last")
    }

    private fun collectTexts(view: View, output: MutableList<android.widget.TextView>) {
        if (view is android.widget.TextView) output += view
        if (view is ViewGroup) (0 until view.childCount).forEach { collectTexts(view.getChildAt(it), output) }
    }

    private fun waitForPicker() = waitUntil {
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
    }

    private fun clickPickerSave() {
        var last = ""
        repeat(100) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(root, nodes)
                last = nodes.joinToString(" | ") { it.text?.toString().orEmpty() }
                val save = nodes.firstOrNull {
                    it.isClickable && it.text?.toString()?.uppercase() in setOf("SAVE", "CREATE")
                }
                if (save?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(100)
        }
        throw AssertionError("Document picker had no Save/Create action: $last")
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        output += node
        (0 until node.childCount).forEach { index -> node.getChild(index)?.let { collectNodes(it, output) } }
    }

    private fun waitUntil(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            Thread.sleep(100)
        }
        assertFalse("Timed out waiting for backup Activity result", true)
    }

    private companion object {
        const val TENANT = "91000000-0000-4000-8000-000000000001"
        const val PROJECT = "92000000-0000-4000-8000-000000000001"
        const val NOW = "2026-11-12T00:00:00Z"
        const val PASSPHRASE = "disposable-saf-passphrase"
    }
}
