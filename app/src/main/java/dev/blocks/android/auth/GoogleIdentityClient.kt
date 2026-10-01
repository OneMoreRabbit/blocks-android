package dev.blocks.android.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

sealed interface ReturningAccountCredential {
    data class Available(val identityToken: String) : ReturningAccountCredential
    data object Unavailable : ReturningAccountCredential
    data object Cancelled : ReturningAccountCredential
}

interface IdentityProvider {
    suspend fun signIn(activity: Activity): String
    suspend fun returningAccount(activity: Activity): ReturningAccountCredential =
        ReturningAccountCredential.Unavailable
    suspend fun clear(activity: Activity)
}

class GoogleIdentityClient(private val serverClientId: String) : IdentityProvider {
    override suspend fun signIn(activity: Activity): String {
        return try {
            credential(activity, request(filterByAuthorizedAccounts = true, immediatelyAvailable = false))
        } catch (_: NoCredentialException) {
            credential(activity, request(filterByAuthorizedAccounts = false, immediatelyAvailable = false))
        }
    }

    override suspend fun returningAccount(activity: Activity): ReturningAccountCredential {
        return try {
            ReturningAccountCredential.Available(
                credential(activity, request(filterByAuthorizedAccounts = true, immediatelyAvailable = true)),
            )
        } catch (_: GetCredentialCancellationException) {
            ReturningAccountCredential.Cancelled
        } catch (_: NoCredentialException) {
            ReturningAccountCredential.Unavailable
        } catch (_: GetCredentialException) {
            ReturningAccountCredential.Unavailable
        }
    }

    private fun request(
        filterByAuthorizedAccounts: Boolean,
        immediatelyAvailable: Boolean,
    ): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(true)
            .build()
        return GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .setPreferImmediatelyAvailableCredentials(immediatelyAvailable)
            .build()
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
