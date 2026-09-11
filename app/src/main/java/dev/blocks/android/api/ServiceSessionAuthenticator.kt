package dev.blocks.android.api

import com.google.gson.Gson
import dev.blocks.android.api.generated.v04.model.SessionOut
import dev.blocks.android.auth.decodeServiceAcceptedGoogleIdentity
import dev.blocks.android.mvp.AccountIdentity
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.SessionAuthenticator

fun interface SessionTransport {
    fun exchange(identityToken: String): ApiResponse
}

/** Exchanges a Google identity credential using the generated API 0.4 session model. */
class ServiceSessionAuthenticator(
    private val transport: SessionTransport,
    private val json: Gson = Gson(),
    private val acceptedIdentity: (String) -> AccountIdentity = ::decodeServiceAcceptedGoogleIdentity,
) : SessionAuthenticator {
    override fun login(identityToken: String): Session {
        val body = when (val response = transport.exchange(identityToken)) {
            is ApiResponse.Success -> response.body
            is ApiResponse.Failure -> throw ServiceException(response.status, response.error)
        }
        val session = json.fromJson(body, SessionOut::class.java)
        return Session(
            tenantId = session.personalTenantId.toString(),
            identityToken = identityToken,
            accountIdentity = acceptedIdentity(identityToken),
        )
    }
}
