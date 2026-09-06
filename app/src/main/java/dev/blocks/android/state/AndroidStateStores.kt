package dev.blocks.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.PickupDraft
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SessionContinuityStore(context: Context) {
    private val preferences = context.getSharedPreferences("session_continuity", Context.MODE_PRIVATE)

    var shouldRestore: Boolean
        get() = preferences.getBoolean("restore_authorized_credential", false)
        set(value) = preferences.edit().putBoolean("restore_authorized_credential", value).apply()
}

class AndroidEncryptedDraftStore(context: Context) : DraftStore {
    private val preferences = context.getSharedPreferences("encrypted_form_drafts", Context.MODE_PRIVATE)

    override fun load(): FormDrafts {
        val encoded = preferences.getString(CIPHERTEXT, null) ?: return FormDrafts()
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = packed.first().toInt() and 0xff
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            decode(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrElse {
            preferences.edit().remove(CIPHERTEXT).apply()
            FormDrafts()
        }
    }

    override fun save(drafts: FormDrafts) {
        if (drafts == FormDrafts()) {
            preferences.edit().remove(CIPHERTEXT).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(encode(drafts).toByteArray(Charsets.UTF_8))
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        preferences.edit().putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
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

    private fun encode(drafts: FormDrafts) = JSONObject()
        .put("project_name", drafts.projectName)
        .put(
            "pickup",
            JSONObject()
                .put("project_id", drafts.pickup.projectId)
                .put("core_outcome", drafts.pickup.coreOutcome)
                .put("full_outcome", drafts.pickup.fullOutcome)
                .put("polish_outcome", drafts.pickup.polishOutcome)
                .put("idempotency_key", drafts.pickup.idempotencyKey),
        )
        .put(
            "parking",
            JSONObject()
                .put("location", drafts.parking.location)
                .put("next_action", drafts.parking.nextAction)
                .put("must_not_forget", drafts.parking.mustNotForget)
                .put("idempotency_key", drafts.parking.idempotencyKey),
        ).toString()

    private fun decode(value: String): FormDrafts {
        val json = JSONObject(value)
        val pickup = json.optJSONObject("pickup") ?: JSONObject()
        val parking = json.optJSONObject("parking") ?: JSONObject()
        return FormDrafts(
            projectName = json.optString("project_name"),
            pickup = PickupDraft(
                projectId = pickup.optString("project_id"),
                coreOutcome = pickup.optString("core_outcome"),
                fullOutcome = pickup.optString("full_outcome"),
                polishOutcome = pickup.optString("polish_outcome"),
                idempotencyKey = pickup.optString("idempotency_key"),
            ),
            parking = ParkingDraft(
                location = parking.optString("location"),
                nextAction = parking.optString("next_action"),
                mustNotForget = parking.optString("must_not_forget"),
                idempotencyKey = parking.optString("idempotency_key"),
            ),
        )
    }

    private companion object {
        const val KEY_ALIAS = "blocks-form-drafts-v1"
        const val CIPHERTEXT = "drafts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
