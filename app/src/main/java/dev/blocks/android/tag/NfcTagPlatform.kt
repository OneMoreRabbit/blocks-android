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

internal const val BLOCKS_NFC_READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V

internal interface NfcTagHandle

internal sealed interface NfcProgrammingResult {
    data class Verified(val replacedAddress: BlocksNavigationAddress? = null) : NfcProgrammingResult
    data object AwaitingReadBack : NfcProgrammingResult
    data object Unsupported : NfcProgrammingResult
    data object ReadOnly : NfcProgrammingResult
    data object TooSmall : NfcProgrammingResult
    data object Removed : NfcProgrammingResult
    data object VerificationMismatch : NfcProgrammingResult
    data object WriteUncertain : NfcProgrammingResult
}

internal sealed interface NfcClearResult {
    data class Verified(val erasedAddress: BlocksNavigationAddress? = null) : NfcClearResult
    data object AwaitingReadBack : NfcClearResult
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
    ): NfcProgrammingResult
    fun verifyWritten(handle: NfcTagHandle, address: BlocksNavigationAddress): NfcProgrammingResult
    fun clearAndVerify(handle: NfcTagHandle): NfcClearResult
    fun verifyCleared(handle: NfcTagHandle): NfcClearResult
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
    ): NfcProgrammingResult = io.programAndVerify(handle, address)

    override fun verifyWritten(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
    ): NfcProgrammingResult = io.verifyWritten(handle, address)

    override fun clearAndVerify(handle: NfcTagHandle): NfcClearResult = io.clearAndVerify(handle)

    override fun verifyCleared(handle: NfcTagHandle): NfcClearResult = io.verifyCleared(handle)
}

private data class AndroidNfcTagHandle(val tag: Tag) : NfcTagHandle

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
    ): NfcProgrammingResult {
        val message = NdefLocatorCodec.message(address)
        technologies.ndef(handle)?.let { ndef ->
            var writeStarted = false
            return try {
                ndef.connect()
                val replacedAddress = NdefLocatorCodec.parse(ndef.readMessage())
                if (!ndef.isWritable) return NfcProgrammingResult.ReadOnly
                if (ndef.maxSize < message.toByteArray().size) return NfcProgrammingResult.TooSmall
                writeStarted = true
                ndef.writeMessage(message)
                if (NdefLocatorCodec.parse(ndef.readMessage()) == address) {
                    NfcProgrammingResult.Verified(replacedAddress)
                } else {
                    NfcProgrammingResult.VerificationMismatch
                }
            } catch (_: Exception) {
                if (writeStarted) NfcProgrammingResult.WriteUncertain else NfcProgrammingResult.Removed
            } finally {
                runCatching(ndef::close)
            }
        }
        val formatable = technologies.formatable(handle) ?: return NfcProgrammingResult.Unsupported
        var formatStarted = false
        return try {
            formatable.connect()
            formatStarted = true
            formatable.format(message)
            NfcProgrammingResult.AwaitingReadBack
        } catch (_: Exception) {
            if (formatStarted) NfcProgrammingResult.WriteUncertain else NfcProgrammingResult.Removed
        } finally {
            runCatching(formatable::close)
        }
    }

    fun verifyWritten(
        handle: NfcTagHandle,
        address: BlocksNavigationAddress,
    ): NfcProgrammingResult {
        val ndef = technologies.ndef(handle) ?: return NfcProgrammingResult.WriteUncertain
        return try {
            ndef.connect()
            if (NdefLocatorCodec.parse(ndef.readMessage()) == address) {
                NfcProgrammingResult.Verified()
            } else {
                NfcProgrammingResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcProgrammingResult.WriteUncertain
        } finally {
            runCatching(ndef::close)
        }
    }

    fun clearAndVerify(handle: NfcTagHandle): NfcClearResult {
        technologies.ndef(handle)?.let { ndef ->
            var writeStarted = false
            return try {
                ndef.connect()
                val erasedAddress = NdefLocatorCodec.parse(ndef.readMessage())
                if (!ndef.isWritable) return NfcClearResult.ReadOnly
                writeStarted = true
                ndef.writeMessage(NdefLocatorCodec.emptyMessage())
                if (NdefLocatorCodec.isEmpty(ndef.readMessage())) {
                    NfcClearResult.Verified(erasedAddress)
                } else {
                    NfcClearResult.VerificationMismatch
                }
            } catch (_: Exception) {
                if (writeStarted) NfcClearResult.WriteUncertain else NfcClearResult.Removed
            } finally {
                runCatching(ndef::close)
            }
        }
        val formatable = technologies.formatable(handle) ?: return NfcClearResult.Unsupported
        var formatStarted = false
        return try {
            formatable.connect()
            formatStarted = true
            formatable.format(NdefLocatorCodec.emptyMessage())
            NfcClearResult.AwaitingReadBack
        } catch (_: Exception) {
            if (formatStarted) NfcClearResult.WriteUncertain else NfcClearResult.Removed
        } finally {
            runCatching(formatable::close)
        }
    }

    fun verifyCleared(handle: NfcTagHandle): NfcClearResult {
        val ndef = technologies.ndef(handle) ?: return NfcClearResult.WriteUncertain
        return try {
            ndef.connect()
            if (NdefLocatorCodec.isEmpty(ndef.readMessage())) {
                NfcClearResult.Verified()
            } else {
                NfcClearResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcClearResult.WriteUncertain
        } finally {
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
