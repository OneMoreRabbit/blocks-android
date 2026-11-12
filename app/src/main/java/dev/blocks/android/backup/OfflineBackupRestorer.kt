package dev.blocks.android.backup

import com.google.gson.Gson
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverVariant
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.state.AndroidEncryptedDraftStore
import dev.blocks.android.state.AndroidSyncStateStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/** Activates a fully verified, fresh local partition only after every store has been read back. */
internal class OfflineBackupRestorer(
    private val state: AndroidSyncStateStore,
    private val drafts: AndroidEncryptedDraftStore,
    private val accepted: AndroidEncryptedAcceptedTenantContinuityStore,
    private val media: AndroidEncryptedProjectCoverMediaStore,
    private val marker: RestoredLocalAccountStore,
    private val checkpoint: (RestoreCheckpoint) -> Unit = {},
) {
    fun readAndValidate(input: InputStream): PortableBackupPayload {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            require(out.size().toLong() + count <= PortableBackupCodec.MAX_PAYLOAD_BYTES + 44L) {
                "Backup exceeds the supported size"
            }
            out.write(chunk, 0, count)
        }
        return PortableBackupCodec.decode(out.toByteArray())
    }

    /** Only an empty installation can accept a restore; no existing account is replaced. */
    @Synchronized
    fun restore(payload: PortableBackupPayload) {
        PortableBackupValidator.validate(payload)
        val tenant = payload.acceptedAccount.tenantId
        require(!accepted.hasAnyBindings() && marker.read() == null &&
            !state.hasAnyLocalState() && !drafts.hasAnySavedDraft() && !media.hasAnyMedia()) {
            "This installation already contains local data; restore needs an empty installation"
        }
        marker.begin(payload.acceptedAccount)
        try {
            state.restoreEmptySnapshot(tenant, payload.state)
            checkpoint(RestoreCheckpoint.DATABASE_STAGED)
            drafts.save(tenant, payload.drafts)
            val covers = payload.state.projectCovers.map { Gson().fromJson(it, ProjectCover::class.java) }
            val bytesByReference = payload.coverMedia.associate { it.reference to Base64.getDecoder().decode(it.bytesBase64) }
            covers.forEach { cover ->
                listOf(
                    ProjectCoverVariant.DISPLAY to cover.displayContentRef,
                    ProjectCoverVariant.THUMBNAIL to cover.thumbnailContentRef,
                ).forEach { (variant, reference) ->
                    val bytes = bytesByReference.getValue(reference)
                    try {
                        media.restoreVariant(tenant, cover, variant, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
            check(state.exportSnapshot(tenant).rows == payload.state) { "Restored database did not read back" }
            check(drafts.load(tenant) == payload.drafts) { "Restored drafts did not read back" }
            checkpoint(RestoreCheckpoint.BEFORE_ACTIVATION)
            marker.activate()
        } catch (error: Exception) {
            eraseIncomplete(tenant)
            throw IllegalStateException("Restore did not activate; existing data was not replaced", error)
        }
    }

    /** A crash before the final marker commit never exposes a half-restored account. */
    @Synchronized
    fun recoverInterruptedRestore() {
        val entry = marker.read() ?: return
        if (entry.phase == RestoredLocalAccountStore.Phase.PREPARING) {
            eraseIncomplete(entry.account.tenantId)
        }
    }

    private fun eraseIncomplete(tenant: String) {
        val failures = mutableListOf<Throwable>()
        runCatching { media.eraseTenant(tenant) }.exceptionOrNull()?.let(failures::add)
        runCatching { drafts.clear(tenant) }.exceptionOrNull()?.let(failures::add)
        runCatching { state.clearSyncState(tenant) }.exceptionOrNull()?.let(failures::add)
        if (failures.isEmpty()) marker.clear()
        failures.firstOrNull()?.let { error ->
            failures.drop(1).forEach(error::addSuppressed)
            throw IllegalStateException("Could not clear interrupted restore", error)
        }
    }
}

internal enum class RestoreCheckpoint { DATABASE_STAGED, BEFORE_ACTIVATION }
