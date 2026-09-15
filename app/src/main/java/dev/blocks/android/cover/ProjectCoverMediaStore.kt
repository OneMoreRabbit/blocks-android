package dev.blocks.android.cover

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectCoverContent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProjectCoverVariant { DISPLAY, THUMBNAIL }

/** Media bytes live outside the general state snapshot behind this tenant-scoped boundary. */
interface ProjectCoverMediaStore {
    fun stage(
        tenantId: String,
        projectId: String,
        content: ProjectCoverContent,
        previous: ProjectCover?,
    ): ProjectCover

    fun read(tenantId: String, cover: ProjectCover, variant: ProjectCoverVariant): ByteArray
    fun retire(tenantId: String, cover: ProjectCover)
    fun cleanupOrphans(tenantId: String, retained: Collection<ProjectCover>)
    fun eraseTenant(tenantId: String)
}

/** Android Keystore protects a separate AES-GCM key for each app-private tenant media directory. */
class AndroidEncryptedProjectCoverMediaStore(context: Context) : ProjectCoverMediaStore {
    private val applicationContext = context.applicationContext
    private val root = File(applicationContext.filesDir, ROOT_DIRECTORY)

    @Synchronized
    override fun stage(
        tenantId: String,
        projectId: String,
        content: ProjectCoverContent,
        previous: ProjectCover?,
    ): ProjectCover {
        require(tenantId.isNotBlank() && projectId.isNotBlank()) { "A tenant and project are required" }
        require(content.projectId == projectId) { "Cover content belongs to another project" }
        previous?.let {
            require(it.tenantId == tenantId && it.projectId == projectId) { "Previous cover belongs elsewhere" }
        }
        validateContent(content)
        val coverId = previous?.coverId ?: UUID.randomUUID().toString()
        require(coverId.matches(UUID_PATTERN)) { "Cover identity is invalid" }
        val revision = (previous?.revision ?: 0) + 1
        val displayRef = "$coverId-r$revision-display.enc"
        val thumbnailRef = "$coverId-r$revision-thumbnail.enc"
        val createdAt = previous?.createdAt ?: content.importedAt
        val keyExisted = existingKey(keyAlias(tenantId)) != null
        val key = existingKey(keyAlias(tenantId)) ?: createKey(keyAlias(tenantId))
        val written = mutableListOf<File>()
        return try {
            written += writeVerifiedObject(tenantId, displayRef, content.displayJpeg, content.displayDigest, key)
            written += writeVerifiedObject(tenantId, thumbnailRef, content.thumbnailJpeg, content.thumbnailDigest, key)
            ProjectCover(
                coverId = coverId,
                tenantId = tenantId,
                projectId = projectId,
                mediaType = JPEG_MEDIA_TYPE,
                displayContentRef = displayRef,
                displayWidth = content.displayWidth,
                displayHeight = content.displayHeight,
                displayByteSize = content.displayJpeg.size.toLong(),
                displayDigest = content.displayDigest,
                thumbnailContentRef = thumbnailRef,
                thumbnailWidth = content.thumbnailWidth,
                thumbnailHeight = content.thumbnailHeight,
                thumbnailByteSize = content.thumbnailJpeg.size.toLong(),
                thumbnailDigest = content.thumbnailDigest,
                contentDigest = content.contentDigest,
                revision = revision,
                createdAt = createdAt,
                updatedAt = content.importedAt,
            )
        } catch (error: Exception) {
            written.forEach(::deleteBestEffort)
            tenantDirectory(tenantId).listFiles().orEmpty()
                .filter { it.name.endsWith(TEMP_SUFFIX) }
                .forEach(::deleteBestEffort)
            if (!keyExisted && tenantDirectory(tenantId).listFiles().isNullOrEmpty()) {
                runCatching { deleteKey(keyAlias(tenantId)) }
                runCatching { tenantDirectory(tenantId).delete() }
            }
            throw IllegalStateException("Could not stage encrypted project cover media", error)
        }
    }

    @Synchronized
    override fun read(tenantId: String, cover: ProjectCover, variant: ProjectCoverVariant): ByteArray {
        require(cover.tenantId == tenantId) { "Cover belongs to another tenant" }
        val reference = when (variant) {
            ProjectCoverVariant.DISPLAY -> cover.displayContentRef
            ProjectCoverVariant.THUMBNAIL -> cover.thumbnailContentRef
        }
        val expectedSize = when (variant) {
            ProjectCoverVariant.DISPLAY -> cover.displayByteSize
            ProjectCoverVariant.THUMBNAIL -> cover.thumbnailByteSize
        }
        val expectedDigest = when (variant) {
            ProjectCoverVariant.DISPLAY -> cover.displayDigest
            ProjectCoverVariant.THUMBNAIL -> cover.thumbnailDigest
        }
        validateReference(reference)
        val key = requireNotNull(existingKey(keyAlias(tenantId))) { "Encrypted cover media key is unavailable" }
        return decryptObject(objectFile(tenantId, reference), tenantId, reference, key).also { bytes ->
            check(bytes.size.toLong() == expectedSize && digest(bytes) == expectedDigest) {
                bytes.fill(0)
                "Encrypted cover media did not match its metadata"
            }
        }
    }

