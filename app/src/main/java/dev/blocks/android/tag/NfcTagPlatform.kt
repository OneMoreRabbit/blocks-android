package dev.blocks.android.tag

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build

internal const val BLOCKS_NFC_READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V

internal interface NfcTagHandle

internal sealed interface NfcProgrammingResult {
    data class Verified(val replacedLocator: TagLocator? = null) : NfcProgrammingResult
    data object Unsupported : NfcProgrammingResult
    data object ReadOnly : NfcProgrammingResult
    data object TooSmall : NfcProgrammingResult
    data object VerificationMismatch : NfcProgrammingResult
    data object WriteUncertain : NfcProgrammingResult
}

internal sealed interface NfcClearResult {
    data class Verified(val erasedLocator: TagLocator? = null) : NfcClearResult
    data object Unsupported : NfcClearResult
    data object ReadOnly : NfcClearResult
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
        locator: TagLocator,
    ): NfcProgrammingResult
    fun clearAndVerify(handle: NfcTagHandle): NfcClearResult
}

internal class AndroidNfcTagPlatform(private val activity: Activity) : NfcTagPlatform {
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
        locator: TagLocator,
    ): NfcProgrammingResult {
        val tag = (handle as? AndroidNfcTagHandle)?.tag ?: return NfcProgrammingResult.Unsupported
        val ndef = Ndef.get(tag) ?: return NfcProgrammingResult.Unsupported
        val message = NdefLocatorCodec.message(locator)
        return try {
            ndef.connect()
            val current = ndef.ndefMessage
            val replacedLocator = NdefLocatorCodec.parse(current)
            if (!ndef.isWritable) return NfcProgrammingResult.ReadOnly
            if (ndef.maxSize < message.toByteArray().size) return NfcProgrammingResult.TooSmall
            ndef.writeNdefMessage(message)
            if (NdefLocatorCodec.parse(ndef.ndefMessage) == locator) {
                NfcProgrammingResult.Verified(replacedLocator)
            } else {
                NfcProgrammingResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcProgrammingResult.WriteUncertain
        } finally {
            runCatching { ndef.close() }
        }
    }

    override fun clearAndVerify(handle: NfcTagHandle): NfcClearResult {
        val tag = (handle as? AndroidNfcTagHandle)?.tag ?: return NfcClearResult.Unsupported
        val ndef = Ndef.get(tag) ?: return NfcClearResult.Unsupported
        return try {
            ndef.connect()
            val erasedLocator = NdefLocatorCodec.parse(ndef.ndefMessage)
            if (!ndef.isWritable) return NfcClearResult.ReadOnly
            ndef.writeNdefMessage(NdefLocatorCodec.emptyMessage())
            if (NdefLocatorCodec.isEmpty(ndef.ndefMessage)) {
                NfcClearResult.Verified(erasedLocator)
            } else {
                NfcClearResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcClearResult.WriteUncertain
        } finally {
            runCatching { ndef.close() }
        }
    }

    private data class AndroidNfcTagHandle(val tag: Tag) : NfcTagHandle
}

internal object NdefLocatorCodec {
    fun message(locator: TagLocator) = NdefMessage(arrayOf(NdefRecord.createUri(locator.encoded)))

    fun emptyMessage() = NdefMessage(
        arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())),
    )

    fun isEmpty(message: NdefMessage?): Boolean = message?.records?.let { records ->
        records.size == 1 && records.single().tnf == NdefRecord.TNF_EMPTY &&
            records.single().type.isEmpty() && records.single().id.isEmpty() && records.single().payload.isEmpty()
    } == true

    fun parse(message: NdefMessage?): TagLocator? {
        val records = message?.records ?: return null
        if (records.size != 1) return null
        val uri = runCatching { records.single().toUri() }.getOrNull() ?: return null
        return TagLocator.parse(uri.toString())
    }

    fun fromIntent(intent: Intent?): TagLocator? {
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
    data class Valid(val locator: TagLocator) : TagLaunchIntent
    data object Invalid : TagLaunchIntent

    companion object {
        fun parse(intent: Intent?): TagLaunchIntent = when (intent?.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                NdefLocatorCodec.fromIntent(intent)?.let(::Valid) ?: Invalid
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.let(TagLocator::parse)?.let(::Valid) ?: Invalid
            }
            else -> None
        }
    }
}

internal object TagShareIntent {
    fun create(locator: TagLocator): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, locator.encoded)
    }
}
