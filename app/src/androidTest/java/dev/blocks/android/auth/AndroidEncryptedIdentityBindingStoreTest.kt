package dev.blocks.android.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedIdentityBindingStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: AndroidEncryptedIdentityBindingStore

    @Before
    fun setUp() {
        store = AndroidEncryptedIdentityBindingStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun bindingIsEncryptedAndSurvivesAStoreRestartWithoutPersistingTheCredential() {
        val binding = AcceptedIdentityBinding("tenant-sensitive", "stable-fingerprint")

        store.save(binding)

        assertEquals(binding, AndroidEncryptedIdentityBindingStore(context).load())
        val persisted = context.getSharedPreferences(
            AndroidEncryptedIdentityBindingStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(persisted.contains(binding.tenantId))
        assertFalse(persisted.contains(binding.identityFingerprint))
        assertFalse(persisted.contains("identity-token"))
        assertTrue(keyStore().containsAlias(AndroidEncryptedIdentityBindingStore.KEY_ALIAS))
    }

    @Test
    fun clearDeletesBothCiphertextAndItsCredentialStoreKey() {
        store.save(AcceptedIdentityBinding("tenant", "fingerprint"))

        store.clear()

        assertNull(store.load())
        assertFalse(keyStore().containsAlias(AndroidEncryptedIdentityBindingStore.KEY_ALIAS))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
