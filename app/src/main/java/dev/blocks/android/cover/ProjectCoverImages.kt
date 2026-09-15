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
import dev.blocks.android.project.ProjectCover
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.max

fun interface ProjectCoverImporter {
    fun importCover(projectId: String, uri: Uri): ProjectCover
}

/** Reads a selected source once, then retains only bounded metadata-free JPEG derivatives. */
class AndroidProjectCoverImporter(
    context: Context,
    private val processor: ProjectCoverImageProcessor = ProjectCoverImageProcessor(),
    private val now: () -> String = { Instant.now().toString() },
) : ProjectCoverImporter {
    private val resolver = context.applicationContext.contentResolver

    override fun importCover(projectId: String, uri: Uri): ProjectCover {
        val source = requireNotNull(resolver.openInputStream(uri)) { "The selected image is unavailable" }
            .use(::readBounded)
        return try {
            processor.process(projectId, source, now())
        } finally {
            source.fill(0)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_SOURCE_BYTES) { "The selected image is too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    }
}

/** CPU-only transformation; callers run it away from the interaction thread. */
class ProjectCoverImageProcessor {
    fun process(projectId: String, source: ByteArray, importedAt: String): ProjectCover {
        require(projectId.isNotBlank()) { "A project is required for a cover" }
        require(source.isNotEmpty()) { "The selected image is empty" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "The selected file is not a supported image"
        }
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size, decode)) {
            "The selected image could not be decoded"
        }
        val oriented = orient(decoded, orientation(source))
        if (oriented !== decoded) decoded.recycle()
        return try {
            val display = crop(oriented, DISPLAY_WIDTH, DISPLAY_HEIGHT)
            val thumbnail = crop(oriented, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            try {
                val displayBytes = encodeOpaqueJpeg(display, DISPLAY_QUALITY)
                val thumbnailBytes = encodeOpaqueJpeg(thumbnail, THUMBNAIL_QUALITY)
                ProjectCover(
                    projectId = projectId,
                    displayJpegBase64 = Base64.getEncoder().encodeToString(displayBytes),
                    thumbnailJpegBase64 = Base64.getEncoder().encodeToString(thumbnailBytes),
                    displayWidth = DISPLAY_WIDTH,
                    displayHeight = DISPLAY_HEIGHT,
                    thumbnailWidth = THUMBNAIL_WIDTH,
                    thumbnailHeight = THUMBNAIL_HEIGHT,
                    contentDigest = digest(displayBytes, thumbnailBytes),
                    importedAt = importedAt,
                ).also {
                    displayBytes.fill(0)
                    thumbnailBytes.fill(0)
                }
            } finally {
                display.recycle()
                thumbnail.recycle()
            }
        } finally {
            oriented.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= DISPLAY_WIDTH && height / (sample * 2) >= DISPLAY_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    private fun orientation(source: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun crop(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val cropped = Bitmap.createBitmap(
                scaled,
                (scaledWidth - width) / 2,
                (scaledHeight - height) / 2,
                width,
                height,
            )
        if (cropped !== scaled && scaled !== source) scaled.recycle()
        return cropped
    }

    private fun encodeOpaqueJpeg(source: Bitmap, quality: Int): ByteArray {
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
                output.toByteArray()
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
        private const val DISPLAY_QUALITY = 88
        private const val THUMBNAIL_QUALITY = 82
        private const val MAX_SOURCE_DIMENSION = 20_000
        private val PAPER = Color.rgb(246, 245, 240)
    }
}

data class PendingCameraCover(val uri: Uri, val path: String)

/** Owns plaintext camera destinations and removes them after every terminal result. */
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

    fun delete(capture: PendingCameraCover?) {
        capture ?: return
        val file = File(capture.path)
        if (isOwned(file) && file.exists()) secureDelete(file)
    }

    fun cleanupOrphans(exceptPath: String? = null) {
        directory.listFiles().orEmpty().forEach { file ->
            if (file.absolutePath != exceptPath && isOwned(file)) secureDelete(file)
        }
    }

    private fun secureDelete(file: File) {
        RandomAccessFile(file, "rw").use { source ->
            source.setLength(0)
            source.fd.sync()
        }
        check(file.delete()) { "Could not delete the cover capture" }
    }

    private fun isOwned(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(FILE_NAME)
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY = "cover-capture"
        val FILE_NAME = Regex("[0-9a-f-]{36}\\.jpg")
    }
}
