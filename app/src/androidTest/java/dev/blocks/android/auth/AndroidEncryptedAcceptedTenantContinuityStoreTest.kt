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
        val continuity = AcceptedTenantContinuity("tenant-sensitive")

        store.save(continuity)

        assertEquals(continuity, AndroidEncryptedAcceptedTenantContinuityStore(context).load())
        val persisted = context.getSharedPreferences(
            AndroidEncryptedAcceptedTenantContinuityStore.PREFERENCES,
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(persisted.contains(continuity.tenantId))
        assertFalse(persisted.contains("identity-token"))
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
}
