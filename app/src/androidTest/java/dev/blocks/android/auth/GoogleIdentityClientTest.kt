package dev.blocks.android.auth

import android.app.Activity
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleIdentityClientTest {
    @Test
    fun deliberateSignInUsesOneExplicitGoogleRequestWithoutReturningAccountPreflight() = runBlocking {
        val gateway = RecordingGateway()
        val token = GoogleIdentityClient(SERVER_CLIENT_ID, gateway).signIn(activity())

        assertEquals("selected-token", token)
        assertEquals(1, gateway.requests.size)
        val option = gateway.requests.single().credentialOptions.single()
        assertTrue(option is GetSignInWithGoogleOption)
        assertFalse(option is GetGoogleIdOption)
    }

    @Test
    fun quietReturningAccountKeepsOneAuthorizedAutoSelectRequest() = runBlocking {
        val gateway = RecordingGateway()
        val result = GoogleIdentityClient(SERVER_CLIENT_ID, gateway).returningAccount(activity())

        assertEquals(ReturningAccountCredential.Available("selected-token"), result)
        assertEquals(1, gateway.requests.size)
        val option = gateway.requests.single().credentialOptions.single() as GetGoogleIdOption
        assertTrue(option.filterByAuthorizedAccounts)
        assertTrue(option.autoSelectEnabled)
    }

    @Test
    fun deliberateCancellationIsContainedWithoutFallbackRequest() = runBlocking {
        val gateway = RecordingGateway(GetCredentialCancellationException("cancelled"))
        val client = GoogleIdentityClient(SERVER_CLIENT_ID, gateway)

        try {
            client.signIn(activity())
            fail("Expected contained cancellation")
        } catch (_: CancellationException) {
            // MainActivity already treats this as a contained outcome.
        }

        assertEquals(1, gateway.requests.size)
        assertTrue(gateway.requests.single().credentialOptions.single() is GetSignInWithGoogleOption)
    }

    private class RecordingGateway(
        private val failure: Exception? = null,
    ) : GoogleCredentialGateway {
        val requests = mutableListOf<GetCredentialRequest>()

        override suspend fun credential(activity: Activity, request: GetCredentialRequest): String {
            requests += request
            failure?.let { throw it }
            return "selected-token"
        }

        override suspend fun clear(activity: Activity) = Unit
    }

    private fun activity(): Activity {
        lateinit var activity: Activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity = Activity() }
        return activity
    }

    private companion object {
        const val SERVER_CLIENT_ID = "server-client-id.apps.googleusercontent.com"
    }
}
