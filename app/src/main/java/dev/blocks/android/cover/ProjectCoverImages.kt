package dev.blocks.android.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import dev.blocks.android.project.ProjectCoverContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

fun interface ProjectCoverImporter {
    fun importCover(projectId: String, uri: Uri): ProjectCoverContent

    /** Best-effort next-start cleanup for plaintext source files abandoned by process death. */
    fun cleanupAbandonedSources(): Int = 0
}

/** Owns the narrowly named plaintext source files used only while a cover import is running. */
class CoverImportFiles(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, DIRECTORY)

    fun createRetained(): File = synchronized(ACTIVE_LOCK) {
        check(directory.mkdirs() || directory.isDirectory) { "Could not create the cover import directory" }
        File(directory, "${UUID.randomUUID()}$SOURCE_SUFFIX").also { file ->
            check(file.createNewFile()) { "Could not create a cover import source" }
            ACTIVE_PATHS += file.canonicalPath
        }
    }

    fun delete(file: File?): Boolean = synchronized(ACTIVE_LOCK) {
        file ?: return@synchronized true
        if (!isOwned(file)) return@synchronized false
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return@synchronized false
        ACTIVE_PATHS -= path
        !file.exists() || bestEffortPrivateDelete(file)
    }

    fun cleanupOrphans(): Int = synchronized(ACTIVE_LOCK) {
        var removed = 0
        directory.listFiles().orEmpty().forEach { file ->
            val path = runCatching { file.canonicalPath }.getOrNull()
            if (path != null && isOwned(file) && path !in ACTIVE_PATHS && bestEffortPrivateDelete(file)) removed += 1
        }
        removed
    }

    internal fun directoryForTest(): File = directory

    private fun isOwned(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(SOURCE_NAME)
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY = "cover-import"
        const val SOURCE_SUFFIX = ".source"
        val SOURCE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.source")
        val ACTIVE_LOCK = Any()
        val ACTIVE_PATHS = mutableSetOf<String>()
    }
}

/** Copies an ambient source to a bounded private temp file before any bounds or pixel decode. */
class AndroidProjectCoverImporter(
    context: Context,
    private val processor: ProjectCoverImageProcessor = ProjectCoverImageProcessor(),
    private val now: () -> String = { Instant.now().toString() },
) : ProjectCoverImporter {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val importFiles = CoverImportFiles(applicationContext)

    override fun cleanupAbandonedSources(): Int = importFiles.cleanupOrphans()

    override fun importCover(projectId: String, uri: Uri): ProjectCoverContent {
        importFiles.cleanupOrphans()
        val source = importFiles.createRetained()
        try {
            requireNotNull(resolver.openInputStream(uri)) { "The selected image is unavailable" }.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    try {
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= ProjectCoverImageProcessor.MAX_COMPRESSED_BYTES) {
                                "The selected image is too large"
                            }
                            output.write(buffer, 0, read)
                        }
                        require(total > 0) { "The selected image is empty" }
                        output.fd.sync()
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
            return processor.process(projectId, source, now())
        } finally {
            importFiles.delete(source)
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
    }
}

