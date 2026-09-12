package dev.blocks.android.tag

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun locator(seed: Int) = TagLocator.create(
        OpaqueTagToken.fromBytes(ByteArray(32) { (it + seed).toByte() }),
    )
}
