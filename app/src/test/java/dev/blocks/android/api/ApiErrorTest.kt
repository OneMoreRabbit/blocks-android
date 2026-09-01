package dev.blocks.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorTest {
    @Test
    fun parsesContractEnvelopeAndBranchesOnCode() {
        val parsed = parseApiError(
            """{"error":{"code":"unauthorized","message":"Membership required."}}""",
        )

        assertEquals(ApiErrorCode.UNAUTHORIZED, parsed?.code)
        assertEquals("Membership required.", parsed?.message)
    }

    @Test
    fun preservesForwardCompatibilityForNewCodes() {
        val parsed = parseApiError(
            """{"error":{"code":"future_code","message":"Upgrade the client."}}""",
        )

        assertEquals(ApiErrorCode.UNKNOWN, parsed?.code)
    }

    @Test
    fun rejectsNonContractErrorShapes() {
        assertNull(parseApiError("""{"detail":"framework error"}"""))
    }
}
