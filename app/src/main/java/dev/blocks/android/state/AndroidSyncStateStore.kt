package dev.blocks.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SyncStateJsonCodec(private val gson: Gson = Gson()) {
    fun encode(state: TenantSyncState): String = gson.toJson(
        PersistedSyncState(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            projects = state.projects,
            projectsLoaded = state.projectsLoaded,
            reentry = state.reentry,
            reentryLoaded = state.reentryLoaded,
            commands = state.commands,
            changeCursor = state.changeCursor,
            retiredIdempotencyKeys = state.retiredIdempotencyKeys,
            notice = state.notice,
        ),
    )

    fun decode(value: String): TenantSyncState {
        val persisted = gson.fromJson(value, PersistedSyncState::class.java)
        val version = persisted.schemaVersion ?: 0
        require(version in 0..CURRENT_SCHEMA_VERSION) { "Unsupported local sync schema $version" }
        if (version == 0) {
            require(persisted.commands.isNullOrEmpty()) {
                "Unversioned local state cannot contain replayable commands"
            }
        }
        val commands = persisted.commands.orEmpty()
        commands.forEach { command ->
            val operation = requireNotNull(command.operation) { "Queued command operation is missing" }
            requireNotNull(command.status) { "Queued command status is missing" }
            require(command.targetId.isNotBlank()) { "Queued command target is missing" }
            require(command.projectId.isNotBlank()) { "Queued command project is missing" }
            require(command.idempotencyKey.isNotBlank()) { "Queued command idempotency key is missing" }
            when (operation) {
                OfflineOperation.PICKUP -> requireNotNull(command.pickup) {
                    "Queued pickup payload is missing"
                }
                OfflineOperation.PARK,
                OfflineOperation.PARK_AND_CLOSE,
                OfflineOperation.CLOSE_EXISTING_CONTINUATION,
                -> requireNotNull(command.parking) { "Queued parking payload is missing" }
                OfflineOperation.CLOSE_WITH_OUTCOME -> requireNotNull(command.outcomeTier) {
                    "Queued outcome payload is missing"
                }
            }
            if (operation != OfflineOperation.PICKUP) {
                val ifMatch = requireNotNull(command.ifMatch) { "Queued command If-Match is missing" }
                val session = requireNotNull(command.workSession) { "Queued session snapshot is missing" }
                require(ifMatch == session.rowVersion) { "Queued command If-Match does not match its snapshot" }
                require(session.id == command.targetId) { "Queued command target does not match its snapshot" }
                require(session.projectId == command.projectId) {
                    "Queued command project does not match its snapshot"
                }
            }
        }
        require(commands.map { it.idempotencyKey }.distinct().size == commands.size) {
            "Queued command idempotency keys are not unique"
        }
        return TenantSyncState(
            projects = persisted.projects.orEmpty(),
            projectsLoaded = persisted.projectsLoaded ?: false,
            reentry = persisted.reentry,
            reentryLoaded = persisted.reentryLoaded ?: false,
            commands = commands,
            changeCursor = persisted.changeCursor,
            retiredIdempotencyKeys = persisted.retiredIdempotencyKeys.orEmpty().distinct(),
            notice = persisted.notice,
        )
    }

    private data class PersistedSyncState(
        @SerializedName("schema_version") val schemaVersion: Int? = null,
        val projects: List<Project>? = null,
        val projectsLoaded: Boolean? = null,
        val reentry: Reentry? = null,
        val reentryLoaded: Boolean? = null,
        val commands: List<QueuedCommand>? = null,
        val changeCursor: String? = null,
        val retiredIdempotencyKeys: List<String>? = null,
        val notice: String? = null,
    )

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class AndroidEncryptedSyncStateStore(context: Context) : SyncStateStore {
    private val applicationContext = context.applicationContext
    private val codec = SyncStateJsonCodec()

    override fun loadSyncState(tenantId: String): TenantSyncState {
        val encoded = preferences(tenantId).getString(CIPHERTEXT, null) ?: return TenantSyncState()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = packed.first().toInt() and 0xff
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(tenantId), GCMParameterSpec(128, iv))
                updateAAD(aad(tenantId))
            }
            codec.decode(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Could not read encrypted sync state", error)
        }
    }

    override fun saveSyncState(tenantId: String, state: TenantSyncState) {
        if (state == TenantSyncState()) {
            clearSyncState(tenantId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(tenantId))
            updateAAD(aad(tenantId))
        }
        val ciphertext = cipher.doFinal(codec.encode(state).toByteArray(Charsets.UTF_8))
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        check(
            preferences(tenantId).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist encrypted sync state" }
    }

    override fun clearSyncState(tenantId: String) {
        check(preferences(tenantId).edit().clear().commit()) { "Could not clear encrypted sync state" }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias(tenantId))) deleteEntry(keyAlias(tenantId))
            }
        }
    }

    private fun preferences(tenantId: String) = applicationContext.getSharedPreferences(
        "$PREFERENCES_PREFIX${tenantDigest(tenantId)}",
        Context.MODE_PRIVATE,
    )

    private fun key(tenantId: String): SecretKey {
        val alias = keyAlias(tenantId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun aad(tenantId: String) = "blocks-sync-state:$tenantId".toByteArray(Charsets.UTF_8)

    private fun keyAlias(tenantId: String) = "$KEY_ALIAS_PREFIX${tenantDigest(tenantId)}"

    private fun tenantDigest(tenantId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tenantId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES_PREFIX = "encrypted_sync_state_v1_"
        const val KEY_ALIAS_PREFIX = "blocks-sync-state-v1-"
        const val CIPHERTEXT = "state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
