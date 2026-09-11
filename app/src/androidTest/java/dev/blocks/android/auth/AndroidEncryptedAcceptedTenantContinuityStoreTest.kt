package dev.blocks.android.auth

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.mvp.AccountIdentity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedAcceptedTenantContinuityStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: AndroidEncryptedAcceptedTenantContinuityStore

    @Before
    fun setUp() {
        store = AndroidEncryptedAcceptedTenantContinuityStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun serviceAcceptedTenantIsEncryptedAndSurvivesRestartWithoutPersistingACredential() {
        val continuity = AcceptedTenantContinuity(
            "tenant-sensitive",
            AccountIdentity("https://accounts.google.com", "subject-sensitive"),
        )

        store.save(continuity)

        assertEquals(continuity, AndroidEncryptedAcceptedTenantContinuityStore(context).load())
        val persisted = context.getSharedPreferences(
            AndroidEncryptedAcceptedTenantContinuityStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(persisted.contains(continuity.tenantId))
        assertFalse(persisted.contains("subject-sensitive"))
        assertFalse(persisted.contains("identity-token"))
        assertTrue(keyStore().containsAlias(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS))
    }

    @Test
    fun signOutLockHidesTheActiveTenantButRetainsItsBindingAndCredentialStoreKey() {
        val identity = AccountIdentity("https://accounts.google.com", "account-a")
        val continuity = AcceptedTenantContinuity("tenant-a", identity)
        store.save(continuity)

        store.lock()

        assertNull(store.load())
        assertEquals(continuity, AndroidEncryptedAcceptedTenantContinuityStore(context).find(identity))
        assertTrue(keyStore().containsAlias(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS))

        store.save(requireNotNull(store.find(identity)))
        assertEquals(continuity, store.load())
    }

    @Test
    fun accountSwitchesRetainIndependentBindingsAndRemovingOneDoesNotDeleteTheOtherKey() {
        val accountA = AccountIdentity("https://accounts.google.com", "account-a")
        val accountB = AccountIdentity("https://accounts.google.com", "account-b")
        val tenantA = AcceptedTenantContinuity("tenant-a", accountA)
        val tenantB = AcceptedTenantContinuity("tenant-b", accountB)
        store.save(tenantA)
        store.save(tenantB)

        assertEquals(tenantA, store.find(accountA))
        assertEquals(tenantB, store.find(accountB))
        assertEquals(tenantB, store.load())

        store.save(requireNotNull(store.find(accountA)))
        assertEquals(tenantA, store.load())
        store.remove("tenant-a")

        assertNull(store.find(accountA))
        assertEquals(tenantB, store.find(accountB))
        assertTrue(keyStore().containsAlias(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS))
    }

    @Test
    fun conflictingIdentityOrTenantCannotReplaceAnAcceptedBinding() {
        val accountA = AccountIdentity("https://accounts.google.com", "account-a")
        val accepted = AcceptedTenantContinuity("tenant-a", accountA)
        store.save(accepted)

        val identityConflict = runCatching {
            store.save(AcceptedTenantContinuity("tenant-b", accountA))
        }.exceptionOrNull()
        val tenantConflict = runCatching {
            store.save(
                AcceptedTenantContinuity(
                    "tenant-a",
                    AccountIdentity("https://accounts.google.com", "account-b"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(identityConflict is AcceptedAccountBindingException)
        assertTrue(tenantConflict is AcceptedAccountBindingException)
        assertEquals(accepted, store.load())
        assertEquals(accepted, store.find(accountA))
    }

    @Test
    fun releasedSchemaOneContinuityMigratesWithoutErasingItsTenantKeyOrDataPointer() {
        store.save(
            AcceptedTenantContinuity(
                "seed",
                AccountIdentity("https://accounts.google.com", "seed-account"),
            ),
        )
        val keyBefore = keyStore().getKey(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS, null) as SecretKey
        writeReleasedSchemaOneFixture("tenant-from-0.5", keyBefore)

        val migrated = AndroidEncryptedAcceptedTenantContinuityStore(context).load()

        assertEquals(AcceptedTenantContinuity("tenant-from-0.5"), migrated)
        assertEquals(
            migrated,
            AndroidEncryptedAcceptedTenantContinuityStore(context).load(),
        )
        assertTrue(keyStore().containsAlias(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS))
    }

    @Test
    fun clearDeletesBothCiphertextAndItsCredentialStoreKey() {
        store.save(AcceptedTenantContinuity("tenant"))

        store.clear()

        assertNull(store.load())
        assertFalse(keyStore().containsAlias(AndroidEncryptedAcceptedTenantContinuityStore.KEY_ALIAS))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun writeReleasedSchemaOneFixture(tenantId: String, key: SecretKey) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("blocks-accepted-tenant-continuity-v1".toByteArray(Charsets.UTF_8))
        }
        val plaintext = JSONObject()
            .put("schema_version", 1)
            .put("tenant_id", tenantId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plaintext)
        check(
            context.getSharedPreferences(
                AndroidEncryptedAcceptedTenantContinuityStore.PREFERENCES,
                Context.MODE_PRIVATE,
            ).edit().putString("continuity", Base64.encodeToString(packed, Base64.NO_WRAP)).commit(),
        )
    }
}
