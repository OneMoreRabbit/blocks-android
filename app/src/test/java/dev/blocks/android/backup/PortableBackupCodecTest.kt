package dev.blocks.android.backup

import com.google.gson.Gson
import com.google.gson.JsonParser
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableBackupCodecTest {
    @Test
    fun unencryptedArchiveRoundTripsWithVersionAndChecksum() {
        val payload = payload()
        val bytes = PortableBackupCodec.encode(payload)
        assertTrue(String(bytes, StandardCharsets.ISO_8859_1).contains("private project name"))
        assertEquals(payload, PortableBackupCodec.decode(bytes))
        assertEquals(1, PortableBackupCodec.summary(payload).projects)
    }

    @Test
    fun truncationTamperAndOldFormatAreRejected() {
        val original = PortableBackupCodec.encode(payload())
        assertTrue(runCatching { PortableBackupCodec.decode(original.copyOf(original.size - 1)) }.isFailure)
        val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { PortableBackupCodec.decode(changed) }.isFailure)
        val old = original.copyOf().also { it[7] = '1'.code.toByte() }
        assertTrue(runCatching { PortableBackupCodec.decode(old) }.isFailure)
    }

    @Test
    fun recomputedChecksumCannotAuthorizeCrossTenantProject() {
        val original = PortableBackupCodec.encode(payload())
        val envelope = JsonParser.parseString(String(original, 44, original.size - 44, StandardCharsets.UTF_8))
            .asJsonObject
        envelope.getAsJsonObject("payload").getAsJsonObject("acceptedAccount")
            .addProperty("tenantId", "20000000-0000-4000-8000-000000000001")
        val payloadBytes = Gson().toJson(envelope.get("payload")).toByteArray(StandardCharsets.UTF_8)
        envelope.getAsJsonObject("manifest").addProperty(
            "payloadSha256", MessageDigest.getInstance("SHA-256").digest(payloadBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
        )
        val changedPayload = Gson().toJson(envelope).toByteArray(StandardCharsets.UTF_8)
        val changed = ByteBuffer.allocate(44 + changedPayload.size)
            .put("BLOCKSB2".toByteArray()).putInt(changedPayload.size)
            .put(MessageDigest.getInstance("SHA-256").digest(changedPayload)).put(changedPayload).array()
        assertTrue(runCatching { PortableBackupCodec.decode(changed) }.isFailure)
    }

    @Test
    fun duplicateJsonFieldIsRejectedEvenWithRecomputedOuterChecksum() {
        val original = PortableBackupCodec.encode(payload())
        val json = String(original, 44, original.size - 44, StandardCharsets.UTF_8)
        val duplicate = json.replaceFirst("\"manifest\":{", "\"manifest\":{\"formatVersion\":2,")
        val bytes = duplicate.toByteArray(StandardCharsets.UTF_8)
        val changed = ByteBuffer.allocate(44 + bytes.size)
            .put("BLOCKSB2".toByteArray()).putInt(bytes.size)
            .put(MessageDigest.getInstance("SHA-256").digest(bytes)).put(bytes).array()
        assertTrue(runCatching { PortableBackupCodec.decode(changed) }.isFailure)
    }

    @Test
    fun documentWriteOnlySucceedsAfterIndependentProviderReadback() {
        val payload = payload()
        val archive = PortableBackupCodec.encode(payload)
        val stored = ByteArrayOutputStream()
        BackupArchiveWriter.writeAndVerify(
            archive, payload,
            openWrite = { stored }, openRead = { ByteArrayInputStream(stored.toByteArray()) },
        )
        assertTrue(stored.toByteArray().contentEquals(archive))
    }

    @Test
    fun unavailableInterruptedTruncatedAndModifiedDocumentsNeverVerify() {
        val payload = payload()
        val archive = PortableBackupCodec.encode(payload)
        fun attempt(write: () -> OutputStream?, read: () -> ByteArrayInputStream?): Boolean =
            runCatching { BackupArchiveWriter.writeAndVerify(archive, payload, write, read) }.isFailure
        assertTrue(attempt({ null }, { ByteArrayInputStream(archive) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { null }))
        assertTrue(attempt({ object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("storage removed")
        } }, { ByteArrayInputStream(archive) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { ByteArrayInputStream(archive.copyOf(archive.size - 1)) }))
        assertTrue(attempt({ ByteArrayOutputStream() }, { ByteArrayInputStream(archive + 0.toByte()) }))
    }

    private fun payload(): PortableBackupPayload {
        val tenant = "10000000-0000-4000-8000-000000000001"
        val project = ProjectSummary(
            id = "20000000-0000-4000-8000-000000000001",
            tenantId = tenant,
            name = "private project name",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 3,
            createdAt = "2026-11-12T10:00:00Z",
            updatedAt = "2026-11-12T10:00:00Z",
        )
        return PortableBackupPayload(
            formatVersion = 2,
            appVersion = "0.10.2",
            exportedAt = "2026-11-12T10:00:00Z",
            acceptedAccount = AcceptedTenantContinuity(
                tenant, AccountIdentity("https://accounts.google.com", "subject-a"),
            ),
            state = SyncStateRowCodec().encode(TenantSyncState(
                projectCache = ProjectCacheState(activeProjects = listOf(project), activeProjectsLoaded = true),
            )),
            drafts = FormDrafts(projectName = "unsent draft"),
            coverMedia = emptyList(),
            excludedKnownOtherAccountPartitions = 1,
        )
    }
}
