package dev.blocks.android.backup

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.state.EncodedSyncRows
import dev.blocks.android.state.SyncStateRowCodec
import dev.blocks.android.tag.OpaqueTagToken
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Format 2 is deliberately unencrypted. It must never contain authentication material. */
internal data class PortableBackupPayload(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val acceptedAccount: AcceptedTenantContinuity,
    val state: EncodedSyncRows,
    val drafts: FormDrafts,
    val coverMedia: List<PortableCoverMedia>,
    val excludedKnownOtherAccountPartitions: Int,
)

internal data class PortableCoverMedia(
    val reference: String,
    val bytesBase64: String,
    val sha256: String,
)

internal data class PortableBackupSummary(
    val exportedAt: String,
    val appVersion: String,
    val projects: Int,
    val notes: Int,
    val links: Int,
    val parking: Int,
    val pendingOperations: Int,
    val covers: Int,
    val blocks: Int,
    val blockOperations: Int,
    val excludedKnownOtherAccountPartitions: Int,
)

internal data class PortableBackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val stateSchemaVersion: Int,
    val payloadSha256: String,
    val rowCounts: Map<String, Int>,
)

internal data class PortableBackupEnvelope(
    val manifest: PortableBackupManifest,
    val payload: PortableBackupPayload,
)

/** A bounded JSON envelope. SHA-256 detects corruption, not forgery or ownership. */
internal object PortableBackupCodec {
    private val magic = "BLOCKSB2".toByteArray(StandardCharsets.US_ASCII)
    private const val headerBytes = 8 + 4 + 32
    const val MAX_PAYLOAD_BYTES = 128 * 1024 * 1024
    private val gson = Gson()

    fun encode(payload: PortableBackupPayload): ByteArray {
        PortableBackupValidator.validate(payload)
        val payloadBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val manifest = PortableBackupManifest(
            formatVersion = payload.formatVersion,
            appVersion = payload.appVersion,
            stateSchemaVersion = payload.state.schemaVersion,
            payloadSha256 = digest(payloadBytes).hex(),
            rowCounts = rowCounts(payload),
        )
        val bytes = gson.toJson(PortableBackupEnvelope(manifest, payload)).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAX_PAYLOAD_BYTES) { "Backup is too large" }
        return ByteBuffer.allocate(headerBytes + bytes.size)
            .put(magic).putInt(bytes.size).put(digest(bytes)).put(bytes).array()
    }

    fun decode(archive: ByteArray): PortableBackupPayload {
        require(archive.size in (headerBytes + 1)..(headerBytes + MAX_PAYLOAD_BYTES)) {
            "Backup is incomplete or too large"
        }
        val input = ByteBuffer.wrap(archive)
        val foundMagic = ByteArray(magic.size).also(input::get)
        require(MessageDigest.isEqual(magic, foundMagic)) { "Unsupported backup format" }
        val size = input.int
        require(size in 1..MAX_PAYLOAD_BYTES && archive.size == headerBytes + size) {
            "Backup length is invalid"
        }
        val expected = ByteArray(32).also(input::get)
        val bytes = ByteArray(size).also(input::get)
        require(MessageDigest.isEqual(expected, digest(bytes))) { "Backup checksum does not match" }
        requireBoundedJsonDepth(bytes)
        requireUniqueJsonFields(String(bytes, StandardCharsets.UTF_8))
        val json = JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
        require(json.keySet() == setOf("manifest", "payload")) { "Backup envelope is invalid" }
        val payloadJson = json.getAsJsonObject("payload")
        require(payloadJson.keySet() == setOf(
            "formatVersion", "appVersion", "exportedAt", "acceptedAccount", "state",
            "drafts", "coverMedia", "excludedKnownOtherAccountPartitions",
        )) { "Backup fields are invalid" }
        val envelope = gson.fromJson(json, PortableBackupEnvelope::class.java)
        val payload = envelope.payload
        val manifest = envelope.manifest
        require(manifest.formatVersion == payload.formatVersion &&
            manifest.appVersion == payload.appVersion &&
            manifest.stateSchemaVersion == payload.state.schemaVersion &&
            manifest.rowCounts == rowCounts(payload) &&
            manifest.payloadSha256 == digest(gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)).hex()) {
            "Backup manifest does not match its payload"
        }
        PortableBackupValidator.validate(payload)
        return payload
    }

    fun summary(payload: PortableBackupPayload) = PortableBackupSummary(
        exportedAt = payload.exportedAt,
        appVersion = payload.appVersion,
        projects = payload.state.activeProjects.size + payload.state.archivedProjects.size,
        notes = payload.state.projectNoteItems.size,
        links = payload.state.projectResourceLinks.size,
        parking = payload.state.parkingRecords.size,
        pendingOperations = payload.state.commands.size + payload.state.projectCommands.size +
            payload.state.projectItemOperations.size + payload.state.interactionOperations.size,
        covers = payload.state.projectCovers.size,
        blocks = payload.state.localBlocks.size,
        blockOperations = payload.state.localBlockOperations.size,
        excludedKnownOtherAccountPartitions = payload.excludedKnownOtherAccountPartitions,
    )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun requireBoundedJsonDepth(bytes: ByteArray) {
        var depth = 0
        var quoted = false
        var escaped = false
        bytes.forEach { byte ->
            val char = byte.toInt().toChar()
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 64) { "Backup nesting is too deep" } }
                    '}', ']' -> depth--
                }
                require(depth >= 0) { "Backup JSON is malformed" }
            }
        }
        require(!quoted && depth == 0) { "Backup JSON is incomplete" }
    }
    private fun rowCounts(payload: PortableBackupPayload): Map<String, Int> =
        gson.toJsonTree(payload.state).asJsonObject.entrySet().mapNotNull { (name, value) ->
            if (value.isJsonArray) name to value.asJsonArray.size() else null
        }.toMap() + ("coverMedia" to payload.coverMedia.size)

    internal fun requireUniqueJsonFields(value: String) {
        JsonReader(StringReader(value)).use { reader ->
            reader.isLenient = false
            fun consume(depth: Int) {
                require(depth <= 64) { "Backup nesting is too deep" }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val names = hashSetOf<String>()
                        while (reader.hasNext()) {
                            require(names.add(reader.nextName())) { "Duplicate backup field" }
                            consume(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) consume(depth + 1)
                        reader.endArray()
                    }
                    JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("Malformed backup JSON")
                }
            }
            consume(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected backup content" }
        }
    }
}

