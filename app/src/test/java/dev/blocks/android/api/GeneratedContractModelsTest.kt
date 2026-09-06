package dev.blocks.android.api

import com.google.gson.Gson
import dev.blocks.android.api.generated.model.CloseWithContinuation
import dev.blocks.android.api.generated.model.EventEnvelopeOut
import dev.blocks.android.api.generated.model.ReentryOut
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedContractModelsTest {
    private val json = Gson()

    @Test
    fun decodesClosedSessionContinuationFromGeneratedReentryType() {
        val reentry = json.fromJson(
            """
            {
              "session": {
                "core_outcome": "Ship the client",
                "id": "00000000-0000-0000-0000-000000000010",
                "project_id": "00000000-0000-0000-0000-000000000011",
                "row_version": 4,
                "state": "closed",
                "boundary_at": null,
                "full_outcome": null,
                "physical_block_id": null,
                "polish_outcome": null
              },
              "parking": {
                "created_at": "2026-09-06T10:00:00Z",
                "id": "00000000-0000-0000-0000-000000000012",
                "must_not_forget": "Review conflicts",
                "next_physical_action": "Run the contract tests",
                "sequence": 1,
                "where_i_am": "Client models generated"
              }
            }
            """.trimIndent(),
            ReentryOut::class.java,
        )

        assertEquals("closed", reentry.session?.state)
        assertEquals("Client models generated", reentry.parking?.whereIAm)
    }

    @Test
    fun generatedContinuationRequestContainsExactlyTheThreeParkingFieldsByDefault() {
        val request = CloseWithContinuation(
            whereIAm = "At the API boundary",
            nextPhysicalAction = "Send one atomic command",
            mustNotForget = "Reuse its idempotency key",
        )

        val encoded = JSONObject(json.toJson(request))
        assertEquals(
            setOf("where_i_am", "next_physical_action", "must_not_forget"),
            encoded.keys().asSequence().toSet(),
        )
    }

    @Test
    fun generatedEnvelopeTypeToleratesUnknownEventsAndFields() {
        val event = json.fromJson(
            """
            {
              "eventId": "41",
              "eventType": "future.event",
              "eventVersion": "0.9",
              "occurredAt": "2026-09-06T10:00:00Z",
              "tenantId": "00000000-0000-0000-0000-000000000002",
              "payload": {"future_id": "value", "another_field": true},
              "futureEnvelopeField": "ignored"
            }
            """.trimIndent(),
            EventEnvelopeOut::class.java,
        )

        assertEquals("future.event", event.eventType)
        assertEquals("value", event.payload["future_id"])
        assertNull(event.payload["not_present"])
    }
}
