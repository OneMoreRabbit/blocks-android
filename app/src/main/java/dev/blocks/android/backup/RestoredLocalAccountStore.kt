package dev.blocks.android.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.AccountIdentity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Local-only restore marker. It is never a Google credential or a Service-accepted binding. */
internal class RestoredLocalAccountStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("restored_local_account_v1", Context.MODE_PRIVATE)

    enum class Phase { PREPARING, ACTIVE, LOCKED }
    data class Entry(val account: AcceptedTenantContinuity, val phase: Phase)

    @Synchronized
    fun read(): Entry? {
        val encoded = prefs.getString("entry", null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 14)
        val ivSize = packed[0].toInt() and 0xff
        require(ivSize == 12 && packed.size > 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, packed.copyOfRange(1, 1 + ivSize)))
        cipher.updateAAD(AAD)
        val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(1 + ivSize, packed.size)), Charsets.UTF_8))
        return Entry(
            AcceptedTenantContinuity(
                json.getString("tenant"),
                AccountIdentity(json.getString("issuer"), json.getString("subject")),
            ),
            Phase.valueOf(json.getString("phase")),
        )
    }

    @Synchronized
    fun begin(account: AcceptedTenantContinuity) {
        require(read() == null) { "A restore already exists" }
        write(Entry(account, Phase.PREPARING))
    }

    @Synchronized
    fun activate() {
        val entry = requireNotNull(read())
        require(entry.phase == Phase.PREPARING)
        write(entry.copy(phase = Phase.ACTIVE))
    }

    @Synchronized
    fun lock() {
        val entry = read() ?: return
        write(entry.copy(phase = Phase.LOCKED))
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().clear().commit()) { "Could not clear restore marker" }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun write(entry: Entry) {
        val identity = requireNotNull(entry.account.accountIdentity)
        val bytes = JSONObject().put("tenant", entry.account.tenantId)
            .put("issuer", identity.issuer).put("subject", identity.subject)
            .put("phase", entry.phase.name).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
        check(prefs.edit().putString("entry", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not persist restore marker"
        }
    }

    private fun existingKey(): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        requireNotNull(getKey(KEY_ALIAS, null) as? SecretKey) { "Restore marker key is unavailable" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "blocks-restored-local-account-v1"
        val AAD = "blocks-restored-local-account-v1".toByteArray(Charsets.UTF_8)
    }
}
