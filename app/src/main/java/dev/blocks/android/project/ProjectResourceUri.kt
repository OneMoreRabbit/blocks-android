package dev.blocks.android.project

import java.net.URI
import java.net.Inet6Address
import java.net.InetAddress

object ProjectResourceUri {
    private val allowedSchemes = setOf("http", "https", "obsidian")

    fun validate(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.all { it.code in 0x21..0x7e }) {
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
            "http", "https" -> validateWebAuthority(parsed)
            "obsidian" -> validateObsidianDestination(parsed)
        }
        return trimmed
    }

    fun canLaunch(value: String): Boolean = runCatching { validate(value) }.isSuccess

    private fun validateWebAuthority(uri: URI) {
        val authority = uri.rawAuthority
        require(!authority.isNullOrEmpty() && '@' !in authority) { "Web links must include a host." }
        val (host, port) = if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            require(end > 1) { "Web links must include a valid host." }
            val suffix = authority.substring(end + 1)
            require(suffix.isEmpty() || suffix.matches(Regex(":[0-9]+"))) {
                "Web links must include a valid port."
            }
            authority.substring(1, end) to suffix.removePrefix(":").takeIf(String::isNotEmpty)
        } else {
            require(authority.count { it == ':' } <= 1) { "IPv6 links must use brackets." }
            val separator = authority.lastIndexOf(':')
            if (separator >= 0) {
                authority.substring(0, separator) to authority.substring(separator + 1)
            } else {
                authority to null
            }
        }
        require(host.isNotEmpty() && isValidHost(host)) { "Web links must include a valid host." }
        port?.let {
            require(it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 1..65535) {
                "Web links must include a valid port."
            }
        }
    }

    private fun isValidHost(host: String): Boolean = when {
        ':' in host -> '%' !in host && runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        host.all { it.isDigit() || it == '.' } -> {
            val parts = host.split('.')
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() &&
                    (part == "0" || !part.startsWith('0')) &&
                    part.toIntOrNull() in 0..255
            }
        }
        else -> {
            val labels = host.split('.')
            labels.isNotEmpty() && labels.last().firstOrNull()?.isLetter() == true && labels.all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }
    }

    private fun validateObsidianDestination(uri: URI) {
        require(uri.rawUserInfo == null) { "Links containing credentials are not allowed." }
        val destination = uri.rawSchemeSpecificPart
            .removePrefix("//")
            .trimStart('/')
        require(destination.isNotEmpty()) { "Obsidian links must include a destination." }
    }
}