/** Rejects structurally hostile data even when an attacker recomputes the checksum. */
internal object PortableBackupValidator {
    private val gson = Gson()
    private val hexDigest = Regex("[0-9a-f]{64}")
    private val coverReference = Regex("[0-9a-fA-F-]{36}-r[1-9][0-9]*-(display|thumbnail)\\.enc")

    fun validate(payload: PortableBackupPayload) {
        require(payload.formatVersion == 2) { "Unsupported backup version" }
        require(payload.appVersion.isNotBlank() && payload.appVersion.length <= 32)
        require(Instant.parse(payload.exportedAt).toString() == payload.exportedAt)
        val account = payload.acceptedAccount
        require(account.tenantId == UUID.fromString(account.tenantId).toString())
        val identity = requireNotNull(account.accountIdentity) { "Backup has no account identity" }
        require(identity.issuer.isNotBlank() && identity.subject.isNotBlank())
        require(payload.excludedKnownOtherAccountPartitions >= 0)
        val rows = payload.state
        require(rows.schemaVersion == SyncStateRowCodec.CURRENT_SCHEMA_VERSION) { "Unsupported local row schema" }
        val encodedRecords = rows.projects + rows.commands + rows.activeProjects + rows.archivedProjects +
            rows.projectPages + rows.parkingRecords + rows.blocks + rows.localBlocks +
            rows.localBlockOperations + rows.projectLinks + rows.projectCovers +
            rows.projectNoteItems + rows.projectResourceLinks + rows.projectItemOperations +
            rows.projectCommands + rows.projectIdMappings + rows.linkOrders + rows.noteOrders +
            rows.parkingTombstones + rows.interactionOperations +
            listOfNotNull(rows.reentry, rows.projectDrafts, rows.projectUiPreferences, rows.projectOrder)
        encodedRecords.forEach(PortableBackupCodec::requireUniqueJsonFields)
        val state = SyncStateRowCodec().decode(rows)
        val cache = state.projectCache
        val projects = cache.activeProjects + cache.archivedProjects
        require(projects.all { it.tenantId == account.tenantId }) { "Project crosses an account boundary" }
        require(projects.map { it.id }.distinct().size == projects.size) { "Duplicate project identity" }
        val projectIds = (projects.map { it.id } + cache.pages.map { it.project.id }).toSet()
        fun projectReference(value: String) = require(value in projectIds) { "Unknown project reference" }
        cache.pages.forEach { projectReference(it.project.id); require(it.project.tenantId == account.tenantId) }
        cache.parkingRecords.forEach { projectReference(it.projectId) }
        cache.noteItems.forEach { projectReference(it.projectId) }
        cache.resourceLinks.forEach { projectReference(it.projectId) }
        cache.projectCovers.forEach { projectReference(it.projectId); require(it.tenantId == account.tenantId) }
        cache.localBlocks.forEach { block ->
            require(OpaqueTagToken.parse(block.token.encoded) != null) { "Invalid tag locator" }
            block.intendedProjectId?.let(::projectReference)
            block.boundProjectId?.let(::projectReference)
        }
        val blockIds = cache.localBlocks.map { it.clientBlockId }.toSet()
        require(blockIds.size == cache.localBlocks.size) { "Duplicate tag identity" }
        cache.localBlockOperations.forEach { operation ->
            require(operation.clientBlockId in blockIds) { "Tag operation has no tag" }
            operation.token?.let { require(OpaqueTagToken.parse(it.encoded) != null) }
            operation.projectId?.let(::projectReference)
        }
        listOf(cache.parkingRecords.map { it.id }, cache.noteItems.map { it.id },
            cache.resourceLinks.map { it.id }, cache.projectCovers.map { it.coverId },
            cache.localBlockOperations.map { it.operationId }).forEach { ids ->
            require(ids.distinct().size == ids.size) { "Duplicate record identity" }
        }
        val operationIds = state.commands.map { it.idempotencyKey } + cache.commands.map { it.operationId } +
            cache.itemOperations.map { it.operationId } + cache.interactionOperations.map { it.operationId } +
            cache.localBlockOperations.map { it.operationId }
        require(operationIds.distinct().size == operationIds.size) { "Duplicate pending operation" }
        val mediaByReference = payload.coverMedia.associateBy { it.reference }
        require(mediaByReference.size == payload.coverMedia.size) { "Duplicate image" }
        val expectedReferences = cache.projectCovers.flatMap {
            listOf(it.displayContentRef, it.thumbnailContentRef)
        }.toSet()
        require(mediaByReference.keys == expectedReferences) { "Missing or unexpected image" }
        var totalMediaBytes = 0L
        payload.coverMedia.forEach { media ->
            require(coverReference.matches(media.reference)) { "Invalid image reference" }
            require(hexDigest.matches(media.sha256)) { "Invalid image digest" }
            val bytes = Base64.getDecoder().decode(media.bytesBase64)
            totalMediaBytes += bytes.size
            require(totalMediaBytes <= PortableBackupCodec.MAX_PAYLOAD_BYTES) { "Images are too large" }
            require(bytes.isNotEmpty() && digest(bytes) == media.sha256) { "Image checksum does not match" }
        }
        cache.projectCovers.forEach { cover ->
            require(mediaByReference.getValue(cover.displayContentRef).sha256 == cover.displayDigest)
            require(mediaByReference.getValue(cover.thumbnailContentRef).sha256 == cover.thumbnailDigest)
            val display = Base64.getDecoder().decode(mediaByReference.getValue(cover.displayContentRef).bytesBase64)
            val thumbnail = Base64.getDecoder().decode(mediaByReference.getValue(cover.thumbnailContentRef).bytesBase64)
            require(display.size.toLong() == cover.displayByteSize &&
                thumbnail.size.toLong() == cover.thumbnailByteSize)
            val combined = MessageDigest.getInstance("SHA-256").apply {
                update(display)
                update(thumbnail)
            }.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(combined == cover.contentDigest) { "Image pair digest does not match" }
            display.fill(0)
            thumbnail.fill(0)
        }
        require(payload.drafts == gson.fromJson(gson.toJson(payload.drafts), FormDrafts::class.java))
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
