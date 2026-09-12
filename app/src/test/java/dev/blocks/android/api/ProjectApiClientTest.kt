package dev.blocks.android.api

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectApiClientTest {
    @Test
    fun sendsApi04NotesAndParkingToTenantScopedProjectRoutes() {
        val captured = mutableListOf<Request>()
        val server = server(captured::add)
        try {
            val client = ProjectApiClient(URI("http://127.0.0.1:${server.address.port}"))
            val tenant = "00000000-0000-0000-0000-000000000002"
            val project = "00000000-0000-0000-0000-000000000003"

            assertTrue(client.saveNotes("token", tenant, project, 7, """{"body":"notes"}""") is ApiResponse.Success)
            assertTrue(
                client.addParking(
                    "token",
                    tenant,
                    project,
                    """{"where_i_am":"Here"}""",
                    "parking-key",
                ) is ApiResponse.Success,
            )

            assertEquals("PUT", captured[0].method)
            assertEquals("/v1/tenants/$tenant/projects/$project/notes", captured[0].path)
            assertEquals("7", captured[0].ifMatch)
            assertNull(captured[0].idempotencyKey)
            assertEquals("POST", captured[1].method)
            assertEquals("/v1/tenants/$tenant/projects/$project/parking", captured[1].path)
            assertEquals("parking-key", captured[1].idempotencyKey)
            assertEquals(listOf("0.4", "0.4"), captured.map { it.clientVersion })
            assertTrue(captured.all { it.authorization == "Bearer token" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun requestsArchiveAsASeparateProjectList() {
        val request = AtomicReference<Request>()
        val server = server(request::set)
        try {
            val client = ProjectApiClient(URI("http://127.0.0.1:${server.address.port}"))
            client.listProjects("token", "tenant-a", "archived")

            assertEquals("/v1/tenants/tenant-a/projects", request.get().path)
            assertEquals("status=archived", request.get().query)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendsOpaqueTagLookupOnlyInTheAuthenticatedTenantScopedPostBody() {
        val request = AtomicReference<Request>()
        val server = server(request::set)
        try {
            val client = ProjectApiClient(URI("http://127.0.0.1:${server.address.port}"))
            val token = "secret-opaque-token"
            client.lookupBlock("identity-token", "tenant-a", """{"tag_token":"$token"}""")

            assertEquals("POST", request.get().method)
            assertEquals("/v1/tenants/tenant-a/blocks/lookup", request.get().path)
            assertNull(request.get().query)
            assertFalse(request.get().path.contains(token))
            assertEquals("""{"tag_token":"$token"}""", request.get().body)
            assertEquals("Bearer identity-token", request.get().authorization)
        } finally {
            server.stop(0)
        }
    }

    private fun server(onRequest: (Request) -> Unit): HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/") { exchange ->
            onRequest(
                Request(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    query = exchange.requestURI.rawQuery,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    clientVersion = exchange.requestHeaders.getFirst("X-Blocks-Client-Version"),
                    ifMatch = exchange.requestHeaders.getFirst("If-Match"),
                    idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key"),
                    body = exchange.requestBody.bufferedReader().use { it.readText() },
                ),
            )
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val clientVersion: String?,
        val ifMatch: String?,
        val idempotencyKey: String?,
        val body: String,
    )
}
