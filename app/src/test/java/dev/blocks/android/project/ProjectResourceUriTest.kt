package dev.blocks.android.project

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
            "/relative/path",
            "https:///missing-host",
            "obsidian:",
            "https://example.com/line\nbreak",
        ).forEach { value -> assertFalse("Unexpectedly accepted $value", ProjectResourceUri.canLaunch(value)) }
    }
}
