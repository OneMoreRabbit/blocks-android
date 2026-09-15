package dev.blocks.android.tag

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import java.security.MessageDigest

internal const val BLOCKS_NFC_READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V

/**
 * Short-lived correlation for one physical tag presentation. It is never persisted or logged.
 * A missing or changing Android tag identifier deliberately makes formatted-tag verification
 * uncertain instead of allowing content on another copy to prove the original mutation.
 */
internal class NfcPresentationCorrelation private constructor(private val digest: ByteArray) {
    override fun equals(other: Any?) = other is NfcPresentationCorrelation &&
        MessageDigest.isEqual(digest, other.digest)

    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "[redacted NFC presentation]"

    companion object {
        fun create(identifier: ByteArray): NfcPresentationCorrelation? {
            if (identifier.isEmpty()) return null
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update(identifier.copyOf())
            return NfcPresentationCorrelation(hash.digest())
        }
    }
}

internal interface NfcTagHandle {
    val presentationCorrelation: NfcPresentationCorrelation?
        get() = null
}

/** Cancellation shared by Activity lifecycle ownership and the blocking NFC technology calls. */
internal class NfcIoCancellation(private val mutationStillAllowed: () -> Boolean = { true }) {
    private val lock = Any()
    private var cancelled = false
    private var mutationStarted = false
    private var closeActiveConnection: (() -> Unit)? = null

    fun isCancelled(): Boolean = synchronized(lock) { cancelled }

    fun registerConnection(close: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) false else {
            closeActiveConnection = close
            true
        }
    }

    fun clearConnection() = synchronized(lock) {
        closeActiveConnection = null
    }

    /** Atomically orders cancellation before, or mutation before, the first physical write. */
    fun beginMutation(): Boolean {
        val scopeAllowsMutation = runCatching(mutationStillAllowed).getOrDefault(false)
        return synchronized(lock) {
            if (cancelled || !scopeAllowsMutation) false else {
                mutationStarted = true
                true
            }
        }
    }

    fun cancel() {
        val close = synchronized(lock) {
            cancelled = true
            closeActiveConnection
        }
        runCatching { close?.invoke() }
    }

    fun mutationHasStarted(): Boolean = synchronized(lock) { mutationStarted }
}

internal sealed interface NfcProgrammingResult {
    data class Verified(val replacedAddress: BlocksNavigationAddress? = null) : NfcProgrammingResult
    data class AwaitingReadBack(val correlation: NfcPresentationCorrelation) : NfcProgrammingResult
    data object Cancelled : NfcProgrammingResult
    data object Unsupported : NfcProgrammingResult
    data object ReadOnly : NfcProgrammingResult
    data object TooSmall : NfcProgrammingResult
    data object Removed : NfcProgrammingResult
    data object VerificationMismatch : NfcProgrammingResult
    data object WriteUncertain : NfcProgrammingResult
}

internal sealed interface NfcClearResult {
    data class Verified(val erasedAddress: BlocksNavigationAddress? = null) : NfcClearResult
    data class AwaitingReadBack(val correlation: NfcPresentationCorrelation) : NfcClearResult
    data object Cancelled : NfcClearResult
    data object Unsupported : NfcClearResult
    data object ReadOnly : NfcClearResult
    data object Removed : NfcClearResult
    data object VerificationMismatch : NfcClearResult
    data object WriteUncertain : NfcClearResult
}

