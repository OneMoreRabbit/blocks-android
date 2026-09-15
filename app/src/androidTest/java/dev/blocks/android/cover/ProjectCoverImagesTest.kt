package dev.blocks.android.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
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
                sourceFile.readBytes(),
                "2026-09-15T09:00:00Z",
            )
            val displayBytes = Base64.getDecoder().decode(cover.displayJpegBase64)
            val thumbnailBytes = Base64.getDecoder().decode(cover.thumbnailJpegBase64)
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
