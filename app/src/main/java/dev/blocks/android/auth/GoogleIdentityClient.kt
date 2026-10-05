package dev.blocks.android.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException

interface IdentityProvider {
    suspend fun signIn(activity: Activity): String
    suspend fun clear(activity: Activity)
}

internal interface GoogleCredentialGateway {
    suspend fun credential(activity: Activity, request: GetCredentialRequest): String
    suspend fun clear(activity: Activity)
}

private object AndroidGoogleCredentialGateway : GoogleCredentialGateway {
    override suspend fun credential(activity: Activity, request: GetCredentialRequest): String {
        val credential = CredentialManager.create(activity)
            .getCredential(activity, request)
            .credential

        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Google returned an unsupported credential type." }

        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    override suspend fun clear(activity: Activity) {
        CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    }
}

class GoogleIdentityClient internal constructor(
    private val serverClientId: String,
    private val gateway: GoogleCredentialGateway,
) : IdentityProvider {
    constructor(serverClientId: String) : this(serverClientId, AndroidGoogleCredentialGateway)

    override suspend fun signIn(activity: Activity): String {
        return try {
            gateway.credential(activity, explicitSignInRequest())
        } catch (error: GetCredentialCancellationException) {
            throw CancellationException("Google sign-in was cancelled").apply { initCause(error) }
        }
    }

    private fun explicitSignInRequest(): GetCredentialRequest {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
    }

    override suspend fun clear(activity: Activity) {
        gateway.clear(activity)
    }
}
