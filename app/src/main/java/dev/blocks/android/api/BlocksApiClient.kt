package dev.blocks.android.api

import java.net.HttpURLConnection
import java.net.URI

sealed interface ApiResponse {
    data class Success(val status: Int, val body: String) : ApiResponse
    data class Failure(val status: Int, val error: ApiError) : ApiResponse
}

/**
 * Thin authenticated transport for blocks-service-api 0.2.
 *
 * Calls are synchronous and must be dispatched away from Android's main thread. Response bodies
 * are decoded into generated OpenAPI models by [ServiceBlocksRepository].
 */
interface BlocksTransport {
    fun exchangeSession(identityToken: String): ApiResponse
    fun listProjects(identityToken: String, tenantId: String): ApiResponse
    fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse
    fun current(identityToken: String, tenantId: String): ApiResponse
    fun pickup(identityToken: String, tenantId: String, body: String, idempotencyKey: String): ApiResponse
    fun park(
        identityToken: String,
        tenantId: String,
        sessionId: String,
        rowVersion: Int,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
    fun resume(identityToken: String, tenantId: String, sessionId: String, rowVersion: Int): ApiResponse
    fun close(
        identityToken: String,
        tenantId: String,
        sessionId: String,
        rowVersion: Int,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
}

class BlocksApiClient(
    private val baseUri: URI,
    private val clientVersion: String = "0.2",
) : BlocksTransport {
    override fun exchangeSession(identityToken: String): ApiResponse =
        request("POST", "/v1/session", identityToken)

    override fun listProjects(identityToken: String, tenantId: String): ApiResponse =
        request("GET", "/v1/tenants/${encodePathSegment(tenantId)}/projects", identityToken)

    override fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse =
        request("POST", "/v1/tenants/${encodePathSegment(tenantId)}/projects", identityToken, body)

    override fun current(identityToken: String, tenantId: String): ApiResponse =
        request("GET", "/v1/tenants/${encodePathSegment(tenantId)}/sessions/current", identityToken)

    override fun pickup(
        identityToken: String,
        tenantId: String,
        body: String,
        idempotencyKey: String,
    ): ApiResponse = request(
        "POST",
        "/v1/tenants/${encodePathSegment(tenantId)}/sessions",
        identityToken,
        body,
        mapOf("Idempotency-Key" to idempotencyKey),
    )

    override fun park(
        identityToken: String,
        tenantId: String,
        sessionId: String,
        rowVersion: Int,
        body: String,
        idempotencyKey: String,
    ): ApiResponse = request(
        "POST",
        "/v1/tenants/${encodePathSegment(tenantId)}/sessions/${encodePathSegment(sessionId)}/park",
        identityToken,
        body,
        mapOf("If-Match" to rowVersion.toString(), "Idempotency-Key" to idempotencyKey),
    )

    override fun resume(
        identityToken: String,
        tenantId: String,
        sessionId: String,
        rowVersion: Int,
    ): ApiResponse = request(
        "POST",
        "/v1/tenants/${encodePathSegment(tenantId)}/sessions/${encodePathSegment(sessionId)}/resume",
        identityToken,
        headers = mapOf("If-Match" to rowVersion.toString()),
    )

    override fun close(
        identityToken: String,
        tenantId: String,
        sessionId: String,
        rowVersion: Int,
        body: String,
        idempotencyKey: String,
    ): ApiResponse = request(
        "POST",
        "/v1/tenants/${encodePathSegment(tenantId)}/sessions/${encodePathSegment(sessionId)}/close",
        identityToken,
        body,
        mapOf("If-Match" to rowVersion.toString(), "Idempotency-Key" to idempotencyKey),
    )

    private fun request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val connection = baseUri.resolve(path).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Blocks-Client-Version", clientVersion)
            headers.forEach(connection::setRequestProperty)
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
