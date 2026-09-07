package dev.blocks.android.auth

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleIdentityProofVerifierTest {
    private val verifier = GoogleIdentityProofVerifier("blocks-client", nowEpochSeconds = { 100L })

    @Test
    fun refreshedCredentialsForTheSameGoogleSubjectHaveTheSameFingerprint() {
        val first = token("subject-a", "blocks-client", expiresAt = 200L, nonce = "first")
        val refreshed = token("subject-a", "blocks-client", expiresAt = 300L, nonce = "second")

        assertEquals(verifier.fingerprint(first), verifier.fingerprint(refreshed))
    }

    @Test
    fun differentSubjectAudienceOrExpiredCredentialCannotOpenTheBinding() {
        val accepted = verifier.fingerprint(token("subject-a", "blocks-client", 200L))
        val otherSubject = verifier.fingerprint(token("subject-b", "blocks-client", 200L))

        assertTrue(accepted != otherSubject)
        assertTrue(
            runCatching { verifier.fingerprint(token("subject-a", "other-client", 200L)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { verifier.fingerprint(token("subject-a", "blocks-client", 99L)) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun token(
        subject: String,
        audience: String,
        expiresAt: Long,
        nonce: String = "nonce",
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{}".toByteArray())
        val claims = JSONObject()
            .put("iss", "https://accounts.google.com")
            .put("sub", subject)
            .put("aud", audience)
            .put("exp", expiresAt)
            .put("nonce", nonce)
            .toString()
        return "$header.${encoder.encodeToString(claims.toByteArray())}.signature"
    }
}
