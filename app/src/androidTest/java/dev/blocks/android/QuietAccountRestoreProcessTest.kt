package dev.blocks.android

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.auth.IdentityProvider
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverVariant
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.NoteItemDraft
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.ProjectResourceLinkDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.BlockLookup
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectScreen
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.RegisteredBlock
import dev.blocks.android.project.SessionAuthenticator
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.state.SessionContinuityStore
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The UC-06 companion executes these methods in separate instrumentation processes with an
 * explicit force-stop between them. Their lexical order also keeps the ordinary full suite valid.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class QuietAccountRestoreProcessTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aSeedPersistentTenantForProcessDeath() {
        clearFixture()
        val image = jpeg()
        val media = AndroidEncryptedProjectCoverMediaStore(context)
        val content = ProjectCoverContent(
            projectId = PROJECT_ID,
            displayJpeg = image.copyOf(),
            thumbnailJpeg = image.copyOf(),
            displayWidth = 2,
            displayHeight = 2,
            thumbnailWidth = 2,
            thumbnailHeight = 2,
            displayDigest = digest(image),
            thumbnailDigest = digest(image),
            contentDigest = digest(image, image),
            importedAt = NOW,
        )
        val cover = media.stage(TENANT_ID, PROJECT_ID, content, null)
        content.clear()

        val project = ProjectSummary(
            id = PROJECT_ID,
            tenantId = TENANT_ID,
            name = PROJECT_NAME,
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 3,
            createdAt = NOW,
            updatedAt = NOW,
        )
        val parking = ProjectParking(
            id = PARK_ID,
            projectId = PROJECT_ID,
            location = "Persistent bench",
            nextAction = "Continue assembly",
            mustNotForget = "Torque wrench",
            authoredByUserId = "local-user",
            createdAt = NOW,
        )
        val drafts = FormDrafts(
            projectName = "Persistent create draft",
            projectNoteItems = mapOf(PROJECT_ID to NoteItemDraft("Persistent note draft")),
            projectResourceLinks = mapOf(
                PROJECT_ID to ProjectResourceLinkDraft("Persistent link draft", "https://example.test/draft"),
            ),
        )
        val cache = ProjectCacheState(
            activeProjects = listOf(project),
            activeProjectsLoaded = true,
            archivedProjectsLoaded = true,
            pages = listOf(ProjectPage(project, ProjectNotes(PROJECT_ID, "", 1, NOW), parking)),
            parkingRecords = listOf(parking),
            parkingLoadedProjectIds = listOf(PROJECT_ID),
            blocksLoaded = true,
            projectCovers = listOf(cover),
            noteItems = listOf(
                ProjectNoteItem(NOTE_ID, PROJECT_ID, "Persistent note", true, null, NOW, NOW, 1),
            ),
            resourceLinks = listOf(
                ProjectResourceLink(
                    LINK_ID,
                    PROJECT_ID,
                    "Persistent link",
                    "https://example.test/resource",
                    NOW,
                    NOW,
                    1,
                ),
            ),
            commands = listOf(
                ProjectMutation(
                    operationId = PROJECT_OPERATION_ID,
                    type = ProjectMutationType.RENAME_PROJECT,
                    targetId = PROJECT_ID,
                    projectId = PROJECT_ID,
                    ifMatch = 3,
                    projectName = PROJECT_NAME,
                    localCreatedAt = NOW,
                ),
            ),
            itemOperations = listOf(
                ProjectItemMutation(
                    operationId = ITEM_OPERATION_ID,
                    type = ProjectItemMutationType.EDIT_NOTE,
                    targetId = NOTE_ID,
                    projectId = PROJECT_ID,
                    ifMatch = 1,
                    noteBody = "Persistent note",
                    createdAt = NOW,
                ),
            ),
        )
        AndroidSyncStateStore(context).saveSyncState(
            TENANT_ID,
            TenantSyncState(
                projectCache = cache,
                projectDrafts = drafts,
                projectDraftsMigrated = true,
            ),
        )
        AndroidEncryptedDraftStore(context).save(TENANT_ID, drafts)
        AndroidEncryptedAcceptedTenantContinuityStore(context).save(
            AcceptedTenantContinuity(TENANT_ID, ACCOUNT),
        )
        persistRestoreAuthorization(true)

        assertEquals(PROJECT_NAME, AndroidSyncStateStore(context).loadSyncState(TENANT_ID)
            .projectCache.activeProjects.single().name)
        assertTrue(media.read(TENANT_ID, cover, ProjectCoverVariant.THUMBNAIL).contentEquals(image))
        image.fill(0)
    }

    @Test
    fun bRestorePersistentTenantAfterProcessDeathWithoutIdentityOrTransport() {
        val identityCalls = AtomicInteger()
        val serviceCalls = AtomicInteger()
        val controllerRef = AtomicReference<ProjectPresentationController>()
        MainActivity.dependencyFactory = { activity ->
            val controller = ProjectPresentationController(
                SessionAuthenticator {
                    serviceCalls.incrementAndGet()
                    error("Automatic restore must not exchange a Service session")
                },
                ProjectSyncRepository(
                    NeverCalledProjectService(serviceCalls),
                    NoOpLegacyQueueReplay,
                    AndroidEncryptedDraftStore(activity),
                    AndroidSyncStateStore(activity),
                    coverMediaStore = AndroidEncryptedProjectCoverMediaStore(activity),
                ),
            )
            controllerRef.set(controller)
            ProjectAppDependencies(
                controller = controller,
                identity = CountingIdentity(identityCalls),
                continuity = SessionContinuityStore(activity),
                acceptedTenantStore = AndroidEncryptedAcceptedTenantContinuityStore(activity),
                observeConnectivity = false,
            )
        }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitUntil { controllerRef.get()?.state?.screen == ProjectScreen.DASHBOARD }
                val controller = requireNotNull(controllerRef.get())
                assertTrue(controller.state.sessionConfirmationRequired)
                assertEquals(PROJECT_NAME, controller.state.activeProjects.single().name)
                assertEquals("Persistent note draft", controller.state.drafts.projectNoteItems[PROJECT_ID]?.body)
                assertEquals(2, pendingOperationCount())
                assertEquals(0, identityCalls.get())
                assertEquals(0, serviceCalls.get())

                scenario.onActivity { activity ->
                    assertTrue(visibleText(activity).contains(PROJECT_NAME))
                }

                val restored = AndroidSyncStateStore(context).loadSyncState(TENANT_ID).projectCache
                assertEquals("Persistent bench", restored.parkingRecords.single().location)
                assertEquals("Persistent note", restored.noteItems.single().body)
                assertEquals("Persistent link", restored.resourceLinks.single().title)
                val cover = restored.projectCovers.single()
                assertTrue(
                    AndroidEncryptedProjectCoverMediaStore(context)
                        .read(TENANT_ID, cover, ProjectCoverVariant.DISPLAY)
                        .isNotEmpty(),
                )
                assertEquals(0, identityCalls.get())
                assertEquals(0, serviceCalls.get())
                assertTrue(controller.state.pendingChanges)
                assertTrue(controller.state.sessionConfirmationRequired)
            }
        } finally {
            MainActivity.dependencyFactory = null
            clearFixture()
        }
    }

    private fun pendingOperationCount(): Int {
        val cache = AndroidSyncStateStore(context).loadSyncState(TENANT_ID).projectCache
        return cache.commands.size + cache.itemOperations.size
    }

    private fun clearFixture() {
        AndroidEncryptedDraftStore(context).clear(TENANT_ID)
        AndroidSyncStateStore(context).clearSyncState(TENANT_ID)
        AndroidEncryptedProjectCoverMediaStore(context).eraseTenant(TENANT_ID)
        AndroidEncryptedAcceptedTenantContinuityStore(context).clear()
        persistRestoreAuthorization(false)
    }

    private fun persistRestoreAuthorization(value: Boolean) {
        check(
            context.getSharedPreferences("session_continuity", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("restore_authorized_credential", value)
                .commit(),
        ) { "Could not persist the process-death restore fixture" }
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(41, 92, 68))
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun digest(vararg values: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .run {
            values.forEach(::update)
            digest().joinToString("") { "%02x".format(it) }
        }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for restored local state")
    }

    private fun visibleText(activity: Activity): List<String> {
        val values = mutableListOf<String>()
        fun walk(view: android.view.View) {
            if (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE) {
                values += view.text.toString()
            }
            if (view is android.view.ViewGroup) {
                repeat(view.childCount) { walk(view.getChildAt(it)) }
            }
        }
        walk(activity.window.decorView)
        return values
    }

    private class CountingIdentity(private val calls: AtomicInteger) : IdentityProvider {
        override suspend fun signIn(activity: Activity): String {
            calls.incrementAndGet()
            error("Automatic restore must not ask Google for an identity")
        }

        override suspend fun clear(activity: Activity) {
            calls.incrementAndGet()
            error("Automatic restore must not clear provider state")
        }
    }

    private class NeverCalledProjectService(private val calls: AtomicInteger) : ProjectService {
        private fun <T> unexpected(): T {
            calls.incrementAndGet()
            error("Automatic restore must not use transport")
        }

        override fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary> = unexpected()
        override fun createProject(session: Session, name: String, clientId: String?, idempotencyKey: String): ProjectSummary = unexpected()
        override fun projectPage(session: Session, projectId: String): ProjectPage = unexpected()
        override fun renameProject(session: Session, projectId: String, rowVersion: Int, name: String): ProjectSummary = unexpected()
        override fun archiveProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary = unexpected()
        override fun reopenProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary = unexpected()
        override fun notes(session: Session, projectId: String): ProjectNotes = unexpected()
        override fun saveNotes(session: Session, projectId: String, rowVersion: Int, body: String): ProjectNotes = unexpected()
        override fun addParking(
            session: Session,
            projectId: String,
            parking: ParkingDraft,
            supersedesId: String?,
            clientId: String?,
            occurredAt: String?,
            idempotencyKey: String,
        ): ProjectParking = unexpected()
        override fun parking(session: Session, projectId: String): List<ProjectParking> = unexpected()
        override fun blocks(session: Session): List<PhysicalBlock> = unexpected()
        override fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock = unexpected()
        override fun bindBlock(session: Session, blockId: String, projectId: String, idempotencyKey: String): BlockBinding = unexpected()
        override fun unbindBlock(session: Session, blockId: String) = unexpected<Unit>()
        override fun lookupBlock(session: Session, tagToken: String): BlockLookup = unexpected()
        override fun changes(session: Session, cursor: String?, limit: Int): ChangePage = unexpected()
    }

    companion object {
        private const val TENANT_ID = "90000000-0000-4000-8000-000000000006"
        private const val PROJECT_ID = "90000000-0000-4000-8000-000000000016"
        private const val PARK_ID = "90000000-0000-4000-8000-000000000026"
        private const val NOTE_ID = "90000000-0000-4000-8000-000000000036"
        private const val LINK_ID = "90000000-0000-4000-8000-000000000046"
        private const val PROJECT_OPERATION_ID = "90000000-0000-4000-8000-000000000056"
        private const val ITEM_OPERATION_ID = "90000000-0000-4000-8000-000000000066"
        private const val PROJECT_NAME = "Process-persistent project"
        private const val NOW = "2026-10-05T09:30:00Z"
        private val ACCOUNT = AccountIdentity("https://accounts.google.com", "uc-06-account")
    }
}
