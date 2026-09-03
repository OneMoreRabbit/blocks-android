package dev.blocks.android.api

import java.net.HttpURLConnection
import java.net.URI

sealed interface ApiResponse {
    data class Success(val status: Int, val body: String) : ApiResponse
    data class Failure(val status: Int, val error: ApiError) : ApiResponse
}

/**
 * Thin transport for blocks-service-api 0.1.
 *
 * Calls are synchronous and must be dispatched away from Android's main thread. Response bodies
 * remain raw until the generated OpenAPI models are added; this prevents guessed payload shapes
 * from becoming an accidental client contract.
 */
interface BlocksTransport {
    fun exchangeSession(identityToken: String): ApiResponse
    fun listProjects(identityToken: String, tenantId: String): ApiResponse
    fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse
}

class BlocksApiClient(private val baseUri: URI) : BlocksTransport {
    override fun exchangeSession(identityToken: String): ApiResponse =
        request("POST", "/v1/session", identityToken, body = "{}")

    override fun listProjects(identityToken: String, tenantId: String): ApiResponse =
        request("GET", "/v1/tenants/${encodePathSegment(tenantId)}/projects", identityToken)

    override fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse =
        request("POST", "/v1/tenants/${encodePathSegment(tenantId)}/projects", identityToken, body)

    private fun request(method: String, path: String, token: String, body: String? = null): ApiResponse {
        val connection = baseUri.resolve(path).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (status in 200..299) {
                ApiResponse.Success(status, responseBody)
            } else {
                ApiResponse.Failure(
                    status,
                    parseApiError(responseBody) ?: ApiError(ApiErrorCode.UNKNOWN, "Unparseable service error"),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
