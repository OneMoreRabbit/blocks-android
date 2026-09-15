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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NdefAndQrCodecTest {
    @Test
    fun projectLinkQrAndNdefContainTheExactSameHttpsAddress() {
        val link = projectLink()
        val message = NdefLocatorCodec.message(link)
        val bitmap = BarcodeEncoder().encodeBitmap(link.encoded, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
        ).text

        assertEquals(link.encoded, decoded)
        assertEquals(link, NdefLocatorCodec.parse(message))
        assertEquals(link, ProjectLink.parse(decoded))
    }

    @Test
    fun formatableTagIsFormattedThenVerifiedOnlyAfterFreshEnumeration() {
        val link = projectLink()
        val events = mutableListOf<String>()
        val formatable = FakeFormatable(events)
        val factory = FakeTechnologies(formatable = formatable)
        val io = NfcTagIo(factory)

        assertEquals(
            NfcProgrammingResult.AwaitingReadBack(requireNotNull(FakeHandle.presentationCorrelation)),
            io.programAndVerify(FakeHandle, link),
        )
        assertEquals(listOf("format-connect", "format-write", "format-close"), events)
        assertEquals(link, NdefLocatorCodec.parse(formatable.formatted))

        events.clear()
        factory.ndef = FakeNdef(events, message = formatable.formatted)
        assertEquals(
            NfcProgrammingResult.Verified(),
            io.verifyWritten(FakeHandle, link, requireNotNull(FakeHandle.presentationCorrelation)),
        )
        assertEquals(listOf("ndef-connect", "ndef-read", "ndef-close"), events)
    }

    @Test
    fun formatableWriteAndClearRejectAnotherMatchingTagBeforeSameTagReadBack() {
        val link = projectLink()
        val handleA = FakeHandleWithCorrelation(1)
        val handleB = FakeHandleWithCorrelation(2)
        val expected = requireNotNull(handleA.presentationCorrelation)

        val writeEvents = mutableListOf<String>()
        val writeFormatable = FakeFormatable(writeEvents)
        val writeFactory = FakeTechnologies(formatable = writeFormatable)
        val writeIo = NfcTagIo(writeFactory)
        assertEquals(
            NfcProgrammingResult.AwaitingReadBack(expected),
            writeIo.programAndVerify(handleA, link),
        )
        writeEvents.clear()
        writeFactory.ndef = FakeNdef(writeEvents, message = writeFormatable.formatted)
        assertEquals(
            NfcProgrammingResult.AwaitingReadBack(expected),
            writeIo.verifyWritten(handleB, link, expected),
        )
        assertTrue(writeEvents.isEmpty())
        assertEquals(NfcProgrammingResult.Verified(), writeIo.verifyWritten(handleA, link, expected))

        val clearEvents = mutableListOf<String>()
        val clearFormatable = FakeFormatable(clearEvents)
        val clearFactory = FakeTechnologies(formatable = clearFormatable)
        val clearIo = NfcTagIo(clearFactory)
        assertEquals(
            NfcClearResult.AwaitingReadBack(expected),
            clearIo.clearAndVerify(handleA),
        )
        clearEvents.clear()
        clearFactory.ndef = FakeNdef(clearEvents, message = clearFormatable.formatted)
        assertEquals(
            NfcClearResult.AwaitingReadBack(expected),
            clearIo.verifyCleared(handleB, expected),
        )
        assertTrue(clearEvents.isEmpty())
        assertEquals(NfcClearResult.Verified(), clearIo.verifyCleared(handleA, expected))
    }

    @Test
    fun missingOrUnstableFormatableCorrelationNeverClaimsVerification() {
        val link = projectLink()
        assertEquals(
            NfcProgrammingResult.WriteUncertain,
            NfcTagIo(FakeTechnologies(formatable = FakeFormatable(mutableListOf())))
                .programAndVerify(NoCorrelationHandle, link),
        )
        assertEquals(
            NfcClearResult.WriteUncertain,
            NfcTagIo(FakeTechnologies(formatable = FakeFormatable(mutableListOf())))
                .clearAndVerify(NoCorrelationHandle),
        )

        val handleA = FakeHandleWithCorrelation(3)
        val handleAWithChangedIdentifier = FakeHandleWithCorrelation(4)
        val expected = requireNotNull(handleA.presentationCorrelation)
        val events = mutableListOf<String>()
        val factory = FakeTechnologies(ndef = FakeNdef(events, message = NdefLocatorCodec.message(link)))
        assertEquals(
            NfcProgrammingResult.AwaitingReadBack(expected),
            NfcTagIo(factory).verifyWritten(handleAWithChangedIdentifier, link, expected),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationBeforeDispatchAndDuringPreWriteIoPreventsWriteAndClear() {
        val link = projectLink()
        val beforeDispatch = NfcIoCancellation().also(NfcIoCancellation::cancel)
        val untouchedEvents = mutableListOf<String>()
        val untouchedIo = NfcTagIo(FakeTechnologies(ndef = FakeNdef(untouchedEvents)))
        assertEquals(NfcProgrammingResult.Cancelled, untouchedIo.programAndVerify(FakeHandle, link, beforeDispatch))
        assertEquals(NfcClearResult.Cancelled, untouchedIo.clearAndVerify(FakeHandle, beforeDispatch))
        assertTrue(untouchedEvents.isEmpty())

        val connectBlocked = BlockingNdef(IoBlock.CONNECT)
        val connectCancellation = NfcIoCancellation()
        val connectResult = runCancelled(connectBlocked.entered, connectCancellation) {
            NfcTagIo(FakeTechnologies(ndef = connectBlocked)).programAndVerify(FakeHandle, link, connectCancellation)
        }
        assertEquals(NfcProgrammingResult.Cancelled, connectResult)
        assertFalse(connectBlocked.events.contains("ndef-write"))

        val readBlocked = BlockingNdef(IoBlock.READ, NdefLocatorCodec.message(link))
        val readCancellation = NfcIoCancellation()
        val readResult = runCancelled(readBlocked.entered, readCancellation) {
            NfcTagIo(FakeTechnologies(ndef = readBlocked)).clearAndVerify(FakeHandle, readCancellation)
        }
        assertEquals(NfcClearResult.Cancelled, readResult)
        assertFalse(readBlocked.events.contains("ndef-write"))

        val formatConnectBlocked = BlockingFormatable(IoBlock.CONNECT)
        val formatCancellation = NfcIoCancellation()
        val formatResult = runCancelled(formatConnectBlocked.entered, formatCancellation) {
            NfcTagIo(FakeTechnologies(formatable = formatConnectBlocked))
                .programAndVerify(FakeHandle, link, formatCancellation)
        }
        assertEquals(NfcProgrammingResult.Cancelled, formatResult)
        assertFalse(formatConnectBlocked.events.contains("format-write"))

        val clearFormatConnectBlocked = BlockingFormatable(IoBlock.CONNECT)
        val clearFormatCancellation = NfcIoCancellation()
        val clearFormatResult = runCancelled(clearFormatConnectBlocked.entered, clearFormatCancellation) {
            NfcTagIo(FakeTechnologies(formatable = clearFormatConnectBlocked))
                .clearAndVerify(FakeHandle, clearFormatCancellation)
        }
        assertEquals(NfcClearResult.Cancelled, clearFormatResult)
        assertFalse(clearFormatConnectBlocked.events.contains("format-write"))
    }

    @Test
    fun cancellationAfterPhysicalMutationStartsReportsUncertainForWriteClearAndFormat() {
        val link = projectLink()
        val writeBlocked = BlockingNdef(IoBlock.WRITE)
        val writeCancellation = NfcIoCancellation()
        val writeResult = runCancelled(writeBlocked.entered, writeCancellation) {
            NfcTagIo(FakeTechnologies(ndef = writeBlocked)).programAndVerify(FakeHandle, link, writeCancellation)
        }
        assertEquals(NfcProgrammingResult.WriteUncertain, writeResult)
        assertTrue(writeCancellation.mutationHasStarted())

        val clearBlocked = BlockingNdef(IoBlock.WRITE, NdefLocatorCodec.message(link))
        val clearCancellation = NfcIoCancellation()
        val clearResult = runCancelled(clearBlocked.entered, clearCancellation) {
            NfcTagIo(FakeTechnologies(ndef = clearBlocked)).clearAndVerify(FakeHandle, clearCancellation)
        }
        assertEquals(NfcClearResult.WriteUncertain, clearResult)
        assertTrue(clearCancellation.mutationHasStarted())

        val formatBlocked = BlockingFormatable(IoBlock.WRITE)
        val formatCancellation = NfcIoCancellation()
        val formatResult = runCancelled(formatBlocked.entered, formatCancellation) {
            NfcTagIo(FakeTechnologies(formatable = formatBlocked))
                .clearAndVerify(FakeHandle, formatCancellation)
        }
        assertEquals(NfcClearResult.WriteUncertain, formatResult)
        assertTrue(formatCancellation.mutationHasStarted())

        val formatWriteBlocked = BlockingFormatable(IoBlock.WRITE)
        val formatWriteCancellation = NfcIoCancellation()
        val formatWriteResult = runCancelled(formatWriteBlocked.entered, formatWriteCancellation) {
            NfcTagIo(FakeTechnologies(formatable = formatWriteBlocked))
                .programAndVerify(FakeHandle, link, formatWriteCancellation)
        }
        assertEquals(NfcProgrammingResult.WriteUncertain, formatWriteResult)
        assertTrue(formatWriteCancellation.mutationHasStarted())
    }

    @Test
    fun ndefWriteReadsActualContentAfterWriteBeforeReportingVerified() {
        val old = locator(3)
        val link = projectLink()
        val events = mutableListOf<String>()
        val ndef = FakeNdef(events, message = NdefLocatorCodec.message(old))
        val io = NfcTagIo(FakeTechnologies(ndef = ndef))

        assertEquals(NfcProgrammingResult.Verified(old), io.programAndVerify(FakeHandle, link))
        assertEquals(
            listOf("ndef-connect", "ndef-read", "ndef-write", "ndef-read", "ndef-close"),
            events,
        )
        assertEquals(link, NdefLocatorCodec.parse(ndef.message))
    }

    @Test
    fun readOnlyTooSmallRemovedAndClearOutcomesAreContained() {
        val link = projectLink()
        assertEquals(
            NfcProgrammingResult.ReadOnly,
            NfcTagIo(FakeTechnologies(ndef = FakeNdef(mutableListOf(), isWritable = false)))
                .programAndVerify(FakeHandle, link),
        )
        assertEquals(
            NfcProgrammingResult.TooSmall,
            NfcTagIo(FakeTechnologies(ndef = FakeNdef(mutableListOf(), maxSize = 1)))
                .programAndVerify(FakeHandle, link),
        )
        assertEquals(
            NfcProgrammingResult.WriteUncertain,
            NfcTagIo(FakeTechnologies(ndef = FakeNdef(mutableListOf(), failWrite = true)))
                .programAndVerify(FakeHandle, link),
        )
        assertEquals(
            NfcProgrammingResult.Removed,
            NfcTagIo(FakeTechnologies(ndef = FakeNdef(mutableListOf(), failConnect = true)))
                .programAndVerify(FakeHandle, link),
        )
        val clearNdef = FakeNdef(mutableListOf(), message = NdefLocatorCodec.message(link))
        assertEquals(
            NfcClearResult.Verified(link),
            NfcTagIo(FakeTechnologies(ndef = clearNdef)).clearAndVerify(FakeHandle),
        )
        assertTrue(NdefLocatorCodec.isEmpty(clearNdef.message))
    }

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
    fun manifestOffersTheProjectHttpsRouteWithoutPackageTargetingAndRejectsNearbyShapes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val valid = Intent(Intent.ACTION_VIEW, Uri.parse(projectLink().encoded))
        val wrongHost = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(projectLink().encoded.replace("api.blocks.onemorerabbit.co.uk", "example.invalid")),
        )
        val activities = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                valid,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(valid, PackageManager.MATCH_ALL)
        }
        val wrongActivities = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                wrongHost.setPackage(context.packageName),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(
                wrongHost.setPackage(context.packageName),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }

        assertTrue(activities.any { it.activityInfo.name == "dev.blocks.android.MainActivity" })
        assertFalse(wrongActivities.any { it.activityInfo.name == "dev.blocks.android.MainActivity" })
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

    private fun projectLink() = ProjectLink.create(
        ProjectKey.fromUuid(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
    )

    private data object FakeHandle : NfcTagHandle {
        override val presentationCorrelation = NfcPresentationCorrelation.create(byteArrayOf(1))
    }

    private data object NoCorrelationHandle : NfcTagHandle

    private data class FakeHandleWithCorrelation(private val seed: Byte) : NfcTagHandle {
        constructor(seed: Int) : this(seed.toByte())
        override val presentationCorrelation = NfcPresentationCorrelation.create(byteArrayOf(seed))
    }

    private class FakeTechnologies(
        var ndef: NdefConnection? = null,
        var formatable: NdefFormatableConnection? = null,
    ) : NfcTechnologyFactory {
        override fun ndef(handle: NfcTagHandle) = ndef
        override fun formatable(handle: NfcTagHandle) = formatable
    }

    private class FakeNdef(
        private val events: MutableList<String>,
        var message: NdefMessage? = null,
        override val isWritable: Boolean = true,
        override val maxSize: Int = 4096,
        private val failWrite: Boolean = false,
        private val failConnect: Boolean = false,
    ) : NdefConnection {
        override fun connect() {
            events += "ndef-connect"
            if (failConnect) throw IOException("tag removed")
        }
        override fun readMessage(): NdefMessage? {
            events += "ndef-read"
            return message
        }
        override fun writeMessage(message: NdefMessage) {
            events += "ndef-write"
            if (failWrite) throw IOException("tag removed")
            this.message = message
        }
        override fun close() { events += "ndef-close" }
    }

    private class FakeFormatable(private val events: MutableList<String>) : NdefFormatableConnection {
        var formatted: NdefMessage? = null
        override fun connect() { events += "format-connect" }
        override fun format(message: NdefMessage) {
            events += "format-write"
            formatted = message
        }
        override fun close() { events += "format-close" }
    }

    private enum class IoBlock { CONNECT, READ, WRITE }

    private class BlockingNdef(
        private val blockedAt: IoBlock,
        var message: NdefMessage? = null,
    ) : NdefConnection {
        val events = mutableListOf<String>()
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override val isWritable = true
        override val maxSize = 4096

        override fun connect() {
            events += "ndef-connect"
            blockIf(IoBlock.CONNECT)
        }

        override fun readMessage(): NdefMessage? {
            events += "ndef-read"
            blockIf(IoBlock.READ)
            return message
        }

        override fun writeMessage(message: NdefMessage) {
            events += "ndef-write"
            this.message = message
            blockIf(IoBlock.WRITE)
        }

        override fun close() {
            events += "ndef-close"
            released.countDown()
        }

        private fun blockIf(phase: IoBlock) {
            if (blockedAt != phase) return
            entered.countDown()
            check(released.await(2, TimeUnit.SECONDS)) { "Timed out waiting for cancellation" }
        }
    }

    private class BlockingFormatable(private val blockedAt: IoBlock) : NdefFormatableConnection {
        val events = mutableListOf<String>()
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun connect() {
            events += "format-connect"
            blockIf(IoBlock.CONNECT)
        }

        override fun format(message: NdefMessage) {
            events += "format-write"
            blockIf(IoBlock.WRITE)
        }

        override fun close() {
            events += "format-close"
            released.countDown()
        }

        private fun blockIf(phase: IoBlock) {
            if (blockedAt != phase) return
            entered.countDown()
            check(released.await(2, TimeUnit.SECONDS)) { "Timed out waiting for cancellation" }
        }
    }

    private fun <T> runCancelled(
        entered: CountDownLatch,
        cancellation: NfcIoCancellation,
        operation: () -> T,
    ): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val worker = thread(start = true) {
            runCatching(operation).onSuccess(result::set).onFailure(failure::set)
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        cancellation.cancel()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("NFC I/O failed", it) }
        return requireNotNull(result.get())
    }
}
