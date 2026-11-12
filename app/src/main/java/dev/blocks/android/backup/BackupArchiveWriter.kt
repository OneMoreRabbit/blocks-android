package dev.blocks.android.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Verifies the document provider's returned bytes before the UI may report success. */
internal object BackupArchiveWriter {
    fun writeAndVerify(
        archive: ByteArray,
        passphrase: CharArray,
        expected: PortableBackupPayload,
        openWrite: () -> OutputStream?,
        openRead: () -> InputStream?,
    ) {
        requireNotNull(openWrite()) { "The selected document could not be opened" }.use { stream ->
            stream.write(archive)
            stream.flush()
        }
        val readback = requireNotNull(openRead()) { "The selected document could not be verified" }.use {
            readBounded(it, archive.size)
        }
        try {
            check(readback.contentEquals(archive)) { "Written backup differs from the captured data" }
            check(PortableBackupCodec.open(readback, passphrase) == expected) {
                "Written backup did not reconstruct the captured data"
            }
        } finally {
            readback.fill(0)
        }
    }

    private fun readBounded(stream: InputStream, expectedSize: Int): ByteArray {
        val out = ByteArrayOutputStream(expectedSize.coerceAtMost(64 * 1024))
        val chunk = ByteArray(8192)
        var count = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            count += read
            check(count <= expectedSize) { "The document provider returned extra data" }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}
