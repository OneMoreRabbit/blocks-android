package dev.blocks.android.backup

import com.google.gson.Gson
import dev.blocks.android.auth.AndroidEncryptedAcceptedTenantContinuityStore
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverVariant
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.state.AndroidSyncStateStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Reads only the open account. Other retained accounts stay locked and are named as excluded. */
internal class OfflineBackupExporter(
    private val stateStore: AndroidSyncStateStore,
    private val draftStore: DraftStore,
    private val acceptedStore: AndroidEncryptedAcceptedTenantContinuityStore,
    private val coverStore: AndroidEncryptedProjectCoverMediaStore,
) {
    fun capture(activeTenantId: String, appVersion: String): PortableBackupPayload {
        val account = acceptedStore.snapshotForExport()
        require(account.active.tenantId == activeTenantId) { "The open account changed before export" }
        val snapshot = stateStore.exportSnapshot(activeTenantId)
        val covers = snapshot.rows.projectCovers.map { Gson().fromJson(it, ProjectCover::class.java) }
        val media = covers.flatMap { cover ->
            require(cover.tenantId == activeTenantId) { "Cover belongs to another account" }
            listOf(
                media(activeTenantId, cover, ProjectCoverVariant.DISPLAY, cover.displayContentRef),
                media(activeTenantId, cover, ProjectCoverVariant.THUMBNAIL, cover.thumbnailContentRef),
            )
        }
        return PortableBackupPayload(
            formatVersion = 1,
            appVersion = appVersion,
            exportedAt = Instant.now().toString(),
            acceptedAccount = account.active,
            state = snapshot.rows,
            drafts = draftStore.load(activeTenantId),
            coverMedia = media,
            protectedBlockMetadata = snapshot.localBlocks.map { block ->
                PortableBlockMetadata(
                    clientBlockId = block.clientBlockId,
                    tokenFingerprint = block.token.fingerprint(),
                    label = block.label,
                    state = block.state.name,
                    intendedProjectId = block.intendedProjectId,
                    boundProjectId = block.boundProjectId,
                    registrationOperationId = block.registrationOperationId,
                    initialBindingOperationId = block.initialBindingOperationId,
                    createdAt = block.createdAt,
                    verifiedAt = block.verifiedAt,
                    activationMedium = block.activationMedium?.name,
                    replacementTokenFingerprint = block.replacementTokenFingerprint,
                    failure = block.failure?.name,
                    serverBlockId = block.serverBlockId,
                )
            },
            protectedBlockOperationMetadata = snapshot.localBlockOperations.map { operation ->
                PortableBlockOperationMetadata(
                    operationId = operation.operationId,
                    type = operation.type.name,
                    clientBlockId = operation.clientBlockId,
                    tokenFingerprint = operation.token?.fingerprint(),
                    label = operation.label,
                    projectId = operation.projectId,
                    dependsOnOperationId = operation.dependsOnOperationId,
                    createdAt = operation.createdAt,
                    contractVersion = operation.contractVersion,
                )
            },
            // Raw locators are intentionally excluded: the encrypted archive preserves
            // association evidence, but cannot program, resolve or replay legacy tags.
            excludedProtectedBlockRecords = snapshot.localBlocks.size,
            excludedProtectedBlockOperations = snapshot.localBlockOperations.size,
            excludedKnownOtherAccountPartitions = account.excludedKnownOtherPartitions,
        )
    }

    private fun media(
        tenantId: String,
        cover: ProjectCover,
        variant: ProjectCoverVariant,
        reference: String,
    ): PortableCoverMedia {
        val bytes = coverStore.read(tenantId, cover, variant)
        return try {
            PortableCoverMedia(
                reference = reference,
                bytesBase64 = Base64.getEncoder().encodeToString(bytes),
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        } finally {
            bytes.fill(0)
        }
    }
}
