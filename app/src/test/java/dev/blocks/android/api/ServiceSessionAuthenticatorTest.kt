package dev.blocks.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionAuthenticatorTest {
    @Test
    fun decodesTheGeneratedApi03SessionWithoutPersistingOrChangingTheCredential() {
        val authenticator = ServiceSessionAuthenticator(SessionTransport {
            assertEquals("google-identity", it)
            ApiResponse.Success(
                200,
                """{"personal_tenant_id":"00000000-0000-0000-0000-000000000031"}""",
            )
        })

        val session = authenticator.login("google-identity")

        assertEquals("00000000-0000-0000-0000-000000000031", session.tenantId)
        assertEquals("google-identity", session.identityToken)
    }

    @Test
    fun propagatesApiAuthenticationFailure() {
        val expected = ApiError(ApiErrorCode.UNAUTHENTICATED, "Refresh required")
        val error = runCatching {
            ServiceSessionAuthenticator(SessionTransport { ApiResponse.Failure(401, expected) }).login("expired")
        }.exceptionOrNull()

        assertTrue(error is ServiceException)
        assertEquals(401, (error as ServiceException).status)
        assertSame(expected, error.error)
    }
}
