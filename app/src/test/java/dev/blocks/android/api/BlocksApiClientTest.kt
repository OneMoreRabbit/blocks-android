package dev.blocks.android.api

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocksApiClientTest {
    @Test
    fun scopesTransitionToTheSuppliedTenantAndSessionWithContractHeaders() {
        val request = AtomicReference<CapturedRequest>()
        val server = server { captured -> request.set(captured) }
        try {
            val client = BlocksApiClient(URI("http://127.0.0.1:${server.address.port}"))
            val response = client.park(
                identityToken = "identity-token",
                tenantId = "00000000-0000-0000-0000-000000000002",
                sessionId = "00000000-0000-0000-0000-000000000010",
                rowVersion = 7,
                body = """{"where_i_am":"Here","next_physical_action":"Next","must_not_forget":"Remember"}""",
                idempotencyKey = "00000000-0000-0000-0000-000000000020",
            )

            assertTrue(response is ApiResponse.Success)
            assertEquals(
                "/v1/tenants/00000000-0000-0000-0000-000000000002/sessions/" +
                    "00000000-0000-0000-0000-000000000010/park",
                request.get().path,
            )
            assertEquals("Bearer identity-token", request.get().authorization)
            assertEquals("0.2", request.get().clientVersion)
            assertEquals("7", request.get().ifMatch)
            assertEquals("00000000-0000-0000-0000-000000000020", request.get().idempotencyKey)
        } finally {
            server.stop(0)
        }
    }

    private fun server(onRequest: (CapturedRequest) -> Unit): HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/") { exchange ->
            onRequest(
                CapturedRequest(
                    path = exchange.requestURI.rawPath,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    clientVersion = exchange.requestHeaders.getFirst("X-Blocks-Client-Version"),
                    ifMatch = exchange.requestHeaders.getFirst("If-Match"),
                    idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key"),
                ),
            )
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }

    private data class CapturedRequest(
        val path: String,
        val authorization: String?,
        val clientVersion: String?,
        val ifMatch: String?,
        val idempotencyKey: String?,
    )
}