    @Synchronized
    override fun retire(tenantId: String, cover: ProjectCover) {
        require(cover.tenantId == tenantId) { "Cover belongs to another tenant" }
        listOf(cover.displayContentRef, cover.thumbnailContentRef).forEach { reference ->
            validateReference(reference)
            check(deleteBestEffort(objectFile(tenantId, reference))) { "Could not retire encrypted cover media" }
        }
    }

    @Synchronized
    override fun cleanupOrphans(tenantId: String, retained: Collection<ProjectCover>) {
        require(retained.all { it.tenantId == tenantId }) { "Retained covers cross a tenant boundary" }
        val references = retained.flatMap { listOf(it.displayContentRef, it.thumbnailContentRef) }.toSet()
        references.forEach(::validateReference)
        tenantDirectory(tenantId).listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(TEMP_SUFFIX) || file.name !in references) {
                check(deleteBestEffort(file)) { "Could not remove orphaned encrypted cover media" }
            }
        }
    }

    @Synchronized
    override fun eraseTenant(tenantId: String) {
        val failures = mutableListOf<Throwable>()
        runCatching {
            tenantDirectory(tenantId).listFiles().orEmpty().forEach { file ->
                check(deleteBestEffort(file)) { "Could not delete encrypted cover media" }
            }
            val directory = tenantDirectory(tenantId)
            if (directory.exists()) check(directory.delete()) { "Could not delete the tenant cover directory" }
        }.exceptionOrNull()?.let(failures::add)
        runCatching { deleteKey(keyAlias(tenantId)) }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not erase encrypted tenant cover media", failure)
        }
    }

    private fun validateContent(content: ProjectCoverContent) {
        require(content.displayWidth > 0 && content.displayHeight > 0) { "Display bounds are invalid" }
        require(content.thumbnailWidth > 0 && content.thumbnailHeight > 0) { "Thumbnail bounds are invalid" }
        require(content.displayJpeg.size in 1..MAX_OBJECT_BYTES) { "Display media size is invalid" }
        require(content.thumbnailJpeg.size in 1..MAX_OBJECT_BYTES) { "Thumbnail media size is invalid" }
        require(digest(content.displayJpeg) == content.displayDigest) { "Display media digest is invalid" }
        require(digest(content.thumbnailJpeg) == content.thumbnailDigest) { "Thumbnail media digest is invalid" }
        require(digest(content.displayJpeg, content.thumbnailJpeg) == content.contentDigest) {
            "Combined media digest is invalid"
        }
        require(content.importedAt.isNotBlank()) { "Cover import time is missing" }
    }

    private fun writeVerifiedObject(
        tenantId: String,
        reference: String,
        plaintext: ByteArray,
        expectedDigest: String,
        key: SecretKey,
    ): File {
        validateReference(reference)
        val directory = tenantDirectory(tenantId)
        check(directory.mkdirs() || directory.isDirectory) { "Could not create tenant cover media directory" }
        val destination = objectFile(tenantId, reference)
        check(!destination.exists()) { "Encrypted cover media already exists" }
        val temporary = File(directory, ".$reference-${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(aad(tenantId, reference))
            }
            FileOutputStream(temporary).use { output ->
                output.write(FORMAT_VERSION)
                output.write(cipher.iv.size)
                output.write(cipher.iv)
                output.write(cipher.doFinal(plaintext))
                output.fd.sync()
            }
            val verified = decryptObject(temporary, tenantId, reference, key)
            try {
                check(verified.size == plaintext.size && digest(verified) == expectedDigest) {
                    "Encrypted cover media verification failed"
                }
            } finally {
                verified.fill(0)
            }
            check(temporary.renameTo(destination)) { "Could not commit immutable cover media" }
            return destination
        } finally {
            if (temporary.exists()) deleteBestEffort(temporary)
        }
    }

    private fun decryptObject(file: File, tenantId: String, reference: String, key: SecretKey): ByteArray {
        check(file.isFile && file.length() in MIN_ENCRYPTED_BYTES..MAX_ENCRYPTED_BYTES) {
            "Encrypted cover media is missing or malformed"
        }
        val packed = ByteArray(file.length().toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < packed.size) {
                val read = input.read(packed, offset, packed.size - offset)
                check(read > 0) { "Encrypted cover media ended early" }
                offset += read
            }
            check(input.read() == -1) { "Encrypted cover media exceeds its declared size" }
        }
        try {
            check(packed[0].toInt() == FORMAT_VERSION) { "Encrypted cover media version is unsupported" }
            val ivLength = packed[1].toInt() and 0xff
            check(ivLength > 0 && packed.size > HEADER_BYTES + ivLength) { "Encrypted cover media is malformed" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, packed.copyOfRange(2, 2 + ivLength)))
                updateAAD(aad(tenantId, reference))
            }
            return cipher.doFinal(packed.copyOfRange(2 + ivLength, packed.size)).also {
                check(it.size <= MAX_OBJECT_BYTES) { "Decrypted cover media exceeds its bound" }
            }
        } finally {
            packed.fill(0)
        }
    }

    private fun validateReference(reference: String) {
        require(reference.matches(REFERENCE_PATTERN)) { "Cover media reference is invalid" }
    }

    private fun tenantDirectory(tenantId: String) = File(root, tenantDigest(tenantId))
    private fun objectFile(tenantId: String, reference: String) = File(tenantDirectory(tenantId), reference)
    private fun keyAlias(tenantId: String) = "$KEY_PREFIX${tenantDigest(tenantId)}"
    private fun aad(tenantId: String, reference: String) =
        "blocks-cover-media:$tenantId:$reference".toByteArray(Charsets.UTF_8)

    private fun tenantDigest(tenantId: String) = digest(tenantId.toByteArray(Charsets.UTF_8))

    private fun createKey(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

    private fun existingKey(alias: String): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias, null) as? SecretKey
    }

    private fun deleteKey(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    internal fun encryptedFiles(tenantId: String): List<File> = tenantDirectory(tenantId).listFiles().orEmpty().toList()

    private companion object {
        const val ROOT_DIRECTORY = "project-cover-media-v1"
        const val KEY_PREFIX = "blocks-cover-media-v1-"
        const val JPEG_MEDIA_TYPE = "image/jpeg"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 2
        const val MAX_OBJECT_BYTES = 4 * 1024 * 1024
        const val MIN_ENCRYPTED_BYTES = 32L
        const val MAX_ENCRYPTED_BYTES = MAX_OBJECT_BYTES.toLong() + 64
        const val TEMP_SUFFIX = ".tmp"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val REFERENCE_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}-r[1-9][0-9]*-(display|thumbnail)\\.enc",
        )
    }
}

