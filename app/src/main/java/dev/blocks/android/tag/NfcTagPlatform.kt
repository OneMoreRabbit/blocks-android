package dev.blocks.android.tag

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build

internal interface NfcTagHandle

internal sealed interface NfcProgrammingResult {
    data object Verified : NfcProgrammingResult
    data class ExistingBlocksTag(val locator: TagLocator) : NfcProgrammingResult {
        override fun toString() = "ExistingBlocksTag([redacted])"
    }
    data object Unsupported : NfcProgrammingResult
    data object ReadOnly : NfcProgrammingResult
    data object TooSmall : NfcProgrammingResult
    data object ForeignContent : NfcProgrammingResult
    data object VerificationMismatch : NfcProgrammingResult
    data object WriteUncertain : NfcProgrammingResult
}

internal interface NfcTagPlatform {
    val isAvailable: Boolean
    val isEnabled: Boolean
    fun startReader(onTag: (NfcTagHandle) -> Unit)
    fun stopReader()
    fun programAndVerify(
        handle: NfcTagHandle,
        locator: TagLocator,
        authorizedReplacementFingerprint: String?,
    ): NfcProgrammingResult
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
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun stopReader() {
        runCatching { adapter?.disableReaderMode(activity) }
    }

    override fun programAndVerify(
        handle: NfcTagHandle,
        locator: TagLocator,
        authorizedReplacementFingerprint: String?,
    ): NfcProgrammingResult {
        val tag = (handle as? AndroidNfcTagHandle)?.tag ?: return NfcProgrammingResult.Unsupported
        val ndef = Ndef.get(tag) ?: return NfcProgrammingResult.Unsupported
        val message = NdefLocatorCodec.message(locator)
        return try {
            ndef.connect()
            val current = ndef.ndefMessage
            if (current != null && current.records.isNotEmpty()) {
                val existing = NdefLocatorCodec.parse(current)
                    ?: return NfcProgrammingResult.ForeignContent
                if (existing == locator) return NfcProgrammingResult.Verified
                if (existing.token.fingerprint() != authorizedReplacementFingerprint) {
                    return NfcProgrammingResult.ExistingBlocksTag(existing)
                }
            }
            if (!ndef.isWritable) return NfcProgrammingResult.ReadOnly
            if (ndef.maxSize < message.toByteArray().size) return NfcProgrammingResult.TooSmall
            ndef.writeNdefMessage(message)
            if (NdefLocatorCodec.parse(ndef.ndefMessage) == locator) {
                NfcProgrammingResult.Verified
            } else {
                NfcProgrammingResult.VerificationMismatch
            }
        } catch (_: Exception) {
            NfcProgrammingResult.WriteUncertain
        } finally {
            runCatching { ndef.close() }
        }
    }

    private data class AndroidNfcTagHandle(val tag: Tag) : NfcTagHandle
}

internal object NdefLocatorCodec {
    fun message(locator: TagLocator) = NdefMessage(arrayOf(NdefRecord.createUri(locator.encoded)))

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
