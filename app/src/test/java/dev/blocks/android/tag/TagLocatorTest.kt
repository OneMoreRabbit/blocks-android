package dev.blocks.android.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagLocatorTest {
    @Test
    fun createsAndParsesOnlyTheCanonicalVersionOneLocator() {
        val token = OpaqueTagToken.fromBytes(ByteArray(32) { it.toByte() })
        val locator = TagLocator.create(token)

        assertEquals(locator, TagLocator.parse(locator.encoded))
        assertEquals(43, token.encoded.length)
        assertFalse(token.encoded.contains('='))
        assertNull(TagLocator.parse(locator.encoded + "?tenant=hidden"))
        assertNull(TagLocator.parse(locator.encoded.replace("/v1/", "/v2/")))
        assertNull(TagLocator.parse(locator.encoded.replace("blocks://tag", "https://tag")))
        assertNull(TagLocator.parse("blocks://tag/v1/not-a-token"))
        assertNull(TagLocator.parse(locator.encoded.replace("/v1/", "/v2/")))
        assertNull(TagLocator.parse("${locator.encoded}?tenant=other"))
        assertNull(TagLocator.parse("${locator.encoded}#project"))
        assertNull(TagLocator.parse(locator.encoded.uppercase()))
    }

    @Test
    fun generatedTokensAreThirtyTwoRandomBytesAndSensitiveStringsAreRedacted() {
        val generated = List(16) { OpaqueTagTokenFactory().create() }

        assertEquals(16, generated.map { it.encoded }.distinct().size)
        assertTrue(generated.all { it.bytes().size == 32 && OpaqueTagToken.parse(it.encoded) == it })
        assertEquals("[redacted tag token]", generated.first().toString())
        assertEquals("blocks://tag/v1/[redacted]", TagLocator.create(generated.first()).toString())
        assertNotEquals(generated.first().encoded, generated.first().toString())
    }
}
