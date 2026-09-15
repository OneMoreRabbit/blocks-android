package dev.blocks.android.tag

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

sealed interface BlocksNavigationAddress {
    val encoded: String
}

/** Stable per-project UUID. String rendering is redacted to keep navigation keys out of logs. */
class ProjectKey private constructor(val encoded: String) {
    override fun equals(other: Any?) = other is ProjectKey && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    override fun toString() = "[redacted project key]"

    companion object {
        fun parse(value: String): ProjectKey? = runCatching { UUID.fromString(value) }
            .getOrNull()
            ?.toString()
            ?.takeIf { it == value }
            ?.let(::ProjectKey)

        fun fromUuid(value: UUID): ProjectKey = ProjectKey(value.toString())
    }
}

class ProjectKeyFactory(private val nextUuid: () -> UUID = UUID::randomUUID) {
    fun create(): ProjectKey = ProjectKey.fromUuid(nextUuid())
}

/** Canonical HTTPS address shared by a project's QR and every newly programmed NFC tag. */
class ProjectLink private constructor(val key: ProjectKey) : BlocksNavigationAddress {
    override val encoded: String
        get() = "https://api.blocks.onemorerabbit.co.uk/p/v1/${key.encoded}"

    override fun equals(other: Any?) = other is ProjectLink && key == other.key
    override fun hashCode() = key.hashCode()
    override fun toString() = "https://api.blocks.onemorerabbit.co.uk/p/v1/[redacted]"

    companion object {
        fun create(key: ProjectKey) = ProjectLink(key)

        fun parse(value: String): ProjectLink? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.rawAuthority != "api.blocks.onemorerabbit.co.uk") return null
            if (uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
            val segments = uri.rawPath?.split('/')?.filter(String::isNotEmpty) ?: return null
            if (segments.size != 3 || segments[0] != "p" || segments[1] != "v1") return null
            val key = ProjectKey.parse(segments[2]) ?: return null
            return ProjectLink(key).takeIf { it.encoded == value }
        }
    }
}

data class ProjectLinkIdentity(
    val projectId: String,
    val key: ProjectKey,
)

object BlocksNavigationAddressParser {
    fun parse(value: String): BlocksNavigationAddress? = ProjectLink.parse(value) ?: TagLocator.parse(value)
}

/** A raw tag token whose string form is deliberately always redacted. */
class OpaqueTagToken private constructor(val encoded: String) {
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun bytes(): ByteArray = Base64.getUrlDecoder().decode(encoded)

    override fun equals(other: Any?) = other is OpaqueTagToken && encoded == other.encoded
    override fun hashCode() = encoded.hashCode()
    override fun toString() = "[redacted tag token]"

    companion object {
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")

        fun fromBytes(bytes: ByteArray): OpaqueTagToken {
            require(bytes.size == TOKEN_BYTES) { "A Blocks tag token must contain 32 bytes" }
            return OpaqueTagToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf()))
        }

        fun parse(encoded: String): OpaqueTagToken? {
            if (!TOKEN_PATTERN.matches(encoded)) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
            if (bytes.size != TOKEN_BYTES) return null
            val canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return canonical.takeIf { it == encoded }?.let(::OpaqueTagToken)
        }

        const val TOKEN_BYTES = 32
    }
}

class OpaqueTagTokenFactory(private val random: SecureRandom = SecureRandom()) {
    fun create(): OpaqueTagToken = ByteArray(OpaqueTagToken.TOKEN_BYTES)
        .also(random::nextBytes)
        .let(OpaqueTagToken::fromBytes)
}

/** Canonical, versioned NFC/QR locator. Its string form never exposes the token. */
class TagLocator private constructor(val token: OpaqueTagToken) : BlocksNavigationAddress {
    override val encoded: String
        get() = "blocks://tag/v1/${token.encoded}"

    override fun equals(other: Any?) = other is TagLocator && token == other.token
    override fun hashCode() = token.hashCode()
    override fun toString() = "blocks://tag/v1/[redacted]"

    companion object {
        fun create(token: OpaqueTagToken) = TagLocator(token)

        fun parse(value: String): TagLocator? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "blocks" || uri.rawAuthority != "tag" || uri.rawPath == null) return null
            if (uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null
            val segments = uri.rawPath.split('/').filter(String::isNotEmpty)
            if (segments.size != 2 || segments[0] != "v1") return null
            val token = OpaqueTagToken.parse(segments[1]) ?: return null
            return TagLocator(token).takeIf { it.encoded == value }
        }
    }
}

enum class LocalBlockState { AWAITING_WRITE, WRITE_UNCERTAIN, ACTIVE, UNBOUND }

enum class TagActivationMedium { NFC, QR }

enum class TagProgrammingFailure {
    UNSUPPORTED,
    READ_ONLY,
    TOO_SMALL,
    FOREIGN_CONTENT,
    VERIFICATION_MISMATCH,
    IO_UNCERTAIN,
}

data class LocalBlockRecord(
    val clientBlockId: String,
    val token: OpaqueTagToken,
    val label: String,
    val state: LocalBlockState,
    val intendedProjectId: String?,
    val boundProjectId: String? = null,
    val registrationOperationId: String? = null,
    val initialBindingOperationId: String? = null,
    val createdAt: String,
    val verifiedAt: String? = null,
    val activationMedium: TagActivationMedium? = null,
    val replacementTokenFingerprint: String? = null,
    val failure: TagProgrammingFailure? = null,
    val serverBlockId: String? = null,
)

/**
 * One foreground-only Set Tag attempt. This value must not be persisted before the NFC payload
 * has been written and read back successfully.
 */
data class DirectTagWritePlan(
    val block: LocalBlockRecord,
) {
    val locator: TagLocator
        get() = TagLocator.create(block.token)
}

enum class LocalBlockOperationType { REGISTER_BLOCK, BIND_BLOCK, UNBIND_BLOCK }

/** Frozen local intent for the future API 0.5 adapter. API 0.4 must never transmit it. */
data class LocalBlockOperation(
    val operationId: String,
    val type: LocalBlockOperationType,
    val clientBlockId: String,
    val token: OpaqueTagToken? = null,
    val label: String? = null,
    val projectId: String? = null,
    val dependsOnOperationId: String? = null,
    val createdAt: String,
    val contractVersion: String = "0.5",
)

sealed interface LocalTagResolution {
    data class Bound(val block: LocalBlockRecord, val projectId: String) : LocalTagResolution
    data class Unbound(val block: LocalBlockRecord) : LocalTagResolution
    data class PendingProgramming(val block: LocalBlockRecord) : LocalTagResolution
    data class Unknown(val token: OpaqueTagToken) : LocalTagResolution {
        override fun toString() = "Unknown([redacted tag token])"
    }
}
