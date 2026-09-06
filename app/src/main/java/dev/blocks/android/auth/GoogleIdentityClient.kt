package dev.blocks.android.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleIdentityClient(private val serverClientId: String) {
    suspend fun signIn(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
        return credential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
    }

    suspend fun restore(activity: Activity): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        return credential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
    }

    suspend fun clear(activity: Activity) {
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
