package dev.blocks.android.auth

import dev.blocks.android.mvp.AccountIdentity
import java.util.Base64
import org.json.JSONObject

/**
 * Reads the stable Google account key from the exact token already accepted by Blocks
 * Service. This does not verify a JWT and must never be used before the Service session
 * exchange succeeds; Service owns signature, issuer, audience, and expiry validation.
 */
internal fun decodeServiceAcceptedGoogleIdentity(identityToken: String): AccountIdentity {
    val parts = identityToken.split('.')
    require(parts.size == 3 && parts.all(String::isNotBlank)) {
        "Service accepted an identity credential with an unsupported shape"
    }
    val claims = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
    val issuer = when (val value = claims.getString("iss")) {
        "accounts.google.com", "https://accounts.google.com" -> GOOGLE_ISSUER
        else -> throw IllegalArgumentException("Service accepted an unexpected Google issuer: $value")
    }
    val subject = claims.getString("sub")
    require(subject.isNotBlank()) { "Service accepted an identity credential without a subject" }
    return AccountIdentity(issuer, subject)
}

private const val GOOGLE_ISSUER = "https://accounts.google.com"
