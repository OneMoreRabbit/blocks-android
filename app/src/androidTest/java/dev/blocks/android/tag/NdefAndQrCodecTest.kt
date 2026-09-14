package dev.blocks.android.tag

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NdefAndQrCodecTest {
    @Test
    fun ndefIntentAcceptsExactlyOneCanonicalRecord() {
        val locator = locator(1)
        val message = NdefLocatorCodec.message(locator)
        val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            data = Uri.parse(locator.encoded)
            putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(message))
        }

        assertEquals(locator, NdefLocatorCodec.fromIntent(intent))
        assertNull(
            NdefLocatorCodec.fromIntent(
                Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    data = Uri.parse(locator.encoded)
                    putExtra(
                        NfcAdapter.EXTRA_NDEF_MESSAGES,
                        arrayOf(
                            NdefMessage(
                                arrayOf(
                                    NdefRecord.createUri(locator.encoded),
                                    NdefRecord.createTextRecord("en", "extra"),
                                ),
                            ),
                        ),
                    )
                },
            ),
        )
        assertNull(
            NdefLocatorCodec.fromIntent(
                Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    data = Uri.parse(locator.encoded)
                    putExtra(
                        NfcAdapter.EXTRA_NDEF_MESSAGES,
                        arrayOf(NdefMessage(arrayOf(NdefRecord.createUri("https://example.invalid")))),
                    )
                },
            ),
        )
    }

    @Test
    fun generatedQrDecodesToTheExactSameOpaqueLocatorOnDevice() {
        val locator = locator(17)
        val bitmap = BarcodeEncoder().encodeBitmap(locator.encoded, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
        ).text

        assertEquals(locator.encoded, decoded)
        assertEquals(locator, TagLocator.parse(decoded))
    }

    @Test
    fun completeViewIntentIsParsedAndManifestRoutesOnlyTheSupportedBlocksShape() {
        val locator = locator(29)
        val valid = Intent(Intent.ACTION_VIEW, Uri.parse(locator.encoded))
        val malformed = Intent(Intent.ACTION_VIEW, Uri.parse("blocks://tag/v2/${locator.token.encoded}"))

        assertEquals(TagLaunchIntent.Valid(locator), TagLaunchIntent.parse(valid))
        assertEquals(TagLaunchIntent.Invalid, TagLaunchIntent.parse(malformed))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        fun activities(intent: Intent) = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent.setPackage(context.packageName),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
        }

        assertTrue(activities(valid).any { it.activityInfo.name == "dev.blocks.android.MainActivity" })
        assertFalse(activities(malformed).any { it.activityInfo.name == "dev.blocks.android.MainActivity" })
    }

    @Test
    fun explicitShareCarriesTheOpaqueLocatorOnlyThroughTheSharesheetPayload() {
        val locator = locator(47)
        val intent = TagShareIntent.create(locator)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(locator.encoded, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.toString().contains(locator.token.encoded))
        assertEquals("blocks://tag/v1/[redacted]", locator.toString())
    }

    @Test
    fun emptyNdefRecordHasNoLocatorAndVerifiesAsErased() {
        val message = NdefLocatorCodec.emptyMessage()

        assertTrue(NdefLocatorCodec.isEmpty(message))
        assertNull(NdefLocatorCodec.parse(message))
    }

    @Test
    fun foregroundReaderOwnsPresentationAndPreservesNdefEnumeration() {
        assertFalse(BLOCKS_NFC_READER_FLAGS and NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK != 0)
        listOf(
            NfcAdapter.FLAG_READER_NFC_A,
            NfcAdapter.FLAG_READER_NFC_B,
            NfcAdapter.FLAG_READER_NFC_F,
            NfcAdapter.FLAG_READER_NFC_V,
        ).forEach { technologyFlag ->
            assertTrue(BLOCKS_NFC_READER_FLAGS and technologyFlag != 0)
        }
    }

    private fun locator(seed: Int) = TagLocator.create(
        OpaqueTagToken.fromBytes(ByteArray(32) { (it + seed).toByte() }),
    )
}
