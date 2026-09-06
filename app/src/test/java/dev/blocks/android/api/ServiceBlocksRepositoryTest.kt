package dev.blocks.android.api

import dev.blocks.android.mvp.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceBlocksRepositoryTest {
    @Test
    fun conformsToSessionAndProjectWireShapes() {
        val transport = FakeTransport()
        val repository = ServiceBlocksRepository(transport)

        val session = repository.login("google-id-token")
        assertEquals("00000000-0000-0000-0000-000000000002", session.tenantId)
        assertEquals("google-id-token", session.identityToken)

        assertEquals(listOf("Existing"), repository.projects(session).map { it.name })
        assertEquals("Created", repository.createProject(session, "Created").name)
        assertEquals("google-id-token", transport.lastToken)
        assertEquals("00000000-0000-0000-0000-000000000002", transport.lastTenant)
        assertEquals("Created", transport.createdName)
    }

    @Test(expected = ServiceException::class)
    fun preservesContractFailureMessage() {
        val repository = ServiceBlocksRepository(
            FakeTransport(sessionResponse = ApiResponse.Failure(401, ApiError(ApiErrorCode.UNAUTHENTICATED, "Sign in again."))),
        )
        repository.login("expired")
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAProjectPayloadFromOutsideTheRequestedTenant() {
        val repository = ServiceBlocksRepository(
            FakeTransport(
                projectsResponse = ApiResponse.Success(
                    200,
                    """[{"id":"00000000-0000-0000-0000-000000000003","tenant_id":"00000000-0000-0000-0000-000000000099","name":"Wrong tenant"}]""",
                ),
            ),
        )

        repository.projects(Session("00000000-0000-0000-0000-000000000002", "token"))
    }

    private class FakeTransport(
        private val sessionResponse: ApiResponse = ApiResponse.Success(
            200,
            """{"user_id":"00000000-0000-0000-0000-000000000001","email":"pilot@example.test","personal_tenant_id":"00000000-0000-0000-0000-000000000002","tenants":[]}""",
        ),
        private val projectsResponse: ApiResponse = ApiResponse.Success(
            200,
            """[{"id":"00000000-0000-0000-0000-000000000003","tenant_id":"00000000-0000-0000-0000-000000000002","name":"Existing"}]""",
        ),
    ) : BlocksTransport {
        var lastToken: String? = null
        var lastTenant: String? = null
        var createdName: String? = null

        override fun exchangeSession(identityToken: String): ApiResponse {
            lastToken = identityToken
            return sessionResponse
        }

        override fun listProjects(identityToken: String, tenantId: String): ApiResponse {
            lastToken = identityToken
            lastTenant = tenantId
            return projectsResponse
        }

        override fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse {
            lastToken = identityToken
            lastTenant = tenantId
            createdName = org.json.JSONObject(body).getString("name")
            return ApiResponse.Success(
                200,
                """{"id":"00000000-0000-0000-0000-000000000004","tenant_id":"00000000-0000-0000-0000-000000000002","name":"$createdName"}""",
            )
        }

        override fun current(identityToken: String, tenantId: String): ApiResponse =
            ApiResponse.Success(200, """{"session":null,"parking":null}""")

        override fun pickup(
            identityToken: String,
            tenantId: String,
            body: String,
            idempotencyKey: String,
        ): ApiResponse = error("Not used")

        override fun park(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
            body: String,
            idempotencyKey: String,
        ): ApiResponse = error("Not used")

        override fun resume(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
        ): ApiResponse = error("Not used")

        override fun close(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
            body: String,
            idempotencyKey: String,
        ): ApiResponse = error("Not used")
    }
}
