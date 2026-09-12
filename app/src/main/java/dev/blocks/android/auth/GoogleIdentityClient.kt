package dev.blocks.android.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

interface IdentityProvider {
    suspend fun signIn(activity: Activity): String
    suspend fun clear(activity: Activity)
}

class GoogleIdentityClient(private val serverClientId: String) : IdentityProvider {
    override suspend fun signIn(activity: Activity): String {
        return try {
            credential(activity, request(filterByAuthorizedAccounts = true))
        } catch (_: NoCredentialException) {
            credential(activity, request(filterByAuthorizedAccounts = false))
        }
    }

    private fun request(filterByAuthorizedAccounts: Boolean): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(true)
            .build()
        return GetCredentialRequest.Builder().addCredentialOption(option).build()
    }

    override suspend fun clear(activity: Activity) {
        CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
    }

    private suspend fun credential(activity: Activity, request: GetCredentialRequest): String {
        val credential = CredentialManager.create(activity)
            .getCredential(activity, request)
            .credential

        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Google returned an unsupported credential type." }

        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
