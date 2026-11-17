package dev.blocks.android.follow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Versioned tenant database with an OS-keystore-encrypted, transactionally replaced record. */
internal class AndroidEncryptedFollowLocalStateStore(context: Context) : FollowLocalStateStore {
    private val applicationContext = context.applicationContext

    @Synchronized
    override fun load(account: FollowAccount): FollowLocalState? {
        validateAccount(account)
        val packed = helper(account.tenantId).use { helper ->
            helper.readableDatabase.rawQuery("SELECT ciphertext FROM follow_state WHERE id = 1", null).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
        } ?: return null
        return try {
            require(packed.size > 13)
            val ivLength = packed[0].toInt() and 0xff
            require(ivLength in 12..16 && packed.size > 1 + ivLength)
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, requireNotNull(existingKey(account.tenantId)) {
                    "Follow encryption key is unavailable"
                }, GCMParameterSpec(128, iv))
                updateAAD(accountAad(account))
            }
            decode(String(cipher.doFinal(packed.copyOfRange(1 + ivLength, packed.size)), Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Could not read encrypted Follow state for this account", error)
        }
    }

    @Synchronized
    override fun save(account: FollowAccount, state: FollowLocalState) {
        validateAccount(account)
        require(state.sourceId.isNotBlank() && state.nextSequence > 0)
        // A different account in the same tenant cannot overwrite an unreadable record.
        load(account)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(account.tenantId))
            updateAAD(accountAad(account))
        }
        val ciphertext = cipher.doFinal(encode(state).toByteArray(Charsets.UTF_8))
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        helper(account.tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("id", 1)
                    put("ciphertext", packed)
                }
                check(database.insertWithOnConflict("follow_state", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) == 1L) { "Could not persist encrypted Follow state" }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    @Synchronized
    override fun eraseTenant(tenantId: String) {
        require(tenantId.isNotBlank())
        val failures = mutableListOf<Throwable>()
        runCatching {
            val file = applicationContext.getDatabasePath(databaseName(tenantId))
            if (file.exists()) {
                check(SQLiteDatabase.deleteDatabase(file)) { "Could not erase Follow database" }
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(keyAlias(tenantId))) deleteEntry(keyAlias(tenantId))
            }
        }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { error ->
            failures.drop(1).forEach(error::addSuppressed)
            throw IllegalStateException("Could not erase encrypted Follow state", error)
        }
    }

    private fun helper(tenantId: String) = Helper(applicationContext, databaseName(tenantId))
    private fun databaseName(tenantId: String) = "blocks_follow_${digest(tenantId)}.db"
    private fun keyAlias(tenantId: String) = "blocks_follow_key_${digest(tenantId)}"

    private fun existingKey(tenantId: String): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(keyAlias(tenantId), null) as? SecretKey

    private fun key(tenantId: String): SecretKey {
        existingKey(tenantId)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                keyAlias(tenantId),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun accountAad(account: FollowAccount): ByteArray =
        "${account.tenantId}\u0000${account.identity.issuer}\u0000${account.identity.subject}"
            .toByteArray(Charsets.UTF_8)

    private fun validateAccount(account: FollowAccount) {
        require(account.tenantId.isNotBlank() && account.identity.issuer.isNotBlank() &&
            account.identity.subject.isNotBlank())
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun encode(state: FollowLocalState): String = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("source_id", state.sourceId)
        .put("next_sequence", state.nextSequence)
        .put("candidate", state.candidate?.let { candidate ->
            JSONObject()
                .put("source_id", candidate.sourceId)
                .put("sequence", candidate.sequence)
                .put("project_id", candidate.projectId)
                .put("kind", candidate.kind.name)
                .put("dispatch_class", candidate.dispatchClass.name)
                .put("observed_revision", candidate.observedRevision)
                .put("created_elapsed_ms", candidate.createdElapsedMs)
                .put("boot_identity", candidate.bootIdentity)
        })
        .toString()

    private fun decode(value: String): FollowLocalState {
        val json = JSONObject(value)
        require(json.getInt("schema_version") == SCHEMA_VERSION) { "Unsupported Follow state version" }
        val candidate = json.optJSONObject("candidate")?.let {
            FollowCandidate(
                sourceId = it.getString("source_id"),
                sequence = it.getLong("sequence"),
                projectId = it.getString("project_id"),
                kind = FollowKind.valueOf(it.getString("kind")),
                dispatchClass = FollowDispatchClass.valueOf(it.getString("dispatch_class")),
                observedRevision = if (it.isNull("observed_revision")) null else it.getLong("observed_revision"),
                createdElapsedMs = it.getLong("created_elapsed_ms"),
                bootIdentity = it.getString("boot_identity"),
            )
        }
        return FollowLocalState(
            sourceId = json.getString("source_id"),
            nextSequence = json.getLong("next_sequence"),
            candidate = candidate,
        ).also { state ->
            require(state.sourceId.isNotBlank() && state.nextSequence > 0)
            require(candidate == null || (candidate.sourceId == state.sourceId &&
                candidate.sequence in 1 until state.nextSequence && candidate.projectId.isNotBlank() &&
                candidate.createdElapsedMs >= 0 && candidate.bootIdentity.isNotBlank()))
        }
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE follow_state (id INTEGER PRIMARY KEY CHECK(id = 1), ciphertext BLOB NOT NULL)")
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported Follow state migration $oldVersion to $newVersion")
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
