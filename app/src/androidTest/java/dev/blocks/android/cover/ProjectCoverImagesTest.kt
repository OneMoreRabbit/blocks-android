package dev.blocks.android.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
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
    fun explicitCropSelectionControlsTheCommittedBannerDerivative() {
        val source = File(context.cacheDir, "cover-crop-selection.jpg")
        try {
            val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.RED)
                canvas.clipRect(800, 0, 1600, 900)
                canvas.drawColor(Color.BLUE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
            } finally {
                bitmap.recycle()
            }
            val processor = ProjectCoverImageProcessor()
            val left = processor.process(
                "project",
                source,
                "2026-09-16T09:00:00Z",
                CoverCropSelection(centerX = 0.25f, centerY = 0.5f, zoom = 2f),
            )
            val right = processor.process(
                "project",
                source,
                "2026-09-16T09:00:00Z",
                CoverCropSelection(centerX = 0.75f, centerY = 0.5f, zoom = 2f),
            )
            try {
                val leftBitmap = BitmapFactory.decodeByteArray(left.displayJpeg, 0, left.displayJpeg.size)
                val rightBitmap = BitmapFactory.decodeByteArray(right.displayJpeg, 0, right.displayJpeg.size)
                try {
                    val leftPixel = leftBitmap.getPixel(leftBitmap.width / 2, leftBitmap.height / 2)
                    val rightPixel = rightBitmap.getPixel(rightBitmap.width / 2, rightBitmap.height / 2)
                    assertTrue(Color.red(leftPixel) > Color.blue(leftPixel))
                    assertTrue(Color.blue(rightPixel) > Color.red(rightPixel))
                } finally {
                    leftBitmap.recycle()
                    rightBitmap.recycle()
                }
            } finally {
                left.clear()
                right.clear()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun cropperRetainsOwnedSourceUntilConfirmOrCancelThenDeletesIt() {
        val capture = File(context.cacheDir, "cropper-input.jpg")
        try {
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(30, 107, 99))
            }
            try {
                FileOutputStream(capture).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            val cropper = AndroidProjectCoverCropper(context, now = { "2026-09-16T09:00:00Z" })
            val cancelled = cropper.prepare(Uri.fromFile(capture))
            val cancelledSource = requireNotNull(cancelled.source)
            assertTrue(cancelledSource.isFile)
            cropper.discard(cancelled)
            assertFalse(cancelledSource.exists())

            val confirmed = cropper.prepare(Uri.fromFile(capture))
            val confirmedSource = requireNotNull(confirmed.source)
            val content = cropper.confirm("project", confirmed, CoverCropSelection(0.5f, 0.5f, 1.5f))
            try {
                assertFalse(confirmedSource.exists())
                assertEquals(ProjectCoverImageProcessor.DISPLAY_WIDTH, content.displayWidth)
                assertEquals(ProjectCoverImageProcessor.DISPLAY_HEIGHT, content.displayHeight)
            } finally {
                content.clear()
            }
        } finally {
            capture.delete()
        }
    }

    @Test
    fun importerCleansPriorOrphanBeforeImportAndItsOwnSourceAfterSuccessOrRejection() {
        val imports = CoverImportFiles(context)
        val orphan = File(
            imports.directoryForTest().apply { mkdirs() },
            "123e4567-e89b-42d3-a456-426614174998.source",
        ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val validCapture = File(context.cacheDir, "valid-cover-input.jpg")
        val invalidCapture = File(context.cacheDir, "invalid-cover-input.jpg")
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(30, 107, 99))
            }
            try {
                FileOutputStream(validCapture).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                }
            } finally {
                bitmap.recycle()
            }
            invalidCapture.writeBytes(byteArrayOf(4, 5, 6))
            val importer = AndroidProjectCoverImporter(context, now = { "2026-09-15T15:00:00Z" })

            val imported = importer.importCover("project", Uri.fromFile(validCapture))
            imported.clear()
            assertFalse(orphan.exists())
            assertTrue(imports.directoryForTest().listFiles().orEmpty().none { it.name.endsWith(".source") })

            assertTrue(runCatching { importer.importCover("project", Uri.fromFile(invalidCapture)) }.isFailure)
            assertTrue(imports.directoryForTest().listFiles().orEmpty().none { it.name.endsWith(".source") })
        } finally {
            validCapture.delete()
            invalidCapture.delete()
            orphan.delete()
        }
    }

}
