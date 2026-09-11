package dev.blocks.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.blocks.android.mvp.AccountIdentity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class AcceptedTenantContinuity(
    val tenantId: String,
    val accountIdentity: AccountIdentity? = null,
)

interface AcceptedTenantContinuityStore {
    fun load(): AcceptedTenantContinuity?
    fun find(accountIdentity: AccountIdentity): AcceptedTenantContinuity?
    fun save(continuity: AcceptedTenantContinuity)
    fun lock()
    fun remove(tenantId: String)
    fun clear()
}

class AcceptedAccountBindingException : IllegalStateException(
    "The Service-accepted account cannot be matched to retained local Blocks data.",
)

/**
 * Stores account-to-tenant bindings only after a successful Service session exchange. The active
 * tenant is OS-sandboxed navigation continuity, not an OIDC credential. Locking hides it while
 * retaining encrypted bindings; only explicit removal deletes a binding.
 */
class AndroidEncryptedAcceptedTenantContinuityStore(context: Context) : AcceptedTenantContinuityStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): AcceptedTenantContinuity? {
        val state = loadState() ?: return null
        return state.activeTenantId?.let { active -> state.bindings.firstOrNull { it.tenantId == active } }
    }

    @Synchronized
    override fun find(accountIdentity: AccountIdentity): AcceptedTenantContinuity? =
        loadState()?.bindings?.firstOrNull { it.accountIdentity == accountIdentity }

    @Synchronized
    override fun save(continuity: AcceptedTenantContinuity) {
        require(continuity.tenantId.isNotBlank())
        continuity.accountIdentity?.let { identity ->
            require(identity.issuer.isNotBlank() && identity.subject.isNotBlank())
        }
        val current = loadState() ?: ContinuityState(null, emptyList())
        val identity = continuity.accountIdentity
        if (identity != null) {
            current.bindings.firstOrNull { it.accountIdentity == identity }?.let { existing ->
                if (existing.tenantId != continuity.tenantId) throw AcceptedAccountBindingException()
            }
            current.bindings.firstOrNull { it.tenantId == continuity.tenantId }?.accountIdentity?.let { existing ->
                if (existing != identity) throw AcceptedAccountBindingException()
            }
        }
        val existing = current.bindings.firstOrNull { it.tenantId == continuity.tenantId }
        val accepted = if (continuity.accountIdentity == null && existing?.accountIdentity != null) {
            existing
        } else {
            continuity
        }
        writeState(
            ContinuityState(
                activeTenantId = accepted.tenantId,
                bindings = current.bindings.filterNot { it.tenantId == accepted.tenantId } + accepted,
            ),
        )
    }

    @Synchronized
    override fun lock() {
        val current = loadState() ?: return
        writeState(current.copy(activeTenantId = null))
    }

    @Synchronized
    override fun remove(tenantId: String) {
        val current = loadState() ?: return
        val retained = current.bindings.filterNot { it.tenantId == tenantId }
        if (retained.isEmpty()) {
            clear()
        } else {
            writeState(
                current.copy(
                    activeTenantId = current.activeTenantId?.takeUnless { it == tenantId },
                    bindings = retained,
                ),
            )
        }
    }

    private fun loadState(): ContinuityState? {
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
            when (val schemaVersion = json.optInt("schema_version")) {
                LEGACY_SCHEMA_VERSION -> ContinuityState(
                    activeTenantId = json.getString("tenant_id"),
                    bindings = listOf(AcceptedTenantContinuity(json.getString("tenant_id"))),
                ).also(::writeState)
                SCHEMA_VERSION -> decodeState(json)
                else -> error("Accepted tenant continuity schema $schemaVersion is unsupported")
            }
        }.getOrElse { error ->
            throw IllegalStateException("Could not read encrypted tenant continuity", error)
        }
    }

    private fun writeState(state: ContinuityState) {
        require(state.bindings.map { it.tenantId }.distinct().size == state.bindings.size)
        require(state.bindings.mapNotNull { it.accountIdentity }.distinct().size ==
            state.bindings.mapNotNull { it.accountIdentity }.size)
        require(state.activeTenantId == null || state.bindings.any { it.tenantId == state.activeTenantId })
        val bindings = JSONArray()
        state.bindings.forEach { binding ->
            bindings.put(
                JSONObject()
                    .put("tenant_id", binding.tenantId)
                    .put("issuer", binding.accountIdentity?.issuer ?: JSONObject.NULL)
                    .put("subject", binding.accountIdentity?.subject ?: JSONObject.NULL),
            )
        }
        val plaintext = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("active_tenant_id", state.activeTenantId ?: JSONObject.NULL)
            .put("bindings", bindings)
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

    private fun decodeState(json: JSONObject): ContinuityState {
        val bindingsJson = json.getJSONArray("bindings")
        val bindings = buildList {
            for (index in 0 until bindingsJson.length()) {
                val binding = bindingsJson.getJSONObject(index)
                val identity = if (binding.isNull("issuer") || binding.isNull("subject")) {
                    null
                } else {
                    AccountIdentity(binding.getString("issuer"), binding.getString("subject"))
                }
                add(AcceptedTenantContinuity(binding.getString("tenant_id"), identity))
            }
        }
        val activeTenantId = if (json.isNull("active_tenant_id")) null else json.getString("active_tenant_id")
        return ContinuityState(activeTenantId, bindings).also { state ->
            require(state.bindings.all { it.tenantId.isNotBlank() })
            require(state.bindings.map { it.tenantId }.distinct().size == state.bindings.size)
            require(state.bindings.mapNotNull { it.accountIdentity }.distinct().size ==
                state.bindings.mapNotNull { it.accountIdentity }.size)
            require(state.activeTenantId == null || state.bindings.any { it.tenantId == state.activeTenantId })
        }
    }

    @Synchronized
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
        private const val LEGACY_SCHEMA_VERSION = 1
        private const val SCHEMA_VERSION = 2
        private val AAD = "blocks-accepted-tenant-continuity-v1".toByteArray(Charsets.UTF_8)
    }

    private data class ContinuityState(
        val activeTenantId: String?,
        val bindings: List<AcceptedTenantContinuity>,
    )
}
