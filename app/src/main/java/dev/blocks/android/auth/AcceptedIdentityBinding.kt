package dev.blocks.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64 as JavaBase64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class AcceptedIdentityBinding(
    val tenantId: String,
    val identityFingerprint: String,
)

fun interface IdentityProofVerifier {
    /** Returns a stable, non-reversible issuer/subject/audience fingerprint. */
    fun fingerprint(identityToken: String): String
}

interface IdentityBindingStore {
    fun load(): AcceptedIdentityBinding?
    fun save(binding: AcceptedIdentityBinding)
    fun clear()
}

class IdentityBindingMismatchException : IllegalStateException(
    "The restored Google identity does not match the locally accepted Blocks account.",
)

/**
 * Reads only the identity claims from a credential returned by Android Credential Manager.
 * Server-side signature and account authorization remain the responsibility of session exchange.
 */
class GoogleIdentityProofVerifier(
    private val expectedAudience: String,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : IdentityProofVerifier {
    init {
        require(expectedAudience.isNotBlank()) { "Google server client id is missing." }
    }

    override fun fingerprint(identityToken: String): String {
        require(identityToken.isNotBlank()) { "Google did not return an identity credential." }
        val segments = identityToken.split('.')
        require(segments.size == 3) { "Google returned a malformed identity credential." }
        val claims = runCatching {
            JSONObject(String(JavaBase64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8))
        }.getOrElse { throw IllegalArgumentException("Google returned malformed identity claims.", it) }
        val issuer = claims.optString("iss")
        require(issuer == "https://accounts.google.com" || issuer == "accounts.google.com") {
            "Google returned an unexpected identity issuer."
        }
        val subject = claims.optString("sub")
        require(subject.isNotBlank()) { "Google identity is missing its subject." }
        require(claims.audiences().contains(expectedAudience)) {
            "Google identity was issued for a different application."
        }
        val authorizedParty = claims.optString("azp")
        require(authorizedParty.isBlank() || authorizedParty == expectedAudience) {
            "Google identity has an unexpected authorized party."
        }
        val expiresAt = claims.optLong("exp", 0L)
        require(expiresAt > nowEpochSeconds()) { "Google identity credential has expired." }
        val canonical = listOf(issuer, subject, expectedAudience).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun JSONObject.audiences(): Set<String> = when (val audience = opt("aud")) {
        is String -> setOf(audience)
        is JSONArray -> buildSet {
            for (index in 0 until audience.length()) add(audience.getString(index))
        }
        else -> emptySet()
    }
}

/** A single encrypted pointer from a restored Google identity to the accepted tenant cache. */
class AndroidEncryptedIdentityBindingStore(context: Context) : IdentityBindingStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): AcceptedIdentityBinding? {
        val encoded = preferences.getString(CIPHERTEXT, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.isNotEmpty())
            val ivLength = packed.first().toInt() and 0xff
            require(ivLength > 0 && packed.size > 1 + ivLength)
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, iv))
                updateAAD(AAD)
            }
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            require(json.optInt("schema_version") == SCHEMA_VERSION) {
                "Accepted identity binding schema is unsupported"
            }
            AcceptedIdentityBinding(
                tenantId = json.getString("tenant_id"),
                identityFingerprint = json.getString("identity_fingerprint"),
            ).also {
                require(it.tenantId.isNotBlank() && it.identityFingerprint.isNotBlank())
            }
        }.getOrElse { error ->
            throw IllegalStateException("Could not read the encrypted accepted identity binding", error)
        }
    }

    override fun save(binding: AcceptedIdentityBinding) {
        require(binding.tenantId.isNotBlank() && binding.identityFingerprint.isNotBlank())
        val plaintext = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("tenant_id", binding.tenantId)
            .put("identity_fingerprint", binding.identityFingerprint)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(AAD)
        }
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist the encrypted accepted identity binding" }
    }

    override fun clear() {
        val failures = mutableListOf<Throwable>()
        runCatching {
            check(preferences.edit().clear().commit()) {
                "Could not clear the encrypted accepted identity binding"
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not erase the accepted identity binding", failure)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun existingKey(): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        requireNotNull(getKey(KEY_ALIAS, null) as? SecretKey) {
            "Accepted identity encryption key is missing"
        }
    }

    internal companion object {
        const val PREFERENCES = "accepted_identity_binding_v1"
        const val KEY_ALIAS = "blocks-accepted-identity-v1"
        private const val CIPHERTEXT = "binding"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SCHEMA_VERSION = 1
        private val AAD = "blocks-accepted-identity-binding-v1".toByteArray(Charsets.UTF_8)
    }
}