/** Deterministic bounded fake used by repository/presentation tests only. */
class InMemoryProjectCoverMediaStore : ProjectCoverMediaStore {
    private val objects = linkedMapOf<Pair<String, String>, ByteArray>()
    var stageCalls = 0
        private set
    var readCalls = 0
        private set
    var retireCalls = 0
        private set
    var cleanupCalls = 0
        private set
    var eraseCalls = 0
        private set

    override fun stage(
        tenantId: String,
        projectId: String,
        content: ProjectCoverContent,
        previous: ProjectCover?,
    ): ProjectCover {
        stageCalls += 1
        val id = previous?.coverId ?: UUID.randomUUID().toString()
        val revision = (previous?.revision ?: 0) + 1
        val displayRef = "$id-r$revision-display.enc"
        val thumbnailRef = "$id-r$revision-thumbnail.enc"
        objects[tenantId to displayRef] = content.displayJpeg.copyOf()
        objects[tenantId to thumbnailRef] = content.thumbnailJpeg.copyOf()
        return ProjectCover(
            id,
            tenantId,
            projectId,
            "image/jpeg",
            displayRef,
            content.displayWidth,
            content.displayHeight,
            content.displayJpeg.size.toLong(),
            content.displayDigest,
            thumbnailRef,
            content.thumbnailWidth,
            content.thumbnailHeight,
            content.thumbnailJpeg.size.toLong(),
            content.thumbnailDigest,
            content.contentDigest,
            revision,
            previous?.createdAt ?: content.importedAt,
            content.importedAt,
        )
    }

    override fun read(tenantId: String, cover: ProjectCover, variant: ProjectCoverVariant): ByteArray {
        readCalls += 1
        require(cover.tenantId == tenantId)
        val reference = if (variant == ProjectCoverVariant.THUMBNAIL) {
            cover.thumbnailContentRef
        } else {
            cover.displayContentRef
        }
        return requireNotNull(objects[tenantId to reference]).copyOf()
    }

    override fun retire(tenantId: String, cover: ProjectCover) {
        retireCalls += 1
        objects.remove(tenantId to cover.displayContentRef)?.fill(0)
        objects.remove(tenantId to cover.thumbnailContentRef)?.fill(0)
    }

    override fun cleanupOrphans(tenantId: String, retained: Collection<ProjectCover>) {
        cleanupCalls += 1
        val keep = retained.flatMap { listOf(it.displayContentRef, it.thumbnailContentRef) }.toSet()
        objects.keys.filter { it.first == tenantId && it.second !in keep }.toList().forEach { key ->
            objects.remove(key)?.fill(0)
        }
    }

    override fun eraseTenant(tenantId: String) {
        eraseCalls += 1
        objects.keys.filter { it.first == tenantId }.toList().forEach { key -> objects.remove(key)?.fill(0) }
    }

    fun objectCount(tenantId: String): Int = objects.keys.count { it.first == tenantId }
}

private fun digest(vararg values: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach(digest::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun deleteBestEffort(file: File): Boolean = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
