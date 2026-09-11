package dev.blocks.android.api

import java.net.HttpURLConnection
import java.net.URI

/** API 0.4 transport. It intentionally exposes no retired session or map route. */
interface ProjectTransport {
    fun listProjects(identityToken: String, tenantId: String, status: String): ApiResponse
    fun createProject(
        identityToken: String,
        tenantId: String,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
    fun projectPage(identityToken: String, tenantId: String, projectId: String): ApiResponse
    fun renameProject(
        identityToken: String,
        tenantId: String,
        projectId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse
    fun archiveProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int): ApiResponse
    fun reopenProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int): ApiResponse
    fun notes(identityToken: String, tenantId: String, projectId: String): ApiResponse
    fun saveNotes(
        identityToken: String,
        tenantId: String,
        projectId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse
    fun addParking(
        identityToken: String,
        tenantId: String,
        projectId: String,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
    fun parking(identityToken: String, tenantId: String, projectId: String): ApiResponse
    fun listBlocks(identityToken: String, tenantId: String): ApiResponse
    fun registerBlock(
        identityToken: String,
        tenantId: String,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
    fun bindBlock(
        identityToken: String,
        tenantId: String,
        blockId: String,
        body: String,
        idempotencyKey: String,
    ): ApiResponse
    fun unbindBlock(identityToken: String, tenantId: String, blockId: String): ApiResponse
    fun lookupBlock(identityToken: String, tenantId: String, body: String): ApiResponse
    fun changes(identityToken: String, tenantId: String, cursor: String?, limit: Int): ApiResponse
}

class ProjectApiClient(
    private val baseUri: URI,
    private val clientVersion: String = "0.4",
) : ProjectTransport {
    override fun listProjects(identityToken: String, tenantId: String, status: String) = request(
        "GET",
        "/v1/tenants/${encoded(tenantId)}/projects?status=${encoded(status)}",
        identityToken,
    )

    override fun createProject(
        identityToken: String,
        tenantId: String,
        body: String,
        idempotencyKey: String,
    ) = request(
        "POST",
        "/v1/tenants/${encoded(tenantId)}/projects",
        identityToken,
        body,
        mapOf("Idempotency-Key" to idempotencyKey),
    )

    override fun projectPage(identityToken: String, tenantId: String, projectId: String) = request(
        "GET",
        projectPath(tenantId, projectId),
        identityToken,
    )

    override fun renameProject(
        identityToken: String,
        tenantId: String,
        projectId: String,
        rowVersion: Int,
        body: String,
    ) = request(
        "PATCH",
        projectPath(tenantId, projectId),
        identityToken,
        body,
        mapOf("If-Match" to rowVersion.toString()),
    )

    override fun archiveProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int) =
        versionedProjectAction("archive", identityToken, tenantId, projectId, rowVersion)

    override fun reopenProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int) =
        versionedProjectAction("reopen", identityToken, tenantId, projectId, rowVersion)

    override fun notes(identityToken: String, tenantId: String, projectId: String) = request(
        "GET",
        "${projectPath(tenantId, projectId)}/notes",
        identityToken,
    )

    override fun saveNotes(
        identityToken: String,
        tenantId: String,
        projectId: String,
        rowVersion: Int,
        body: String,
    ) = request(
        "PUT",
        "${projectPath(tenantId, projectId)}/notes",
        identityToken,
        body,
        mapOf("If-Match" to rowVersion.toString()),
    )

    override fun addParking(
        identityToken: String,
        tenantId: String,
        projectId: String,
        body: String,
        idempotencyKey: String,
    ) = request(
        "POST",
        "${projectPath(tenantId, projectId)}/parking",
        identityToken,
        body,
        mapOf("Idempotency-Key" to idempotencyKey),
    )

    override fun parking(identityToken: String, tenantId: String, projectId: String) = request(
        "GET",
        "${projectPath(tenantId, projectId)}/parking",
        identityToken,
    )

    override fun listBlocks(identityToken: String, tenantId: String) = request(
        "GET",
        "/v1/tenants/${encoded(tenantId)}/blocks",
        identityToken,
    )

    override fun registerBlock(
        identityToken: String,
        tenantId: String,
        body: String,
        idempotencyKey: String,
    ) = request(
        "POST",
        "/v1/tenants/${encoded(tenantId)}/blocks",
        identityToken,
        body,
        mapOf("Idempotency-Key" to idempotencyKey),
    )

    override fun bindBlock(
        identityToken: String,
        tenantId: String,
        blockId: String,
        body: String,
        idempotencyKey: String,
    ) = request(
        "POST",
        "/v1/tenants/${encoded(tenantId)}/blocks/${encoded(blockId)}/binding",
        identityToken,
        body,
        mapOf("Idempotency-Key" to idempotencyKey),
    )

    override fun unbindBlock(identityToken: String, tenantId: String, blockId: String) = request(
        "DELETE",
        "/v1/tenants/${encoded(tenantId)}/blocks/${encoded(blockId)}/binding",
        identityToken,
    )

    override fun lookupBlock(identityToken: String, tenantId: String, body: String) = request(
        "POST",
        "/v1/tenants/${encoded(tenantId)}/blocks/lookup",
        identityToken,
        body,
    )

    override fun changes(identityToken: String, tenantId: String, cursor: String?, limit: Int): ApiResponse {
        require(limit in 1..500)
        val query = buildList {
            cursor?.let { add("since=${encoded(it)}") }
            add("limit=$limit")
        }.joinToString("&")
        return request("GET", "/v1/tenants/${encoded(tenantId)}/changes?$query", identityToken)
    }

    private fun versionedProjectAction(
        action: String,
        identityToken: String,
        tenantId: String,
        projectId: String,
        rowVersion: Int,
    ) = request(
        "POST",
        "${projectPath(tenantId, projectId)}/$action",
        identityToken,
        headers = mapOf("If-Match" to rowVersion.toString()),
    )

    private fun projectPath(tenantId: String, projectId: String) =
        "/v1/tenants/${encoded(tenantId)}/projects/${encoded(projectId)}"

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

    private fun encoded(value: String) =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