/** CPU-only transformation with hard compressed, decoded-pixel and intermediate-memory caps. */
class ProjectCoverImageProcessor {
    fun process(projectId: String, source: File, importedAt: String): ProjectCoverContent {
        require(projectId.isNotBlank()) { "A project is required for a cover" }
        require(source.isFile && source.length() in 1..MAX_COMPRESSED_BYTES) {
            "The selected image is empty or too large"
        }
        val orientation = readOrientation(source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "The selected file is not a supported image"
        }
        val orientedWidth = if (orientation.rotatesQuarterTurn()) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (orientation.rotatesQuarterTurn()) bounds.outWidth else bounds.outHeight
        val aspect = max(orientedWidth.toDouble() / orientedHeight, orientedHeight.toDouble() / orientedWidth)
        require(aspect <= MAX_ASPECT_RATIO) { "The selected image is too narrow to use safely" }

        val sample = sampleSize(orientedWidth, orientedHeight)
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            FileInputStream(source).use { input ->
                requireNotNull(BitmapFactory.decodeStream(input, null, decode)) {
                    "The selected image could not be decoded"
                }
            }
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("The selected image exceeds the cover memory limit", error)
        }
        require(decoded.width.toLong() * decoded.height <= MAX_DECODED_PIXELS) {
            decoded.recycle()
            "The selected image exceeds the decoded-pixel limit"
        }
        val oriented = try {
            orient(decoded, orientation)
        } catch (error: OutOfMemoryError) {
            decoded.recycle()
            throw IllegalArgumentException("The selected image exceeds the transform memory limit", error)
        }
        if (oriented !== decoded) decoded.recycle()
        return try {
            val crop = centreCropRegion(oriented)
            try {
                val display = Bitmap.createScaledBitmap(crop, DISPLAY_WIDTH, DISPLAY_HEIGHT, true)
                try {
                    val thumbnail = Bitmap.createScaledBitmap(display, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
                    try {
                        val displayBytes = encodeOpaqueJpeg(display, DISPLAY_QUALITY, MAX_DISPLAY_BYTES)
                        val thumbnailBytes = encodeOpaqueJpeg(thumbnail, THUMBNAIL_QUALITY, MAX_THUMBNAIL_BYTES)
                        ProjectCoverContent(
                            projectId = projectId,
                            displayJpeg = displayBytes,
                            thumbnailJpeg = thumbnailBytes,
                            displayWidth = DISPLAY_WIDTH,
                            displayHeight = DISPLAY_HEIGHT,
                            thumbnailWidth = THUMBNAIL_WIDTH,
                            thumbnailHeight = THUMBNAIL_HEIGHT,
                            displayDigest = digest(displayBytes),
                            thumbnailDigest = digest(thumbnailBytes),
                            contentDigest = digest(displayBytes, thumbnailBytes),
                            importedAt = importedAt,
                        )
                    } finally {
                        thumbnail.recycle()
                    }
                } finally {
                    display.recycle()
                }
            } finally {
                if (crop !== oriented) crop.recycle()
            }
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("The selected image exceeds the derivative memory limit", error)
        } finally {
            oriented.recycle()
        }
    }

    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (true) {
            val decodedWidth = ceil(width.toDouble() / sample).toLong()
            val decodedHeight = ceil(height.toDouble() / sample).toLong()
            if (
                decodedWidth * decodedHeight <= MAX_DECODED_PIXELS &&
                centreCropPixels(decodedWidth, decodedHeight) <= MAX_CROP_PIXELS
            ) return sample
            require(sample < MAX_SAMPLE_SIZE) { "The selected image cannot be decoded within the cover memory limit" }
            sample *= 2
        }
    }

    private fun centreCropPixels(width: Long, height: Long): Long {
        val sourceAspect = width.toDouble() / height
        return if (sourceAspect > TARGET_ASPECT) {
            (height * TARGET_ASPECT).toLong().coerceAtLeast(1) * height
        } else {
            width * (width / TARGET_ASPECT).toLong().coerceAtLeast(1)
        }
    }

    private fun centreCropRegion(source: Bitmap): Bitmap {
        val sourceAspect = source.width.toDouble() / source.height
        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect > TARGET_ASPECT) {
            cropHeight = source.height
            cropWidth = (cropHeight * TARGET_ASPECT).toInt().coerceIn(1, source.width)
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth / TARGET_ASPECT).toInt().coerceIn(1, source.height)
        }
        require(cropWidth.toLong() * cropHeight <= MAX_CROP_PIXELS) {
            "The selected image exceeds the crop-memory limit"
        }
        return Bitmap.createBitmap(
            source,
            (source.width - cropWidth) / 2,
            (source.height - cropHeight) / 2,
            cropWidth,
            cropHeight,
        )
    }

    private fun readOrientation(source: File): Int = runCatching {
        ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun Int.rotatesQuarterTurn() = this in setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        require(source.allocationByteCount.toLong() * 2 <= MAX_ORIENTATION_BYTES) {
            "The selected image exceeds the orientation-memory limit"
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun encodeOpaqueJpeg(source: Bitmap, quality: Int, maxBytes: Int): ByteArray {
        val opaque = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(opaque).apply {
                drawColor(PAPER)
                drawBitmap(source, 0f, 0f, null)
            }
            ByteArrayOutputStream().use { output ->
                check(opaque.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "The project cover could not be encoded"
                }
                output.toByteArray().also { require(it.size <= maxBytes) { "The cover derivative is too large" } }
            }
        } finally {
            opaque.recycle()
        }
    }

    private fun digest(vararg values: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val DISPLAY_WIDTH = 1200
        const val DISPLAY_HEIGHT = 675
        const val THUMBNAIL_WIDTH = 480
        const val THUMBNAIL_HEIGHT = 270
        const val MAX_COMPRESSED_BYTES = 32L * 1024 * 1024
        const val MAX_DECODED_PIXELS = 8_000_000L
        const val MAX_CROP_PIXELS = 2_500_000L
        const val MAX_ORIENTATION_BYTES = 64L * 1024 * 1024
        const val MAX_ASPECT_RATIO = 12.0
        private const val MAX_DISPLAY_BYTES = 4 * 1024 * 1024
        private const val MAX_THUMBNAIL_BYTES = 1024 * 1024
        private const val MAX_SOURCE_DIMENSION = 20_000
        private const val MAX_SAMPLE_SIZE = 32
        private const val DISPLAY_QUALITY = 88
        private const val THUMBNAIL_QUALITY = 82
        private const val TARGET_ASPECT = DISPLAY_WIDTH.toDouble() / DISPLAY_HEIGHT
        private val PAPER = Color.rgb(246, 245, 240)
    }
}

data class PendingCameraCover(val uri: Uri, val path: String)

/** Owns plaintext camera destinations and removes them best-effort after terminal results. */
class CoverCaptureFiles(private val context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY)

    fun create(): PendingCameraCover {
        check(directory.mkdirs() || directory.isDirectory) { "Could not create the cover capture directory" }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "Could not create a cover capture destination" }
        return PendingCameraCover(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            file.absolutePath,
        )
    }

    fun restore(path: String?): PendingCameraCover? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!isOwned(file) || !file.isFile) return null
        return PendingCameraCover(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            file.absolutePath,
        )
    }

    fun delete(capture: PendingCameraCover?): Boolean {
        capture ?: return true
        val file = File(capture.path)
        return !file.exists() || isOwned(file) && bestEffortPrivateDelete(file)
    }

    fun cleanupOrphans(exceptPath: String? = null): Int {
        var removed = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.absolutePath != exceptPath && isOwned(file) && bestEffortPrivateDelete(file)) removed += 1
        }
        return removed
    }

    private fun isOwned(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(FILE_NAME)
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY = "cover-capture"
        val FILE_NAME = Regex("[0-9a-f-]{36}\\.jpg")
    }
}

private fun bestEffortPrivateDelete(file: File): Boolean {
    runCatching {
        if (file.exists()) {
            RandomAccessFile(file, "rw").use { source ->
                source.setLength(0)
                source.fd.sync()
            }
        }
    }
    return !file.exists() || runCatching { file.delete() }.getOrDefault(false)
}
