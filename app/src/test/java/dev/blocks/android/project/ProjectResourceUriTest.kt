package dev.blocks.android.project

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectResourceUriTest {
    @Test
    fun allowsAbsoluteWebAndExplicitlyAllowlistedObsidianLinks() {
        listOf(
            "https://example.com/path?q=one",
            "http://localhost:8080/project",
            "obsidian://open?vault=Blocks&file=Workshop",
        ).forEach { value ->
            assertEquals(value, ProjectResourceUri.validate(value))
            assertTrue(ProjectResourceUri.canLaunch(value))
        }
    }

    @Test
    fun rejectsCommandsPrivateContentCredentialsRelativeAndMalformedLinks() {
        listOf(
            "intent://open/#Intent;scheme=https;end",
            "file:///tmp/private",
            "content://provider/private",
            "javascript:alert(1)",
            "https://user:password@example.com/private",
            "onenote://user:password@example.com/private",
            "/relative/path",
            "https:///missing-host",
            "obsidian:",
            "https://example.com/line\nbreak",
        ).forEach { value -> assertFalse("Unexpectedly accepted $value", ProjectResourceUri.canLaunch(value)) }
    }

    @Test
    fun matchesTheApi06PrintableAsciiAndAuthorityBoundary() {
        listOf(
            "HTTPS://Example.COM/path",
            "https://example.com/caf%C3%A9",
            "https://127.0.0.1:65535/path",
            "https://[2001:db8::1]/path",
            "obsidian://new?vault=Blocks&name=Note%20one",
        ).forEach { value -> assertTrue("Unexpectedly rejected $value", ProjectResourceUri.canLaunch(value)) }

        listOf(
            "https://example.com/café",
            "https://example.com/space here",
            "https://user@example.com/path",
            "https://127.00.0.1/path",
            "https://example.com:0/path",
            "https://example.com:65536/path",
            "https://bad_host.example/path",
            "https://[fe80::1%25eth0]/path",
            "obsidian://",
            "obsidian://open\t?vault=Blocks",
        ).forEach { value -> assertFalse("Unexpectedly accepted $value", ProjectResourceUri.canLaunch(value)) }
    }

    @Test
    fun matchesTheSharedVersionedAcceptanceCorpusIncludingOrdinaryOneNoteUris() {
        val corpus = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("project-resource-uri-validation-v0_3.json")
            .use { input -> Gson().fromJson(input.reader(), UriCorpus::class.java) }
        assertEquals("0.3", corpus.version)
        corpus.accepted.forEach { value ->
            assertEquals(value, ProjectResourceUri.validate(value))
            assertTrue(ProjectResourceUri.canLaunch(value))
        }
        corpus.refused.forEach { value ->
            assertFalse("Unexpectedly accepted $value", ProjectResourceUri.canLaunch(value))
        }
    }

    private data class UriCorpus(
        val version: String,
        val accepted: List<String>,
        val refused: List<String>,
    )
}
