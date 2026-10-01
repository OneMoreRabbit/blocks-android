package dev.blocks.android.api

import java.net.URI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Active API 0.7 transport. It intentionally exposes no retired session or map route. */
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
    fun createNote(identityToken: String, tenantId: String, projectId: String, body: String): ApiResponse =
        throw UnsupportedOperationException("API 0.6 note transport is unavailable")
    fun readNote(identityToken: String, tenantId: String, projectId: String, itemId: String): ApiResponse =
        throw UnsupportedOperationException("API 0.6 note transport is unavailable")
    fun editNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 note transport is unavailable")
    fun pinNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 note transport is unavailable")
    fun deleteNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 note transport is unavailable")
    fun createLink(identityToken: String, tenantId: String, projectId: String, body: String): ApiResponse =
        throw UnsupportedOperationException("API 0.6 link transport is unavailable")
    fun readLink(identityToken: String, tenantId: String, projectId: String, itemId: String): ApiResponse =
        throw UnsupportedOperationException("API 0.6 link transport is unavailable")
    fun editLink(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 link transport is unavailable")
    fun deleteLink(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 link transport is unavailable")
    fun attachNavigationAlias(
        identityToken: String,
        tenantId: String,
        projectId: String,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.6 navigation aliases are unavailable")
    fun resolveNavigationAlias(identityToken: String, tenantId: String, body: String): ApiResponse =
        throw UnsupportedOperationException("API 0.6 navigation aliases are unavailable")
    fun projectOrder(identityToken: String, tenantId: String): ApiResponse =
        throw UnsupportedOperationException("API 0.7 Project order is unavailable")
    fun reorderProjects(identityToken: String, tenantId: String, body: String): ApiResponse =
        throw UnsupportedOperationException("API 0.7 Project ordering is unavailable")
    fun linkOrder(identityToken: String, tenantId: String, projectId: String): ApiResponse =
        throw UnsupportedOperationException("API 0.7 Link order is unavailable")
    fun reorderLinks(identityToken: String, tenantId: String, projectId: String, body: String): ApiResponse =
        throw UnsupportedOperationException("API 0.7 Link ordering is unavailable")
    fun parkingTombstones(identityToken: String, tenantId: String, projectId: String): ApiResponse =
        throw UnsupportedOperationException("API 0.7 Park tombstones are unavailable")
    fun deleteParking(
        identityToken: String,
        tenantId: String,
        projectId: String,
        parkingId: String,
        body: String,
    ): ApiResponse = throw UnsupportedOperationException("API 0.7 Park deletion is unavailable")
}

class ProjectApiClient(
    private val baseUri: URI,
    private val clientVersion: String = "0.7",
    private val httpClient: OkHttpClient = OkHttpClient(),
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

    override fun createNote(identityToken: String, tenantId: String, projectId: String, body: String) = request(
        "POST",
        "${itemPath(tenantId, projectId, "notes")}",
        identityToken,
        body,
    )

    override fun readNote(identityToken: String, tenantId: String, projectId: String, itemId: String) = request(
        "GET",
        "${itemPath(tenantId, projectId, "notes")}/${encoded(itemId)}",
        identityToken,
    )

    override fun editNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ) = itemMutation("PATCH", "notes", null, identityToken, tenantId, projectId, itemId, rowVersion, body)

    override fun pinNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ) = itemMutation("PUT", "notes", "pin", identityToken, tenantId, projectId, itemId, rowVersion, body)

    override fun deleteNote(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ) = itemMutation("POST", "notes", "delete", identityToken, tenantId, projectId, itemId, rowVersion, body)

    override fun createLink(identityToken: String, tenantId: String, projectId: String, body: String) = request(
        "POST",
        itemPath(tenantId, projectId, "links"),
        identityToken,
        body,
        mapOf(API_VERSION_HEADER to API_VERSION_07),
    )

    override fun readLink(identityToken: String, tenantId: String, projectId: String, itemId: String) = request(
        "GET",
        "${itemPath(tenantId, projectId, "links")}/${encoded(itemId)}",
        identityToken,
    )

    override fun editLink(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ) = itemMutation(
        "PATCH",
        "links",
        null,
        identityToken,
        tenantId,
        projectId,
        itemId,
        rowVersion,
        body,
        mapOf(API_VERSION_HEADER to API_VERSION_07),
    )

    override fun deleteLink(
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
    ) = itemMutation("POST", "links", "delete", identityToken, tenantId, projectId, itemId, rowVersion, body)

    override fun attachNavigationAlias(
        identityToken: String,
        tenantId: String,
        projectId: String,
        body: String,
    ) = request(
        "POST",
        "${projectPath(tenantId, projectId)}/navigation-aliases",
        identityToken,
        body,
    )

    override fun resolveNavigationAlias(identityToken: String, tenantId: String, body: String) = request(
        "POST",
        "/v1/tenants/${encoded(tenantId)}/projects/resolve-key",
        identityToken,
        body,
    )

    override fun projectOrder(identityToken: String, tenantId: String) = request(
        "GET",
        projectOrderPath(tenantId),
        identityToken,
    )

    override fun reorderProjects(identityToken: String, tenantId: String, body: String) = request(
        "PUT",
        projectOrderPath(tenantId),
        identityToken,
        body,
    )

    override fun linkOrder(identityToken: String, tenantId: String, projectId: String) = request(
        "GET",
        "${projectPath(tenantId, projectId)}/interaction/link-order",
        identityToken,
    )

    override fun reorderLinks(identityToken: String, tenantId: String, projectId: String, body: String) = request(
        "PUT",
        "${projectPath(tenantId, projectId)}/interaction/link-order",
        identityToken,
        body,
    )

    override fun parkingTombstones(identityToken: String, tenantId: String, projectId: String) = request(
        "GET",
        "${projectPath(tenantId, projectId)}/interaction/parking-tombstones",
        identityToken,
    )

    override fun deleteParking(
        identityToken: String,
        tenantId: String,
        projectId: String,
        parkingId: String,
        body: String,
    ) = request(
        "POST",
        "${projectPath(tenantId, projectId)}/parking/${encoded(parkingId)}/delete",
        identityToken,
        body,
    )

    private fun itemMutation(
        method: String,
        kind: String,
        action: String?,
        identityToken: String,
        tenantId: String,
        projectId: String,
        itemId: String,
        rowVersion: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val suffix = action?.let { "/$it" }.orEmpty()
        return request(
            method,
            "${itemPath(tenantId, projectId, kind)}/${encoded(itemId)}$suffix",
            identityToken,
            body,
            mapOf("If-Match" to rowVersion.toString()) + extraHeaders,
        )
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

    private fun projectOrderPath(tenantId: String) =
        "/v1/tenants/${encoded(tenantId)}/interaction/project-order"

    private fun itemPath(tenantId: String, projectId: String, kind: String) =
        "${projectPath(tenantId, projectId)}/items/$kind"

    private fun request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body?.toRequestBody(mediaType)
            ?: if (method in METHODS_REQUIRING_BODY) "".toRequestBody(mediaType) else null
        val request = Request.Builder()
            .url(baseUri.resolve(path).toString())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("X-Blocks-Client-Version", clientVersion)
            .apply { headers.forEach(::header) }
            .method(method, requestBody)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val status = response.code
            val responseBody = response.body.string()
            if (status in 200..299) {
                ApiResponse.Success(status, responseBody)
            } else {
                ApiResponse.Failure(
                    status,
                    parseApiError(responseBody) ?: ApiError(ApiErrorCode.UNKNOWN, "Unparseable service error"),
                )
            }
        }
    }

    private fun encoded(value: String) =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val API_VERSION_HEADER = "X-Blocks-API-Version"
        const val API_VERSION_07 = "0.7"
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
    }
}
