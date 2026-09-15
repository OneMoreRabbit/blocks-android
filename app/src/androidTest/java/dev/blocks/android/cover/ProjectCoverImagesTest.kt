package dev.blocks.android.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectCoverImagesTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun processorCreatesBoundedDerivativesAndStripsLocationMetadata() {
        val sourceFile = File(context.cacheDir, "cover-source.jpg")
        val sanitizedFile = File(context.cacheDir, "cover-sanitized.jpg")
        try {
            val bitmap = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(30, 107, 99))
            }
            FileOutputStream(sourceFile).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
            }
            bitmap.recycle()
            ExifInterface(sourceFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                setAttribute(ExifInterface.TAG_MAKE, "Sensitive camera")
                saveAttributes()
            }

            val cover = ProjectCoverImageProcessor().process(
                "project-cover",
                sourceFile,
                "2026-09-15T09:00:00Z",
            )
            val displayBytes = cover.displayJpeg
            val thumbnailBytes = cover.thumbnailJpeg
            val display = BitmapFactory.decodeByteArray(displayBytes, 0, displayBytes.size)
            val thumbnail = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)

            assertEquals(ProjectCoverImageProcessor.DISPLAY_WIDTH, display.width)
            assertEquals(ProjectCoverImageProcessor.DISPLAY_HEIGHT, display.height)
            assertEquals(ProjectCoverImageProcessor.THUMBNAIL_WIDTH, thumbnail.width)
            assertEquals(ProjectCoverImageProcessor.THUMBNAIL_HEIGHT, thumbnail.height)
            assertFalse(display.width == 1800 && display.height == 1200)
            sanitizedFile.writeBytes(displayBytes)
            ExifInterface(sanitizedFile.absolutePath).apply {
                assertNull(getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertNull(getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
                assertNull(getAttribute(ExifInterface.TAG_MAKE))
            }
            display.recycle()
            thumbnail.recycle()
            displayBytes.fill(0)
            thumbnailBytes.fill(0)
        } finally {
            sourceFile.delete()
            sanitizedFile.delete()
        }
    }

    @Test
    fun processorRejectsOverLimitCompressedAndExtremeAspectSourcesBeforePixelDecode() {
        val overLimit = File(context.cacheDir, "cover-over-limit.jpg")
        val extreme = File(context.cacheDir, "cover-extreme.jpg")
        try {
            RandomAccessFile(overLimit, "rw").use {
                it.setLength(ProjectCoverImageProcessor.MAX_COMPRESSED_BYTES + 1)
            }
            assertTrue(
                runCatching {
                    ProjectCoverImageProcessor().process("project", overLimit, "2026-09-15T09:00:00Z")
                }.isFailure,
            )
            val extremeBitmap = Bitmap.createBitmap(1_300, 100, Bitmap.Config.ARGB_8888)
            try {
                extreme.outputStream().use { output ->
                    assertTrue(extremeBitmap.compress(Bitmap.CompressFormat.JPEG, 80, output))
                }
            } finally {
                extremeBitmap.recycle()
            }
            assertTrue(
                runCatching {
                    ProjectCoverImageProcessor().process("project", extreme, "2026-09-15T09:00:00Z")
                }.exceptionOrNull()?.message?.contains("too narrow") == true,
            )
        } finally {
            overLimit.delete()
            extreme.delete()
        }
    }

    @Test
    fun processorSamplesMaximumPermittedDimensionsWithinDecodedAndCropCaps() {
        val processor = ProjectCoverImageProcessor()

        assertEquals(16, processor.sampleSize(20_000, 20_000))
        assertEquals(4, processor.sampleSize(20_000, 2_000))
        assertEquals(4, processor.sampleSize(2_000, 20_000))
    }

    @Test
    fun importerCleansPriorOrphanBeforeImportAndItsOwnSourceAfterSuccessOrRejection() {
        val imports = CoverImportFiles(context)
        val orphan = File(
            imports.directoryForTest().apply { mkdirs() },
            "123e4567-e89b-42d3-a456-426614174998.source",
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val captures = CoverCaptureFiles(context)
        val validCapture = captures.create()
        val invalidCapture = captures.create()
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(30, 107, 99))
            }
            try {
                FileOutputStream(validCapture.path).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                }
            } finally {
                bitmap.recycle()
            }
            File(invalidCapture.path).writeBytes(byteArrayOf(4, 5, 6))
            val importer = AndroidProjectCoverImporter(context, now = { "2026-09-15T15:00:00Z" })

            val imported = importer.importCover("project", validCapture.uri)
            imported.clear()
            assertFalse(orphan.exists())
            assertTrue(imports.directoryForTest().listFiles().orEmpty().none { it.name.endsWith(".source") })

            assertTrue(runCatching { importer.importCover("project", invalidCapture.uri) }.isFailure)
            assertTrue(imports.directoryForTest().listFiles().orEmpty().none { it.name.endsWith(".source") })
        } finally {
            captures.delete(validCapture)
            captures.delete(invalidCapture)
            orphan.delete()
        }
    }

    @Test
    fun cameraCaptureFilesRestoreOnlyOwnedFilesAndCleanTerminalOrphans() {
        val files = CoverCaptureFiles(context)
        val retained = files.create()
        val orphan = files.create()
        File(retained.path).writeBytes(byteArrayOf(1, 2, 3))
        File(orphan.path).writeBytes(byteArrayOf(4, 5, 6))

        assertEquals(retained.path, files.restore(retained.path)?.path)
        assertNull(files.restore(File(context.filesDir, "not-owned.jpg").path))

        files.cleanupOrphans(retained.path)
        assertTrue(File(retained.path).isFile)
        assertFalse(File(orphan.path).exists())
        files.delete(retained)
        assertFalse(File(retained.path).exists())
    }
}
