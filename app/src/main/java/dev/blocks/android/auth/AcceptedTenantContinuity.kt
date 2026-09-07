package dev.blocks.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class AcceptedTenantContinuity(
    val tenantId: String,
)

interface AcceptedTenantContinuityStore {
    fun load(): AcceptedTenantContinuity?
    fun save(continuity: AcceptedTenantContinuity)
    fun clear()
}

class AcceptedTenantMismatchException : IllegalStateException(
    "The restored Google identity does not match the locally accepted Blocks account.",
)

/**
 * Stores the tenant accepted by the Service after a successful session exchange. This is an
 * OS-sandboxed continuity pointer, not proof of the current Google identity. A restored Google
 * credential remains untrusted until the Service validates it and confirms this tenant.
 */
class AndroidEncryptedAcceptedTenantContinuityStore(context: Context) : AcceptedTenantContinuityStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): AcceptedTenantContinuity? {
        val encoded = preferences.getString(CIPHERTEXT, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.isNotEmpty())
            val ivLength = packed.first().toInt() and 0xff
            require(ivLength > 0 && packed.size > 1 + ivLength)
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
                updateAAD(AAD)
            }
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            require(json.optInt("schema_version") == SCHEMA_VERSION) {
                "Accepted tenant continuity schema is unsupported"
            }
            AcceptedTenantContinuity(
                tenantId = json.getString("tenant_id"),
            ).also {
                require(it.tenantId.isNotBlank())
            }
        }.getOrElse { error ->
            throw IllegalStateException("Could not read encrypted tenant continuity", error)
        }
    }

    override fun save(continuity: AcceptedTenantContinuity) {
        require(continuity.tenantId.isNotBlank())
        val plaintext = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("tenant_id", continuity.tenantId)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(AAD)
        }
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist encrypted tenant continuity" }
    }

    override fun clear() {
        val failures = mutableListOf<Throwable>()
        runCatching {
            check(preferences.edit().clear().commit()) {
                "Could not clear encrypted tenant continuity"
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not erase accepted tenant continuity", failure)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun existingKey(): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        requireNotNull(getKey(KEY_ALIAS, null) as? SecretKey) {
            "Accepted tenant continuity encryption key is missing"
        }
    }

    internal companion object {
        const val PREFERENCES = "accepted_tenant_continuity_v1"
        const val KEY_ALIAS = "blocks-accepted-tenant-continuity-v1"
        private const val CIPHERTEXT = "continuity"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SCHEMA_VERSION = 1
        private val AAD = "blocks-accepted-tenant-continuity-v1".toByteArray(Charsets.UTF_8)
    }
}
