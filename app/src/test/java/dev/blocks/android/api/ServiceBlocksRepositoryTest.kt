package dev.blocks.android.api

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

    private class FakeTransport(
        private val sessionResponse: ApiResponse = ApiResponse.Success(
            200,
            """{"user_id":"00000000-0000-0000-0000-000000000001","email":"pilot@example.test","personal_tenant_id":"00000000-0000-0000-0000-000000000002","tenants":[]}""",
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
            return ApiResponse.Success(
                200,
                """[{"id":"00000000-0000-0000-0000-000000000003","tenant_id":"00000000-0000-0000-0000-000000000002","name":"Existing"}]""",
            )
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
    }
}
