package dev.blocks.android.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncodedSyncRows(
    val schemaVersion: Int,
    val projectsLoaded: Boolean,
    val reentryLoaded: Boolean,
    val changeCursor: String?,
    val notice: String?,
    val projects: List<String>,
    val reentry: String?,
    val commands: List<String>,
    val retiredIdempotencyKeys: List<String>,
)

internal class SyncStateRowCodec(private val gson: Gson = Gson()) {
    fun encode(state: TenantSyncState): EncodedSyncRows {
        validateProjects(state.projects)
        validateCommands(state.commands)
        val retiredKeys = state.retiredIdempotencyKeys.distinct()
        require(state.commands.none { it.idempotencyKey in retiredKeys }) {
            "A queued idempotency key is already retired"
        }
        return EncodedSyncRows(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            projectsLoaded = state.projectsLoaded,
            reentryLoaded = state.reentryLoaded,
            changeCursor = state.changeCursor,
            notice = state.notice,
            projects = state.projects.map(gson::toJson),
            reentry = state.reentry?.let(gson::toJson),
            commands = state.commands.map(gson::toJson),
            retiredIdempotencyKeys = retiredKeys,
        )
    }

    fun decode(rows: EncodedSyncRows): TenantSyncState {
        require(rows.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported local sync row schema ${rows.schemaVersion}"
        }
        val projects = rows.projects.map { gson.fromJson(it, Project::class.java) }
        validateProjects(projects)
        val commands = rows.commands.map { gson.fromJson(it, QueuedCommand::class.java) }
        validateCommands(commands)
        val retiredKeys = rows.retiredIdempotencyKeys.distinct()
        require(commands.none { it.idempotencyKey in retiredKeys }) {
            "A queued idempotency key is already retired"
        }
        return TenantSyncState(
            projects = projects,
            projectsLoaded = rows.projectsLoaded,
            reentry = rows.reentry?.let { gson.fromJson(it, Reentry::class.java) },
            reentryLoaded = rows.reentryLoaded,
            commands = commands,
            changeCursor = rows.changeCursor,
            retiredIdempotencyKeys = retiredKeys,
            notice = rows.notice,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Decoder used only to import the pre-database preference record from the unaccepted PR build. */
internal class LegacySyncStateJsonCodec(private val gson: Gson = Gson()) {
    fun decode(value: String): TenantSyncState {
        val persisted = gson.fromJson(value, PersistedSyncState::class.java)
        val version = persisted.schemaVersion ?: 0
        require(version in 0..LEGACY_SCHEMA_VERSION) { "Unsupported legacy sync schema $version" }
        if (version == 0) {
            require(persisted.commands.isNullOrEmpty()) {
                "Unversioned local state cannot contain replayable commands"
            }
        }
        val commands = persisted.commands.orEmpty()
        validateCommands(commands)
        return TenantSyncState(
            projects = persisted.projects.orEmpty(),
            projectsLoaded = persisted.projectsLoaded ?: false,
            reentry = persisted.reentry,
            reentryLoaded = persisted.reentryLoaded ?: false,
            commands = commands,
            changeCursor = persisted.changeCursor,
            retiredIdempotencyKeys = persisted.retiredIdempotencyKeys.orEmpty().distinct(),
            notice = persisted.notice,
        )
    }

    private data class PersistedSyncState(
        @SerializedName("schema_version") val schemaVersion: Int? = null,
        val projects: List<Project>? = null,
        val projectsLoaded: Boolean? = null,
        val reentry: Reentry? = null,
        val reentryLoaded: Boolean? = null,
        val commands: List<QueuedCommand>? = null,
        val changeCursor: String? = null,
        val retiredIdempotencyKeys: List<String>? = null,
        val notice: String? = null,
    )

    private companion object {
        const val LEGACY_SCHEMA_VERSION = 1
    }
}

private fun validateCommands(commands: List<QueuedCommand>) {
    commands.forEach { command ->
        val operation = requireNotNull(command.operation) { "Queued command operation is missing" }
        requireNotNull(command.status) { "Queued command status is missing" }
        require(command.targetId.isNotBlank()) { "Queued command target is missing" }
        require(command.projectId.isNotBlank()) { "Queued command project is missing" }
        require(command.idempotencyKey.isNotBlank()) { "Queued command idempotency key is missing" }
        when (operation) {
            OfflineOperation.PICKUP -> requireNotNull(command.pickup) {
                "Queued pickup payload is missing"
            }
            OfflineOperation.PARK,
            OfflineOperation.PARK_AND_CLOSE,
            OfflineOperation.CLOSE_EXISTING_CONTINUATION,
            -> requireNotNull(command.parking) { "Queued parking payload is missing" }
            OfflineOperation.CLOSE_WITH_OUTCOME -> requireNotNull(command.outcomeTier) {
                "Queued outcome payload is missing"
            }
        }
        if (operation != OfflineOperation.PICKUP) {
            val ifMatch = requireNotNull(command.ifMatch) { "Queued command If-Match is missing" }
            val session = requireNotNull(command.workSession) { "Queued session snapshot is missing" }
            require(ifMatch == session.rowVersion) { "Queued command If-Match does not match its snapshot" }
            require(session.id == command.targetId) { "Queued command target does not match its snapshot" }
            require(session.projectId == command.projectId) {
                "Queued command project does not match its snapshot"
            }
        }
    }
    require(commands.map { it.idempotencyKey }.distinct().size == commands.size) {
        "Queued command idempotency keys are not unique"
    }
}

private fun validateProjects(projects: List<Project>) {
    require(projects.map { it.id }.distinct().size == projects.size) {
        "Cached project identifiers are not unique"
    }
}

/**
 * One SQLite database per tenant. Every logical value is an independently authenticated
 * ciphertext; only table structure, row order, and non-secret metadata names remain visible.
 * A tenant-specific AES key lives in Android Keystore, and each state replacement is one
 * SQLite transaction so a queue/cursor/cache snapshot cannot be partially committed.
 */
class AndroidEncryptedSyncStateStore(context: Context) : SyncStateStore {
    private val applicationContext = context.applicationContext
    private val rowCodec = SyncStateRowCodec()
    private val legacyCodec = LegacySyncStateJsonCodec()

    @Synchronized
    override fun loadSyncState(tenantId: String): TenantSyncState {
        if (!databaseFile(tenantId).exists()) return migrateLegacyState(tenantId)
        val secretKey = requireNotNull(existingKey(databaseKeyAlias(tenantId))) {
            "Encrypted sync database key is unavailable"
        }
        val helper = TenantDatabaseHelper(applicationContext, databaseName(tenantId))
        return try {
            val database = helper.readableDatabase
            database.beginTransaction()
            try {
                val rows = EncodedSyncRows(
                    schemaVersion = readMetadata(database, tenantId, secretKey, META_SCHEMA_VERSION).toInt(),
                    projectsLoaded = readMetadata(
                        database,
                        tenantId,
                        secretKey,
                        META_PROJECTS_LOADED,
                    ).toBooleanStrict(),
                    reentryLoaded = readMetadata(
                        database,
                        tenantId,
                        secretKey,
                        META_REENTRY_LOADED,
                    ).toBooleanStrict(),
                    changeCursor = readOptionalMetadata(database, tenantId, secretKey, META_CHANGE_CURSOR),
                    notice = readOptionalMetadata(database, tenantId, secretKey, META_NOTICE),
                    projects = readRows(database, tenantId, secretKey, TABLE_PROJECTS),
                    reentry = readRows(database, tenantId, secretKey, TABLE_REENTRY).singleOrNull(),
                    commands = readRows(database, tenantId, secretKey, TABLE_COMMANDS),
                    retiredIdempotencyKeys = readRows(database, tenantId, secretKey, TABLE_RETIRED_KEYS),
                )
                rowCodec.decode(rows).also { database.setTransactionSuccessful() }
            } finally {
                database.endTransaction()
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not read encrypted tenant sync database", error)
        } finally {
            helper.close()
        }
    }

    @Synchronized
    override fun saveSyncState(tenantId: String, state: TenantSyncState) {
        if (state == TenantSyncState()) {
            clearSyncState(tenantId)
            return
        }
        val rows = rowCodec.encode(state)
        val databaseExisted = databaseFile(tenantId).exists()
        val secretKey = existingKey(databaseKeyAlias(tenantId)) ?: run {
            check(!databaseExisted) { "Encrypted sync database key is unavailable" }
            createKey(databaseKeyAlias(tenantId))
        }
        val helper = TenantDatabaseHelper(applicationContext, databaseName(tenantId))
        try {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                clearTables(database)
                writeMetadata(database, tenantId, secretKey, META_SCHEMA_VERSION, rows.schemaVersion.toString())
                writeMetadata(database, tenantId, secretKey, META_PROJECTS_LOADED, rows.projectsLoaded.toString())
                writeMetadata(database, tenantId, secretKey, META_REENTRY_LOADED, rows.reentryLoaded.toString())
                rows.changeCursor?.let {
                    writeMetadata(database, tenantId, secretKey, META_CHANGE_CURSOR, it)
                }
                rows.notice?.let { writeMetadata(database, tenantId, secretKey, META_NOTICE, it) }
                writeRows(database, tenantId, secretKey, TABLE_PROJECTS, rows.projects)
                writeRows(database, tenantId, secretKey, TABLE_REENTRY, listOfNotNull(rows.reentry))
                writeRows(database, tenantId, secretKey, TABLE_COMMANDS, rows.commands)
                writeRows(database, tenantId, secretKey, TABLE_RETIRED_KEYS, rows.retiredIdempotencyKeys)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (error: Exception) {
            if (!databaseExisted) {
                runCatching { applicationContext.deleteDatabase(databaseName(tenantId)) }
                runCatching { deleteKey(databaseKeyAlias(tenantId)) }
            }
            throw IllegalStateException("Could not transactionally persist encrypted tenant sync state", error)
        } finally {
            helper.close()
        }
    }

    @Synchronized
    override fun clearSyncState(tenantId: String) {
        val failures = mutableListOf<Throwable>()
        runCatching {
            val file = databaseFile(tenantId)
            if (file.exists()) check(applicationContext.deleteDatabase(databaseName(tenantId))) {
                "Could not delete tenant sync database"
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching { clearLegacyState(tenantId) }.exceptionOrNull()?.let(failures::add)
        runCatching { deleteKey(databaseKeyAlias(tenantId)) }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not clear encrypted tenant sync database", failure)
        }
    }

    private fun migrateLegacyState(tenantId: String): TenantSyncState {
        val encoded = legacyPreferences(tenantId).getString(LEGACY_CIPHERTEXT, null)
            ?: return TenantSyncState()
        val secretKey = requireNotNull(existingKey(legacyKeyAlias(tenantId))) {
            "Legacy encrypted sync state key is unavailable"
        }
        val state = try {
            legacyCodec.decode(
                decrypt(
                    secretKey,
                    legacyAad(tenantId),
                    Base64.decode(encoded, Base64.NO_WRAP),
                ),
            )
        } catch (error: Exception) {
            throw IllegalStateException("Could not migrate legacy encrypted sync state", error)
        }
        saveSyncState(tenantId, state)
        clearLegacyState(tenantId)
        return state
    }

    private fun writeMetadata(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        name: String,
        value: String,
    ) {
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_CIPHERTEXT, encrypt(secretKey, rowAad(tenantId, TABLE_METADATA, name), value))
        }
        check(database.insertOrThrow(TABLE_METADATA, null, values) != -1L)
    }

    private fun writeRows(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        table: String,
        values: List<String>,
    ) {
        values.forEachIndexed { position, value ->
            val row = ContentValues().apply {
                put(COLUMN_POSITION, position)
                put(COLUMN_CIPHERTEXT, encrypt(secretKey, rowAad(tenantId, table, position.toString()), value))
            }
            check(database.insertOrThrow(table, null, row) != -1L)
        }
    }

    private fun readMetadata(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        name: String,
    ): String = requireNotNull(readOptionalMetadata(database, tenantId, secretKey, name)) {
        "Encrypted sync database metadata $name is missing"
    }

    private fun readOptionalMetadata(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        name: String,
    ): String? = database.query(
        TABLE_METADATA,
        arrayOf(COLUMN_CIPHERTEXT),
        "$COLUMN_NAME = ?",
        arrayOf(name),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            decrypt(secretKey, rowAad(tenantId, TABLE_METADATA, name), cursor.getBlob(0))
        }
    }

    private fun readRows(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        table: String,
    ): List<String> = database.query(
        table,
        arrayOf(COLUMN_POSITION, COLUMN_CIPHERTEXT),
        null,
        null,
        null,
        null,
        "$COLUMN_POSITION ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val position = cursor.getInt(0)
                add(
                    decrypt(
                        secretKey,
                        rowAad(tenantId, table, position.toString()),
                        cursor.getBlob(1),
                    ),
                )
            }
        }
    }

    private fun clearTables(database: SQLiteDatabase) {
        TABLES.forEach { table -> database.delete(table, null, null) }
    }

    private fun encrypt(secretKey: SecretKey, aad: ByteArray, value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
            updateAAD(aad)
        }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(secretKey: SecretKey, aad: ByteArray, packed: ByteArray): String {
        require(packed.isNotEmpty()) { "Encrypted database row is empty" }
        val ivLength = packed.first().toInt() and 0xff
        require(ivLength > 0 && packed.size > 1 + ivLength) { "Encrypted database row is malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, packed.copyOfRange(1, 1 + ivLength)))
            updateAAD(aad)
        }
        return String(cipher.doFinal(packed.copyOfRange(1 + ivLength, packed.size)), Charsets.UTF_8)
    }

    private fun createKey(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }

    private fun existingKey(alias: String): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias, null) as? SecretKey
    }

    private fun deleteKey(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private fun clearLegacyState(tenantId: String) {
        check(legacyPreferences(tenantId).edit().clear().commit()) {
            "Could not clear legacy encrypted sync state"
        }
        deleteKey(legacyKeyAlias(tenantId))
    }

    private fun databaseFile(tenantId: String): File = applicationContext.getDatabasePath(databaseName(tenantId))

    private fun databaseName(tenantId: String) = "$DATABASE_PREFIX${tenantDigest(tenantId)}.db"

    private fun databaseKeyAlias(tenantId: String) = "$DATABASE_KEY_PREFIX${tenantDigest(tenantId)}"

    private fun legacyPreferences(tenantId: String) = applicationContext.getSharedPreferences(
        "$LEGACY_PREFERENCES_PREFIX${tenantDigest(tenantId)}",
        Context.MODE_PRIVATE,
    )

    private fun legacyKeyAlias(tenantId: String) = "$LEGACY_KEY_PREFIX${tenantDigest(tenantId)}"

    private fun rowAad(tenantId: String, table: String, identity: String) =
        "blocks-sync-db:$tenantId:$table:$identity".toByteArray(Charsets.UTF_8)

    private fun legacyAad(tenantId: String) = "blocks-sync-state:$tenantId".toByteArray(Charsets.UTF_8)

    private fun tenantDigest(tenantId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tenantId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private class TenantDatabaseHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.execSQL("PRAGMA secure_delete=ON")
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $TABLE_METADATA (" +
                    "$COLUMN_NAME TEXT PRIMARY KEY NOT NULL, " +
                    "$COLUMN_CIPHERTEXT BLOB NOT NULL)",
            )
            listOf(TABLE_PROJECTS, TABLE_REENTRY, TABLE_COMMANDS, TABLE_RETIRED_KEYS).forEach { table ->
                database.execSQL(
                    "CREATE TABLE $table (" +
                        "$COLUMN_POSITION INTEGER PRIMARY KEY NOT NULL, " +
                        "$COLUMN_CIPHERTEXT BLOB NOT NULL)",
                )
            }
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No sync database migration from $oldVersion to $newVersion")
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Sync database downgrade from $oldVersion to $newVersion is not supported")
        }
    }

    private companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_PREFIX = "blocks_sync_"
        const val DATABASE_KEY_PREFIX = "blocks-sync-db-v1-"
        const val LEGACY_PREFERENCES_PREFIX = "encrypted_sync_state_v1_"
        const val LEGACY_KEY_PREFIX = "blocks-sync-state-v1-"
        const val LEGACY_CIPHERTEXT = "state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TABLE_METADATA = "sync_metadata"
        const val TABLE_PROJECTS = "cached_projects"
        const val TABLE_REENTRY = "latest_reentry"
        const val TABLE_COMMANDS = "pending_commands"
        const val TABLE_RETIRED_KEYS = "retired_keys"
        const val COLUMN_NAME = "name"
        const val COLUMN_POSITION = "position"
        const val COLUMN_CIPHERTEXT = "ciphertext"
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_PROJECTS_LOADED = "projects_loaded"
        const val META_REENTRY_LOADED = "reentry_loaded"
        const val META_CHANGE_CURSOR = "change_cursor"
        const val META_NOTICE = "notice"
        val TABLES = listOf(
            TABLE_METADATA,
            TABLE_PROJECTS,
            TABLE_REENTRY,
            TABLE_COMMANDS,
            TABLE_RETIRED_KEYS,
        )
    }
}
