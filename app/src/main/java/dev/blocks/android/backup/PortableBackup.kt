package dev.blocks.android.backup

import com.google.gson.Gson
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.state.EncodedSyncRows
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Plaintext exists only in memory while making an explicit owner-selected encrypted archive. */
internal data class PortableBackupPayload(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val acceptedAccount: AcceptedTenantContinuity,
    val state: EncodedSyncRows,
    val drafts: FormDrafts,
    val coverMedia: List<PortableCoverMedia>,
    val excludedProtectedBlockRecords: Int,
    val excludedProtectedBlockOperations: Int,
    val excludedKnownOtherAccountPartitions: Int,
    val otherUnboundPartitionsMayExist: Boolean = true,
)

internal data class PortableCoverMedia(
    val reference: String,
    val bytesBase64: String,
    val sha256: String,
)

internal data class PortableBackupSummary(
    val exportedAt: String,
    val appVersion: String,
    val projects: Int,
    val notes: Int,
    val links: Int,
    val parking: Int,
    val pendingOperations: Int,
    val covers: Int,
    val excludedProtectedBlockRecords: Int,
    val excludedProtectedBlockOperations: Int,
    val excludedKnownOtherAccountPartitions: Int,
)

/** PBKDF2-HMAC-SHA256 + AES-256-GCM. Header is authenticated as AAD. No device key is used. */
internal object PortableBackupCodec {
    private val magic = "BLOCKSB1".toByteArray(StandardCharsets.US_ASCII)
    private const val iterations = 600_000
    private const val saltBytes = 16
    private const val nonceBytes = 12
    private const val tagBits = 128
    private const val headerBytes = 8 + 4 + saltBytes + nonceBytes
    private const val maxPlaintextBytes = 64 * 1024 * 1024
    private val gson = Gson()

    fun seal(payload: PortableBackupPayload, passphrase: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(passphrase.size >= 12) { "Use a passphrase of at least 12 characters" }
        require(payload.formatVersion == 1 && payload.acceptedAccount.tenantId.isNotBlank())
        val plaintext = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        try {
            require(plaintext.size <= maxPlaintextBytes) { "Backup is too large for this export version" }
            val salt = ByteArray(saltBytes).also(random::nextBytes)
            val nonce = ByteArray(nonceBytes).also(random::nextBytes)
            val header = ByteBuffer.allocate(headerBytes).put(magic).putInt(iterations).put(salt).put(nonce).array()
            val key = deriveKey(passphrase, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
                cipher.updateAAD(header)
                return header + cipher.doFinal(plaintext)
            } finally {
                Arrays.fill(key, 0)
            }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    fun open(archive: ByteArray, passphrase: CharArray): PortableBackupPayload {
        require(archive.size in (headerBytes + 16)..(headerBytes + maxPlaintextBytes + 16)) {
            "Backup is incomplete or too large"
        }
        val header = archive.copyOfRange(0, headerBytes)
        val input = ByteBuffer.wrap(header)
        val actualMagic = ByteArray(magic.size).also(input::get)
        require(MessageDigest.isEqual(magic, actualMagic) && input.int == iterations) {
            "Unsupported backup format"
        }
        val salt = ByteArray(saltBytes).also(input::get)
        val nonce = ByteArray(nonceBytes).also(input::get)
        val key = deriveKey(passphrase, salt)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
            cipher.updateAAD(header)
            try {
                cipher.doFinal(archive, headerBytes, archive.size - headerBytes)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("Wrong passphrase or damaged backup")
            }
        } finally {
            Arrays.fill(key, 0)
        }
        return try {
            gson.fromJson(String(plaintext, StandardCharsets.UTF_8), PortableBackupPayload::class.java).also {
                requireNotNull(it)
                require(it.formatVersion == 1 && it.acceptedAccount.tenantId.isNotBlank())
                require(Instant.parse(it.exportedAt).toString() == it.exportedAt)
            }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    fun summary(payload: PortableBackupPayload) = PortableBackupSummary(
        exportedAt = payload.exportedAt,
        appVersion = payload.appVersion,
        projects = payload.state.activeProjects.size + payload.state.archivedProjects.size,
        notes = payload.state.projectNoteItems.size,
        links = payload.state.projectResourceLinks.size,
        parking = payload.state.parkingRecords.size,
        pendingOperations = payload.state.commands.size + payload.state.projectCommands.size +
            payload.state.projectItemOperations.size + payload.state.interactionOperations.size,
        covers = payload.state.projectCovers.size,
        excludedProtectedBlockRecords = payload.excludedProtectedBlockRecords,
        excludedProtectedBlockOperations = payload.excludedProtectedBlockOperations,
        excludedKnownOtherAccountPartitions = payload.excludedKnownOtherAccountPartitions,
    )

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
