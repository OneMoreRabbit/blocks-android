package dev.blocks.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.PendingMutation
import dev.blocks.android.mvp.PickupDraft
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

interface SessionContinuity {
    var shouldRestore: Boolean
}

class SessionContinuityStore(context: Context) : SessionContinuity {
    private val preferences = context.getSharedPreferences("session_continuity", Context.MODE_PRIVATE)

    override var shouldRestore: Boolean
        get() = preferences.getBoolean("restore_authorized_credential", false)
        set(value) = preferences.edit().putBoolean("restore_authorized_credential", value).apply()
}

class AndroidEncryptedDraftStore(context: Context) : DraftStore {
    private val applicationContext = context.applicationContext

    init {
        // Version 1 used one app-global record. It cannot be assigned to a tenant safely, so remove it.
        applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    override fun load(tenantId: String): FormDrafts {
        val encoded = preferences(tenantId).getString(CIPHERTEXT, null) ?: return FormDrafts()
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = packed.first().toInt() and 0xff
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(tenantId), GCMParameterSpec(128, iv))
                updateAAD(tenantId.toByteArray(Charsets.UTF_8))
            }
            decode(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrElse { error ->
            throw IllegalStateException("Could not read encrypted tenant draft state", error)
        }
    }

    override fun save(tenantId: String, drafts: FormDrafts) {
        if (drafts == FormDrafts()) {
            clear(tenantId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(tenantId))
            updateAAD(tenantId.toByteArray(Charsets.UTF_8))
        }
        val ciphertext = cipher.doFinal(encode(drafts).toByteArray(Charsets.UTF_8))
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        check(
            preferences(tenantId).edit()
            .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist encrypted draft state" }
    }

    override fun clear(tenantId: String) {
        val failures = mutableListOf<Throwable>()
        runCatching {
            check(preferences(tenantId).edit().clear().commit()) {
                "Could not clear encrypted draft state"
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias(tenantId))) deleteEntry(keyAlias(tenantId))
            }
        }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not clear encrypted tenant draft state", failure)
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

    private fun keyAlias(tenantId: String) = "$KEY_ALIAS_PREFIX${tenantDigest(tenantId)}"

    private fun tenantDigest(tenantId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tenantId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun encode(drafts: FormDrafts): String {
        val pickups = JSONObject()
        drafts.pickups.forEach { (projectId, pickup) ->
            pickups.put(
                projectId,
                JSONObject()
                    .put("project_id", pickup.projectId)
                    .put("core_outcome", pickup.coreOutcome)
                    .put("full_outcome", pickup.fullOutcome)
                    .put("polish_outcome", pickup.polishOutcome),
            )
        }
        val mutations = JSONObject()
        drafts.pendingMutations.forEach { (slot, mutation) ->
            mutations.put(
                slot,
                JSONObject()
                    .put("operation", mutation.operation)
                    .put("target_id", mutation.targetId)
                    .put("payload_digest", mutation.payloadDigest)
                    .put("idempotency_key", mutation.idempotencyKey),
            )
        }
        val projectNotes = JSONObject()
        drafts.projectNotes.forEach(projectNotes::put)
        val projectParking = JSONObject()
        drafts.projectParking.forEach { (projectId, parking) ->
            projectParking.put(projectId, parking.toJson())
        }
        val projectRenames = JSONObject()
        drafts.projectRenames.forEach(projectRenames::put)
        return JSONObject()
            .put("schema_version", CURRENT_SCHEMA_VERSION)
            .put("project_name", drafts.projectName)
            .put("pickups", pickups)
            .put(
                "parking",
                JSONObject()
                    .put("location", drafts.parking.location)
                    .put("next_action", drafts.parking.nextAction)
                    .put("must_not_forget", drafts.parking.mustNotForget),
            )
            .put("project_notes", projectNotes)
            .put("project_parking", projectParking)
            .put("project_renames", projectRenames)
            .put("pending_mutations", mutations)
            .toString()
    }

    private fun decode(value: String): FormDrafts {
        val json = JSONObject(value)
        val schemaVersion = json.optInt("schema_version", PILOT_SCHEMA_VERSION)
        require(schemaVersion in PILOT_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported encrypted draft schema $schemaVersion"
        }
        val pickupsJson = json.optJSONObject("pickups") ?: JSONObject()
        val pickups = buildMap {
            val keys = pickupsJson.keys()
            while (keys.hasNext()) {
                val projectId = keys.next()
                val pickup = pickupsJson.getJSONObject(projectId)
                put(
                    projectId,
                    PickupDraft(
                        projectId = pickup.optString("project_id", projectId),
                        coreOutcome = pickup.optString("core_outcome"),
                        fullOutcome = pickup.optString("full_outcome"),
                        polishOutcome = pickup.optString("polish_outcome"),
                    ),
                )
            }
        }
        val mutationsJson = json.optJSONObject("pending_mutations") ?: JSONObject()
        val mutations = buildMap {
            val keys = mutationsJson.keys()
            while (keys.hasNext()) {
                val slot = keys.next()
                val mutation = mutationsJson.getJSONObject(slot)
                put(
                    slot,
                    PendingMutation(
                        operation = mutation.getString("operation"),
                        targetId = mutation.getString("target_id"),
                        payloadDigest = mutation.getString("payload_digest"),
                        idempotencyKey = mutation.getString("idempotency_key"),
                    ),
                )
            }
        }
        val projectNotesJson = json.optJSONObject("project_notes") ?: JSONObject()
        val projectNotes = buildMap {
            val keys = projectNotesJson.keys()
            while (keys.hasNext()) {
                val projectId = keys.next()
                put(projectId, projectNotesJson.getString(projectId))
            }
        }
        val projectParkingJson = json.optJSONObject("project_parking") ?: JSONObject()
        val projectParking = buildMap {
            val keys = projectParkingJson.keys()
            while (keys.hasNext()) {
                val projectId = keys.next()
                put(projectId, projectParkingJson.getJSONObject(projectId).toParkingDraft())
            }
        }
        val projectRenamesJson = json.optJSONObject("project_renames") ?: JSONObject()
        val projectRenames = buildMap {
            val keys = projectRenamesJson.keys()
            while (keys.hasNext()) {
                val projectId = keys.next()
                put(projectId, projectRenamesJson.getString(projectId))
            }
        }
        val parking = json.optJSONObject("parking") ?: JSONObject()
        return FormDrafts(
            projectName = json.optString("project_name"),
            pickups = pickups,
            parking = ParkingDraft(
                location = parking.optString("location"),
                nextAction = parking.optString("next_action"),
                mustNotForget = parking.optString("must_not_forget"),
            ),
            projectNotes = projectNotes,
            projectParking = projectParking,
            projectRenames = projectRenames,
            pendingMutations = mutations,
        )
    }

    private fun ParkingDraft.toJson() = JSONObject()
        .put("location", location)
        .put("next_action", nextAction)
        .put("must_not_forget", mustNotForget)

    private fun JSONObject.toParkingDraft() = ParkingDraft(
        location = optString("location"),
        nextAction = optString("next_action"),
        mustNotForget = optString("must_not_forget"),
    )

    private companion object {
        const val LEGACY_PREFERENCES = "encrypted_form_drafts"
        const val PREFERENCES_PREFIX = "encrypted_form_drafts_v2_"
        const val KEY_ALIAS_PREFIX = "blocks-form-drafts-v2-"
        const val CIPHERTEXT = "drafts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PILOT_SCHEMA_VERSION = 2
        const val CURRENT_SCHEMA_VERSION = 4
    }
}
