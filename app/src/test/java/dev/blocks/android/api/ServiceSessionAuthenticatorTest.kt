package dev.blocks.android.api

import dev.blocks.android.mvp.AccountIdentity
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionAuthenticatorTest {
    @Test
    fun decodesTheGeneratedApi04SessionWithoutPersistingOrChangingTheCredential() {
        val identity = AccountIdentity("https://accounts.google.com", "account-31")
        val authenticator = ServiceSessionAuthenticator(
            transport = SessionTransport {
                assertEquals("google-identity", it)
                ApiResponse.Success(
                    200,
                    """{"personal_tenant_id":"00000000-0000-0000-0000-000000000031"}""",
                )
            },
            acceptedIdentity = {
                assertEquals("google-identity", it)
                identity
            },
        )

        val session = authenticator.login("google-identity")

        assertEquals("00000000-0000-0000-0000-000000000031", session.tenantId)
        assertEquals("google-identity", session.identityToken)
        assertEquals(identity, session.accountIdentity)
    }

    @Test
    fun readsStableGoogleIdentityOnlyAfterServiceAcceptsTheExactCredential() {
        val token = jwt("accounts.google.com", "google-account-31")
        val authenticator = ServiceSessionAuthenticator(SessionTransport {
            ApiResponse.Success(
                200,
                """{"personal_tenant_id":"00000000-0000-0000-0000-000000000031"}""",
            )
        })

        val session = authenticator.login(token)

        assertEquals(
            AccountIdentity("https://accounts.google.com", "google-account-31"),
            session.accountIdentity,
        )
    }

    @Test
    fun propagatesApiAuthenticationFailure() {
        val expected = ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required")
        var identityRead = false
        val error = runCatching {
            ServiceSessionAuthenticator(
                transport = SessionTransport { ApiResponse.Failure(401, expected) },
                acceptedIdentity = {
                    identityRead = true
                    error("Must not inspect claims rejected by Service")
                },
            ).login("expired")
        }.exceptionOrNull()

        assertTrue(error is ServiceException)
        assertEquals(401, (error as ServiceException).status)
        assertSame(expected, error.error)
        assertTrue(!identityRead)
    }

    private fun jwt(issuer: String, subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{}".toByteArray())
        val claims = encoder.encodeToString("""{"iss":"$issuer","sub":"$subject"}""".toByteArray())
        return "$header.$claims.signature"
    }
}
