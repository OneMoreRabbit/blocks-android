package dev.blocks.android.cover

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.AndroidSyncStateStore
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import java.security.KeyStore
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedProjectCoverMediaStoreTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media by lazy { AndroidEncryptedProjectCoverMediaStore(context) }
    private val state by lazy { AndroidSyncStateStore(context) }

    @Before
    @After
    fun clean() {
        listOf(TENANT_A, TENANT_B).forEach { tenantId ->
            runCatching { media.eraseTenant(tenantId) }
            runCatching { state.clearSyncState(tenantId) }
        }
    }

    @Test
    fun encryptedObjectsStayOutsideSQLiteAndOrdinaryStateWritesLeaveTheirBytesUntouched() {
        val content = content(PROJECT_ID, "sensitive-display-a", "sensitive-thumbnail-a")
        val expectedDisplay = content.displayJpeg.copyOf()
        val cover = media.stage(TENANT_A, PROJECT_ID, content, null)
        content.clear()
        val project = project(TENANT_A)
        val initial = TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                pages = listOf(ProjectPage(project, ProjectNotes(PROJECT_ID, "Notes one", 1, NOW), null)),
                projectLinks = listOf(ProjectLinkIdentity(PROJECT_ID, PROJECT_KEY)),
                projectCovers = listOf(cover),
            ),
        )
        state.saveSyncState(TENANT_A, initial)
        val before = media.encryptedFiles(TENANT_A).associate { it.name to it.readBytes() }

        state.saveSyncState(
            TENANT_A,
            initial.copy(
                projectCache = initial.projectCache.copy(
                    pages = listOf(ProjectPage(project, ProjectNotes(PROJECT_ID, "Notes two", 2, NOW), null)),
                ),
            ),
        )

        val after = media.encryptedFiles(TENANT_A).associate { it.name to it.readBytes() }
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
        assertFalse(after.values.any { it.containsBytes(expectedDisplay) })
        val restoredCover = state.loadSyncState(TENANT_A).projectCache.projectCovers.single()
        assertEquals(cover, restoredCover)
        assertArrayEquals(expectedDisplay, media.read(TENANT_A, restoredCover, ProjectCoverVariant.DISPLAY))
        expectedDisplay.fill(0)
    }

    @Test
    fun immutableReplacementCleanupAndTenantErasureAreTenantSelective() {
        val firstContent = content(PROJECT_ID, "first-display", "first-thumbnail")
        val first = media.stage(TENANT_A, PROJECT_ID, firstContent, null)
        firstContent.clear()
        val replacementContent = content(PROJECT_ID, "replacement-display", "replacement-thumbnail")
        val replacement = media.stage(TENANT_A, PROJECT_ID, replacementContent, first)
        val replacementBytes = replacementContent.displayJpeg.copyOf()
        replacementContent.clear()
        val tenantBContent = content(PROJECT_ID, "replacement-display", "replacement-thumbnail")
        val tenantB = media.stage(TENANT_B, PROJECT_ID, tenantBContent, null)
        tenantBContent.clear()

        assertEquals(first.coverId, replacement.coverId)
        assertEquals(2, replacement.revision)
        assertEquals(4, media.encryptedFiles(TENANT_A).size)
        assertTrue(runCatching { media.read(TENANT_B, replacement, ProjectCoverVariant.DISPLAY) }.isFailure)
        assertFalse(
            media.encryptedFiles(TENANT_A).first().readBytes()
                .contentEquals(media.encryptedFiles(TENANT_B).first().readBytes()),
        )

        media.cleanupOrphans(TENANT_A, listOf(replacement))

        assertEquals(2, media.encryptedFiles(TENANT_A).size)
        assertTrue(runCatching { media.read(TENANT_A, first, ProjectCoverVariant.DISPLAY) }.isFailure)
        assertArrayEquals(replacementBytes, media.read(TENANT_A, replacement, ProjectCoverVariant.DISPLAY))

        media.eraseTenant(TENANT_A)

        assertTrue(media.encryptedFiles(TENANT_A).isEmpty())
        assertFalse(keyStore().containsAlias(mediaKeyAlias(TENANT_A)))
        assertArrayEquals(replacementBytes, media.read(TENANT_B, tenantB, ProjectCoverVariant.DISPLAY))
        assertTrue(keyStore().containsAlias(mediaKeyAlias(TENANT_B)))
        replacementBytes.fill(0)
    }

    private fun project(tenantId: String) = ProjectSummary(
        PROJECT_ID,
        tenantId,
        "Project",
        ProjectLifecycle.ACTIVE,
        1,
        NOW,
        NOW,
    )

    private fun content(projectId: String, displayValue: String, thumbnailValue: String): ProjectCoverContent {
        val display = displayValue.toByteArray()
        val thumbnail = thumbnailValue.toByteArray()
        return ProjectCoverContent(
            projectId,
            display,
            thumbnail,
            1200,
            675,
            480,
            270,
            digest(display),
            digest(thumbnail),
            digest(display, thumbnail),
            NOW,
        )
    }

    private fun mediaKeyAlias(tenantId: String) = "blocks-cover-media-v1-${digest(tenantId.toByteArray())}"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ByteArray.containsBytes(value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > size) return false
        return indices.take(size - value.size + 1).any { offset ->
            value.indices.all { index -> this[offset + index] == value[index] }
        }
    }

    private fun digest(vararg values: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TENANT_A = "00000000-0000-0000-0000-000000000071"
        const val TENANT_B = "00000000-0000-0000-0000-000000000072"
        const val PROJECT_ID = "00000000-0000-0000-0000-000000000073"
        const val NOW = "2026-09-15T15:00:00Z"
        val PROJECT_KEY = ProjectKey.parse("723e4567-e89b-42d3-a456-426614174000")!!
    }
}
