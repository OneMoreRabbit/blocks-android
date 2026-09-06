package dev.blocks.android.api

import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionFlowRepositoryTest {
    @Test
    fun mapsAuthoritativeClosedSessionReentryWithNewestParking() {
        val transport = FlowTransport().apply {
            currentResponse = ApiResponse.Success(200, reentryJson("closed", 4))
        }

        val reentry = ServiceBlocksRepository(transport).current(AUTH_SESSION)

        assertEquals(WorkSessionState.CLOSED, reentry.workSession?.state)
        assertEquals(PROJECT_ID, reentry.workSession?.projectId)
        assertEquals("Continue here", reentry.parking?.nextAction)
        assertEquals(9, reentry.parking?.sequence)
    }

    @Test
    fun startsPickupWithGeneratedBodyAndSurfacesOneActiveSessionConflict() {
        val transport = FlowTransport().apply {
            pickupResponse = ApiResponse.Success(201, workSessionJson("active", 1))
        }
        val repository = ServiceBlocksRepository(transport)

        val started = repository.pickup(
            AUTH_SESSION,
            PickupRequest(PROJECT_ID, "Core", "Full", null),
            IDEMPOTENCY_KEY,
        )

        assertTrue(started is PickupResult.Started)
        assertEquals(IDEMPOTENCY_KEY, transport.idempotencyKey)
        assertEquals(PROJECT_ID, JSONObject(transport.body!!).getString("project_id"))
        assertEquals("Core", JSONObject(transport.body!!).getString("core_outcome"))

        transport.pickupResponse = ApiResponse.Failure(
            409,
            ApiError(ApiErrorCode.CONFLICT, "One active session"),
        )
        transport.currentResponse = ApiResponse.Success(200, reentryJson("active", 1))

        val conflict = repository.pickup(
            AUTH_SESSION,
            PickupRequest(PROJECT_ID, "Other core"),
            "another-key",
        )
        assertTrue(conflict is PickupResult.ExistingSession)
        assertEquals(WorkSessionState.ACTIVE, (conflict as PickupResult.ExistingSession).reentry.workSession?.state)
    }

    @Test
    fun appliesConcurrencyAndIdempotencyToParkResumeAndAllCloseForms() {
        val transport = FlowTransport().apply {
            parkResponse = ApiResponse.Success(
                200,
                """{"session":{"id":"$WORK_SESSION_ID","state":"parked","row_version":2},"parking":${parkingJson()}}""",
            )
        }
        val repository = ServiceBlocksRepository(transport)
        val active = workSession(WorkSessionState.ACTIVE, 1)
        val parking = ParkingRecord("Here", "Continue here", "Remember this")

        val parked = repository.park(AUTH_SESSION, active, parking, IDEMPOTENCY_KEY)
        assertEquals(WorkSessionState.PARKED, parked.workSession?.state)
        assertEquals("1", transport.ifMatch)
        assertEquals(IDEMPOTENCY_KEY, transport.idempotencyKey)
        assertEquals(
            setOf("where_i_am", "next_physical_action", "must_not_forget"),
            JSONObject(transport.body!!).keys().asSequence().toSet(),
        )

        transport.currentResponse = ApiResponse.Success(200, reentryJson("active", 3))
        val resumed = repository.resume(AUTH_SESSION, requireNotNull(parked.workSession))
        assertEquals(WorkSessionState.ACTIVE, resumed.workSession?.state)
        assertEquals("2", transport.ifMatch)

        transport.closeResponse = ApiResponse.Success(
            200,
            """{"id":"$WORK_SESSION_ID","state":"closed","row_version":2,"parking_record_id":"$PARKING_ID"}""",
        )
        val closed = repository.closeWithContinuation(AUTH_SESSION, active, parking, IDEMPOTENCY_KEY)
        assertEquals(WorkSessionState.CLOSED, closed.workSession?.state)
        assertEquals(PARKING_ID, closed.parking?.id)
        assertEquals(
            setOf("where_i_am", "next_physical_action", "must_not_forget"),
            JSONObject(transport.body!!).keys().asSequence().toSet(),
        )

        repository.closeOnExistingContinuation(
            AUTH_SESSION,
            workSession(WorkSessionState.PARKED, 2),
            parking,
            "existing-key",
        )
        assertEquals("existing_continuation", JSONObject(transport.body!!).getString("preserve"))

        transport.currentResponse = ApiResponse.Success(200, """{"session":null,"parking":null}""")
        repository.closeWithOutcome(AUTH_SESSION, active, OutcomeTier.CORE, "outcome-key")
        assertEquals("core", JSONObject(transport.body!!).getString("outcome_tier"))
    }

    private class FlowTransport : BlocksTransport {
        var currentResponse: ApiResponse = ApiResponse.Success(200, """{"session":null,"parking":null}""")
        var pickupResponse: ApiResponse = ApiResponse.Success(201, workSessionJson("active", 1))
        var parkResponse: ApiResponse = ApiResponse.Success(200, "{}")
        var resumeResponse: ApiResponse = ApiResponse.Success(
            200,
            """{"id":"$WORK_SESSION_ID","state":"active","row_version":3}""",
        )
        var closeResponse: ApiResponse = ApiResponse.Success(
            200,
            """{"id":"$WORK_SESSION_ID","state":"closed","row_version":2,"parking_record_id":null}""",
        )
        var body: String? = null
        var ifMatch: String? = null
        var idempotencyKey: String? = null

        override fun exchangeSession(identityToken: String): ApiResponse = error("Not used")
        override fun listProjects(identityToken: String, tenantId: String): ApiResponse = error("Not used")
        override fun createProject(identityToken: String, tenantId: String, body: String): ApiResponse = error("Not used")
        override fun current(identityToken: String, tenantId: String): ApiResponse = currentResponse

        override fun pickup(
            identityToken: String,
            tenantId: String,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            this.body = body
            this.idempotencyKey = idempotencyKey
            return pickupResponse
        }

        override fun park(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            this.body = body
            this.ifMatch = rowVersion.toString()
            this.idempotencyKey = idempotencyKey
            return parkResponse
        }

        override fun resume(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
        ): ApiResponse {
            ifMatch = rowVersion.toString()
            return resumeResponse
        }

        override fun close(
            identityToken: String,
            tenantId: String,
            sessionId: String,
            rowVersion: Int,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            this.body = body
            this.ifMatch = rowVersion.toString()
            this.idempotencyKey = idempotencyKey
            return closeResponse
        }
    }

    private companion object {
        const val TENANT_ID = "00000000-0000-0000-0000-000000000002"
        const val PROJECT_ID = "00000000-0000-0000-0000-000000000011"
        const val WORK_SESSION_ID = "00000000-0000-0000-0000-000000000010"
        const val PARKING_ID = "00000000-0000-0000-0000-000000000012"
        const val IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000020"
        val AUTH_SESSION = Session(TENANT_ID, "identity-token")

        fun workSession(state: WorkSessionState, rowVersion: Int) = WorkSession(
            id = WORK_SESSION_ID,
            projectId = PROJECT_ID,
            state = state,
            coreOutcome = "Core",
            rowVersion = rowVersion,
        )

        fun workSessionJson(state: String, rowVersion: Int) =
            """{"id":"$WORK_SESSION_ID","project_id":"$PROJECT_ID","state":"$state","core_outcome":"Core","row_version":$rowVersion}"""

        fun parkingJson() =
            """{"created_at":"2026-09-06T10:00:00Z","id":"$PARKING_ID","must_not_forget":"Remember this","next_physical_action":"Continue here","sequence":9,"where_i_am":"Here"}"""

        fun reentryJson(state: String, rowVersion: Int) =
            """{"session":${workSessionJson(state, rowVersion)},"parking":${parkingJson()}}"""
    }
}
