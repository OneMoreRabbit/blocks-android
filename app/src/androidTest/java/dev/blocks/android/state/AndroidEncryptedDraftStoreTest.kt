package dev.blocks.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.PendingMutation
import dev.blocks.android.mvp.PickupDraft
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedDraftStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidEncryptedDraftStore
        get() = AndroidEncryptedDraftStore(context)

    @Before
    @After
    fun clearFixture() {
        store.clear(TENANT_ID)
    }

    @Test
    fun upgradesSignedPilotDraftsInPlaceAndPreservesLegacyFields() {
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias(),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
        val pilotJson = JSONObject()
            .put("project_name", "Pilot project draft")
            .put(
                "pickups",
                JSONObject().put(
                    "pilot-project",
                    JSONObject()
                        .put("project_id", "pilot-project")
                        .put("core_outcome", "Pilot core")
                        .put("full_outcome", "Pilot full")
                        .put("polish_outcome", "Pilot polish"),
                ),
            )
            .put(
                "parking",
                JSONObject()
                    .put("location", "Pilot location")
                    .put("next_action", "Pilot next action")
                    .put("must_not_forget", "Pilot reminder"),
            )
            .put(
                "pending_mutations",
                JSONObject().put(
                    "pilot-operation",
                    JSONObject()
                        .put("operation", "PICKUP")
                        .put("target_id", "pilot-project")
                        .put("payload_digest", "pilot-payload-digest")
                        .put("idempotency_key", "pilot-idempotency-key"),
                ),
            )
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(TENANT_ID.toByteArray())
        }
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(pilotJson.toByteArray())
        assertTrue(
            preferences().edit()
                .putString("drafts", Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        )

        val migrated = store.load(TENANT_ID)

        assertEquals("Pilot project draft", migrated.projectName)
        assertEquals(
            PickupDraft("pilot-project", "Pilot core", "Pilot full", "Pilot polish"),
            migrated.pickups.getValue("pilot-project"),
        )
        assertEquals(
            ParkingDraft("Pilot location", "Pilot next action", "Pilot reminder"),
            migrated.parking,
        )
        assertEquals(
            PendingMutation("PICKUP", "pilot-project", "pilot-payload-digest", "pilot-idempotency-key"),
            migrated.pendingMutations.getValue("pilot-operation"),
        )
        assertTrue(migrated.projectNotes.isEmpty())
        assertTrue(migrated.projectParking.isEmpty())

        val evolved = migrated.copy(
            projectNotes = mapOf("pilot-project" to "API 0.3 notes"),
            projectParking = mapOf(
                "pilot-project" to ParkingDraft("Desk", "Review", "Adapter"),
            ),
        )
        store.save(TENANT_ID, evolved)

        assertEquals(evolved, store.load(TENANT_ID))
        assertTrue(keyStore().containsAlias(keyAlias()))
    }

    private fun preferences() = context.getSharedPreferences(
        "encrypted_form_drafts_v2_${tenantDigest()}",
        Context.MODE_PRIVATE,
    )

    private fun keyAlias() = "blocks-form-drafts-v2-${tenantDigest()}"

    private fun tenantDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(TENANT_ID.toByteArray())
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val TENANT_ID = "00000000-0000-0000-0000-00000000000e"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