internal interface NfcTagPlatform {
    val isAvailable: Boolean
    val isEnabled: Boolean
    fun startReader(onTag: (NfcTagHandle) -> Unit)
    fun stopReader()
    fun programAndVerify(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcProgrammingResult
    fun verifyWritten(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcProgrammingResult
    fun clearAndVerify(
        handle: NfcTagHandle,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcClearResult
    fun verifyCleared(
        handle: NfcTagHandle,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcClearResult
}

internal class AndroidNfcTagPlatform(private val activity: Activity) : NfcTagPlatform {
    private val io = NfcTagIo(AndroidNfcTechnologyFactory)
    private val adapter: NfcAdapter?
        get() = NfcAdapter.getDefaultAdapter(activity)

    override val isAvailable: Boolean
        get() = adapter != null

    override val isEnabled: Boolean
        get() = adapter?.isEnabled == true

    override fun startReader(onTag: (NfcTagHandle) -> Unit) {
        val current = adapter ?: return
        if (!current.isEnabled) return
        current.enableReaderMode(
            activity,
            { tag -> onTag(AndroidNfcTagHandle(tag)) },
            BLOCKS_NFC_READER_FLAGS,
            null,
        )
    }

    override fun stopReader() {
        runCatching { adapter?.disableReaderMode(activity) }
    }

    override fun programAndVerify(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        cancellation: NfcIoCancellation,
    ): NfcProgrammingResult = io.programAndVerify(handle, address, cancellation)

    override fun verifyWritten(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation,
    ): NfcProgrammingResult = io.verifyWritten(handle, address, expectedCorrelation, cancellation)

    override fun clearAndVerify(
        handle: NfcTagHandle,
        cancellation: NfcIoCancellation,
    ): NfcClearResult = io.clearAndVerify(handle, cancellation)

    override fun verifyCleared(
        handle: NfcTagHandle,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation,
    ): NfcClearResult = io.verifyCleared(handle, expectedCorrelation, cancellation)
}

private data class AndroidNfcTagHandle(val tag: Tag) : NfcTagHandle {
    override val presentationCorrelation = NfcPresentationCorrelation.create(tag.id)
}

internal interface NdefConnection {
    val isWritable: Boolean
    val maxSize: Int
    fun connect()
    fun readMessage(): NdefMessage?
    fun writeMessage(message: NdefMessage)
    fun close()
}

internal interface NdefFormatableConnection {
    fun connect()
    fun format(message: NdefMessage)
    fun close()
}

internal interface NfcTechnologyFactory {
    fun ndef(handle: NfcTagHandle): NdefConnection?
    fun formatable(handle: NfcTagHandle): NdefFormatableConnection?
}

private object AndroidNfcTechnologyFactory : NfcTechnologyFactory {
    override fun ndef(handle: NfcTagHandle): NdefConnection? {
        val tag = (handle as? AndroidNfcTagHandle)?.tag ?: return null
        return Ndef.get(tag)?.let(::AndroidNdefConnection)
    }

    override fun formatable(handle: NfcTagHandle): NdefFormatableConnection? {
        val tag = (handle as? AndroidNfcTagHandle)?.tag ?: return null
        return NdefFormatable.get(tag)?.let(::AndroidNdefFormatableConnection)
    }
}

private class AndroidNdefConnection(private val ndef: Ndef) : NdefConnection {
    override val isWritable: Boolean get() = ndef.isWritable
    override val maxSize: Int get() = ndef.maxSize
    override fun connect() = ndef.connect()
    override fun readMessage(): NdefMessage? = ndef.ndefMessage
    override fun writeMessage(message: NdefMessage) = ndef.writeNdefMessage(message)
    override fun close() = ndef.close()
}

private class AndroidNdefFormatableConnection(private val formatable: NdefFormatable) : NdefFormatableConnection {
    override fun connect() = formatable.connect()
    override fun format(message: NdefMessage) = formatable.format(message)
    override fun close() = formatable.close()
}

/** Physical I/O state machine, separated from Activity reader-mode ownership for deterministic tests. */
internal class NfcTagIo(private val technologies: NfcTechnologyFactory) {
    fun programAndVerify(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcProgrammingResult {
        if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
        val message = NdefLocatorCodec.message(address)
        technologies.ndef(handle)?.let { ndef ->
            var writeStarted = false
            return try {
                if (!cancellation.registerConnection(ndef::close)) return NfcProgrammingResult.Cancelled
                ndef.connect()
                if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
                val replacedAddress = NdefLocatorCodec.parse(ndef.readMessage())
                if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
                if (!ndef.isWritable) return NfcProgrammingResult.ReadOnly
                if (ndef.maxSize < message.toByteArray().size) return NfcProgrammingResult.TooSmall
                if (!cancellation.beginMutation()) return NfcProgrammingResult.Cancelled
                writeStarted = true
                ndef.writeMessage(message)
                if (cancellation.isCancelled()) return NfcProgrammingResult.WriteUncertain
                if (NdefLocatorCodec.parse(ndef.readMessage()) == address) {
                    NfcProgrammingResult.Verified(replacedAddress)
                } else {
                    NfcProgrammingResult.VerificationMismatch
                }
            } catch (_: Exception) {
                if (writeStarted) NfcProgrammingResult.WriteUncertain else NfcProgrammingResult.Removed
            } finally {
                cancellation.clearConnection()
                runCatching(ndef::close)
            }
        }
        val formatable = technologies.formatable(handle) ?: return NfcProgrammingResult.Unsupported
        var formatStarted = false
        return try {
            if (!cancellation.registerConnection(formatable::close)) return NfcProgrammingResult.Cancelled
            formatable.connect()
            if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
            if (!cancellation.beginMutation()) return NfcProgrammingResult.Cancelled
            formatStarted = true
            formatable.format(message)
            if (cancellation.isCancelled()) return NfcProgrammingResult.WriteUncertain
            handle.presentationCorrelation?.let(NfcProgrammingResult::AwaitingReadBack)
                ?: NfcProgrammingResult.WriteUncertain
        } catch (_: Exception) {
            if (formatStarted) NfcProgrammingResult.WriteUncertain else NfcProgrammingResult.Removed
        } finally {
            cancellation.clearConnection()
            runCatching(formatable::close)
        }
    }

    fun verifyWritten(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcProgrammingResult {
        if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
        if (handle.presentationCorrelation != expectedCorrelation) {
            return NfcProgrammingResult.AwaitingReadBack(expectedCorrelation)
        }
        val ndef = technologies.ndef(handle) ?: return NfcProgrammingResult.WriteUncertain
        return try {
            if (!cancellation.registerConnection(ndef::close)) return NfcProgrammingResult.Cancelled
            ndef.connect()
            if (cancellation.isCancelled()) return NfcProgrammingResult.Cancelled
            if (NdefLocatorCodec.parse(ndef.readMessage()) == address) {
                NfcProgrammingResult.Verified()
            } else {
                NfcProgrammingResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcProgrammingResult.WriteUncertain
        } finally {
            cancellation.clearConnection()
            runCatching(ndef::close)
        }
    }

    fun clearAndVerify(
        handle: NfcTagHandle,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcClearResult {
        if (cancellation.isCancelled()) return NfcClearResult.Cancelled
        technologies.ndef(handle)?.let { ndef ->
            var writeStarted = false
            return try {
                if (!cancellation.registerConnection(ndef::close)) return NfcClearResult.Cancelled
                ndef.connect()
                if (cancellation.isCancelled()) return NfcClearResult.Cancelled
                val erasedAddress = NdefLocatorCodec.parse(ndef.readMessage())
                if (cancellation.isCancelled()) return NfcClearResult.Cancelled
                if (!ndef.isWritable) return NfcClearResult.ReadOnly
                if (!cancellation.beginMutation()) return NfcClearResult.Cancelled
                writeStarted = true
                ndef.writeMessage(NdefLocatorCodec.emptyMessage())
                if (cancellation.isCancelled()) return NfcClearResult.WriteUncertain
                if (NdefLocatorCodec.isEmpty(ndef.readMessage())) {
                    NfcClearResult.Verified(erasedAddress)
                } else {
                    NfcClearResult.VerificationMismatch
                }
            } catch (_: Exception) {
                if (writeStarted) NfcClearResult.WriteUncertain else NfcClearResult.Removed
            } finally {
                cancellation.clearConnection()
                runCatching(ndef::close)
            }
        }
        val formatable = technologies.formatable(handle) ?: return NfcClearResult.Unsupported
        var formatStarted = false
        return try {
            if (!cancellation.registerConnection(formatable::close)) return NfcClearResult.Cancelled
            formatable.connect()
            if (cancellation.isCancelled()) return NfcClearResult.Cancelled
            if (!cancellation.beginMutation()) return NfcClearResult.Cancelled
            formatStarted = true
            formatable.format(NdefLocatorCodec.emptyMessage())
            if (cancellation.isCancelled()) return NfcClearResult.WriteUncertain
            handle.presentationCorrelation?.let(NfcClearResult::AwaitingReadBack)
                ?: NfcClearResult.WriteUncertain
        } catch (_: Exception) {
            if (formatStarted) NfcClearResult.WriteUncertain else NfcClearResult.Removed
        } finally {
            cancellation.clearConnection()
            runCatching(formatable::close)
        }
    }

    fun verifyCleared(
        handle: NfcTagHandle,
        expectedCorrelation: NfcPresentationCorrelation,
        cancellation: NfcIoCancellation = NfcIoCancellation(),
    ): NfcClearResult {
        if (cancellation.isCancelled()) return NfcClearResult.Cancelled
        if (handle.presentationCorrelation != expectedCorrelation) {
            return NfcClearResult.AwaitingReadBack(expectedCorrelation)
        }
        val ndef = technologies.ndef(handle) ?: return NfcClearResult.WriteUncertain
        return try {
            if (!cancellation.registerConnection(ndef::close)) return NfcClearResult.Cancelled
            ndef.connect()
            if (cancellation.isCancelled()) return NfcClearResult.Cancelled
            if (NdefLocatorCodec.isEmpty(ndef.readMessage())) {
                NfcClearResult.Verified()
            } else {
                NfcClearResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcClearResult.WriteUncertain
        } finally {
            cancellation.clearConnection()
            runCatching(ndef::close)
        }
    }
}

internal object NdefLocatorCodec {
    fun message(address: BlocksNavigationAddress) = NdefMessage(arrayOf(NdefRecord.createUri(address.encoded)))

    fun emptyMessage() = NdefMessage(
        arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())),
    )

    fun isEmpty(message: NdefMessage?): Boolean = message?.records?.let { records ->
        records.size == 1 && records.single().tnf == NdefRecord.TNF_EMPTY &&
            records.single().type.isEmpty() && records.single().id.isEmpty() && records.single().payload.isEmpty()
    } == true

    fun parse(message: NdefMessage?): BlocksNavigationAddress? {
        val records = message?.records ?: return null
        if (records.size != 1) return null
        val uri = runCatching { records.single().toUri() }.getOrNull() ?: return null
        return BlocksNavigationAddressParser.parse(uri.toString())
    }

    fun fromIntent(intent: Intent?): BlocksNavigationAddress? {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        val messages = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                ?.mapNotNull { it as? NdefMessage }
                ?.toTypedArray()
        }
        if (messages?.size != 1) return null
        val locator = parse(messages.single()) ?: return null
        return locator.takeIf { intent.dataString == it.encoded }
    }
}

internal sealed interface TagLaunchIntent {
    data object None : TagLaunchIntent
    data class Valid(val address: BlocksNavigationAddress) : TagLaunchIntent
    data object Invalid : TagLaunchIntent

    companion object {
        fun parse(intent: Intent?): TagLaunchIntent = when (intent?.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                NdefLocatorCodec.fromIntent(intent)?.let(::Valid) ?: Invalid
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.let(BlocksNavigationAddressParser::parse)?.let(::Valid) ?: Invalid
            }
            else -> None
        }
    }
}

internal object TagShareIntent {
    fun create(address: BlocksNavigationAddress): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, address.encoded)
    }
}
