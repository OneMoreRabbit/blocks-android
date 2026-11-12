package dev.blocks.android.backup

import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.state.SyncStateRowCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupCodecTest {
    private val passphrase = "long-owner-passphrase".toCharArray()

    @Test
    fun encryptedArchiveRoundTripsAllRowsWithoutPlaintextOrDeviceKey() {
        val payload = payload()
        val first = PortableBackupCodec.seal(payload, passphrase)
        val second = PortableBackupCodec.seal(payload, passphrase)

        assertFalse(first.contentEquals(second))
        assertFalse(String(first, StandardCharsets.ISO_8859_1).contains("private project name"))
        assertFalse(String(first, StandardCharsets.ISO_8859_1).contains("unsent draft"))
        assertEquals(payload, PortableBackupCodec.open(first, passphrase))
        assertEquals(payload, PortableBackupCodec.open(second, passphrase))
        assertEquals(1, PortableBackupCodec.summary(payload).projects)
    }

    @Test
    fun wrongPassphraseAndEveryArchiveSegmentTamperFailClosed() {
        val original = PortableBackupCodec.seal(payload(), passphrase)
        assertTrue(runCatching { PortableBackupCodec.open(original, "wrong passphrase".toCharArray()) }.isFailure)
        assertTrue(runCatching { PortableBackupCodec.open(original.copyOf(original.size - 1), passphrase) }.isFailure)
        listOf(12, 28, 40, original.lastIndex).forEach { offset ->
            val changed = original.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertTrue("tamper at $offset", runCatching { PortableBackupCodec.open(changed, passphrase) }.isFailure)
        }
    }

    @Test
    fun shortPassphraseAndOversizedPayloadCannotBeWritten() {
        assertTrue(runCatching { PortableBackupCodec.seal(payload(), "short".toCharArray()) }.isFailure)
        val oversized = payload().copy(drafts = FormDrafts(projectName = "x".repeat(64 * 1024 * 1024)))
        assertTrue(runCatching { PortableBackupCodec.seal(oversized, passphrase) }.isFailure)
    }

    @Test
    fun documentWriteOnlySucceedsAfterIndependentProviderReadback() {
        val payload = payload()
        val archive = PortableBackupCodec.seal(payload, passphrase)
        val stored = ByteArrayOutputStream()
        BackupArchiveWriter.writeAndVerify(
            archive, passphrase, payload,
            openWrite = { stored }, openRead = { ByteArrayInputStream(stored.toByteArray()) },
        )
        assertTrue(stored.toByteArray().contentEquals(archive))
    }

    @Test
    fun unavailableInterruptedTruncatedAndModifiedDocumentsNeverVerify() {
        val payload = payload()
        val archive = PortableBackupCodec.seal(payload, passphrase)
        fun attempt(write: () -> OutputStream?, read: () -> ByteArrayInputStream?): Boolean =
            runCatching { BackupArchiveWriter.writeAndVerify(archive, passphrase, payload, write, read) }.isFailure

        assertTrue(attempt({ null }, { ByteArrayInputStream(archive) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { null }))
        assertTrue(attempt({ object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("storage removed")
        } }, { ByteArrayInputStream(archive) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { ByteArrayInputStream(archive.copyOf(archive.size - 1)) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { ByteArrayInputStream(archive + 0.toByte()) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { ByteArrayInputStream(archive.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }) }))
    }

    private fun payload(): PortableBackupPayload {
        val project = ProjectSummary(
            id = "10000000-0000-4000-8000-000000000001",
            tenantId = "tenant-a",
            name = "private project name",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 3,
            createdAt = "2026-11-12T10:00:00Z",
            updatedAt = "2026-11-12T10:00:00Z",
        )
        return PortableBackupPayload(
            formatVersion = 1,
            appVersion = "0.10.2",
            exportedAt = "2026-11-12T10:00:00Z",
            acceptedAccount = AcceptedTenantContinuity(
                "tenant-a", AccountIdentity("https://accounts.google.com", "subject-a"),
            ),
            state = SyncStateRowCodec().encode(TenantSyncState(
                projectCache = ProjectCacheState(activeProjects = listOf(project), activeProjectsLoaded = true),
            )),
            drafts = FormDrafts(projectName = "unsent draft"),
            coverMedia = emptyList(),
            excludedProtectedBlockRecords = 0,
            excludedProtectedBlockOperations = 0,
            excludedKnownOtherAccountPartitions = 1,
        )
    }
}
