package dev.blocks.android.project

import java.net.URI

object ProjectResourceUri {
    private val allowedSchemes = setOf("http", "https", "obsidian")

    fun validate(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.none(Char::isISOControl)) {
            "Enter a valid link."
        }
        val parsed = runCatching { URI(trimmed) }.getOrElse {
            throw IllegalArgumentException("Enter a valid link.")
        }
        val scheme = parsed.scheme?.lowercase()
        require(parsed.isAbsolute && scheme in allowedSchemes) {
            "Use an http, https or obsidian link."
        }
        require(parsed.rawUserInfo == null) { "Links containing credentials are not allowed." }
        when (scheme) {
            "http", "https" -> require(!parsed.host.isNullOrBlank()) {
                "Web links must include a host."
            }
            "obsidian" -> require(!parsed.rawSchemeSpecificPart.isNullOrBlank()) {
                "Obsidian links must include a destination."
            }
        }
        return trimmed
    }

    fun canLaunch(value: String): Boolean = runCatching { validate(value) }.isSuccess
}
