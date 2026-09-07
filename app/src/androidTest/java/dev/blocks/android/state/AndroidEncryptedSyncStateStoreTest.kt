package dev.blocks.android.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectSummary
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedSyncStateStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidEncryptedSyncStateStore
        get() = AndroidEncryptedSyncStateStore(context)

    @Before
    @After
    fun clearTestTenants() {
        listOf(TENANT_A, TENANT_B, LEGACY_TENANT, PILOT_DATABASE_TENANT).forEach { tenantId ->
            store.clearSyncState(tenantId)
        }
    }

    @Test
    fun storesEncryptedTenantDatabaseRowsAndDeletesOnlyTheRequestedDatabaseAndKey() {
        val command = QueuedCommand(
            operation = OfflineOperation.PICKUP,
            targetId = "project-a",
            projectId = "project-a",
            idempotencyKey = "sensitive-retry-key-a",
            pickup = PickupRequest("project-a", "Sensitive outcome A"),
        )
        val tenantAState = TenantSyncState(
            projects = listOf(Project("project-a", "Sensitive project A")),
            projectsLoaded = true,
            commands = listOf(command),
            changeCursor = "41",
        )
        val tenantBState = TenantSyncState(
            projects = listOf(Project("project-b", "Sensitive project B")),
            projectsLoaded = true,
            changeCursor = "84",
        )

        store.saveSyncState(TENANT_A, tenantAState)
        store.saveSyncState(TENANT_B, tenantBState)

        assertEquals(tenantAState, store.loadSyncState(TENANT_A))
        assertEquals(tenantBState, store.loadSyncState(TENANT_B))
        assertTrue(databaseFile(TENANT_A).isFile)
        assertTrue(databaseFile(TENANT_B).isFile)
        assertTrue(keyStore().containsAlias(databaseKeyAlias(TENANT_A)))
        assertTrue(keyStore().containsAlias(databaseKeyAlias(TENANT_B)))
        val databaseBytes = databaseFile(TENANT_A).readBytes()
        assertFalse(databaseBytes.containsBytes("Sensitive project A".toByteArray()))
        assertFalse(databaseBytes.containsBytes("Sensitive outcome A".toByteArray()))
        assertFalse(databaseBytes.containsBytes("sensitive-retry-key-a".toByteArray()))

        val invalid = tenantAState.copy(commands = listOf(command, command))
        assertTrue(runCatching { store.saveSyncState(TENANT_A, invalid) }.isFailure)
        assertEquals(tenantAState, store.loadSyncState(TENANT_A))

        store.clearSyncState(TENANT_A)

        assertFalse(databaseFile(TENANT_A).exists())
        assertFalse(keyStore().containsAlias(databaseKeyAlias(TENANT_A)))
        assertEquals(TenantSyncState(), store.loadSyncState(TENANT_A))
        assertEquals(tenantBState, store.loadSyncState(TENANT_B))
        assertTrue(keyStore().containsAlias(databaseKeyAlias(TENANT_B)))
    }

    @Test
    fun migratesTheLegacyPreferenceQueueBeforeDeletingItsCiphertextAndKey() {
        val legacyKey = createLegacyKey(LEGACY_TENANT)
        val legacyJson = Gson().toJson(
            mapOf(
                "schema_version" to 1,
                "projectsLoaded" to true,
                "projects" to listOf(Project("legacy-project", "Legacy project")),
                "commands" to listOf(
                    QueuedCommand(
                        operation = OfflineOperation.PICKUP,
                        targetId = "legacy-project",
                        projectId = "legacy-project",
                        idempotencyKey = "legacy-operation-key",
                        pickup = PickupRequest("legacy-project", "Legacy outcome"),
                    ),
                ),
                "changeCursor" to "19",
            ),
        )
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, legacyKey)
            updateAAD("blocks-sync-state:$LEGACY_TENANT".toByteArray())
        }
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(legacyJson.toByteArray())
        assertTrue(
            legacyPreferences(LEGACY_TENANT).edit()
                .putString("state", Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit(),
        )

        val migrated = store.loadSyncState(LEGACY_TENANT)

        assertEquals("legacy-operation-key", migrated.commands.single().idempotencyKey)
        assertEquals("19", migrated.changeCursor)
        assertTrue(databaseFile(LEGACY_TENANT).isFile)
        assertTrue(keyStore().containsAlias(databaseKeyAlias(LEGACY_TENANT)))
        assertNull(legacyPreferences(LEGACY_TENANT).getString("state", null))
        assertFalse(keyStore().containsAlias(legacyKeyAlias(LEGACY_TENANT)))
        assertEquals(migrated, store.loadSyncState(LEGACY_TENANT))
    }

    @Test
    fun upgradesTheSignedPilotDatabaseWithoutRotatingAnUnresolvedOperationIdentity() {
        val key = createKey(databaseKeyAlias(PILOT_DATABASE_TENANT))
        val unresolved = QueuedCommand(
            operation = OfflineOperation.PICKUP,
            targetId = "pilot-project",
            projectId = "pilot-project",
            idempotencyKey = "pilot-original-operation-key",
            pickup = PickupRequest("pilot-project", "Pilot outcome"),
            afterCursor = "28",
            deliveryUncertain = true,
        )
        createSignedPilotDatabase(
            tenantId = PILOT_DATABASE_TENANT,
            key = key,
            projects = listOf(Project("pilot-project", "Pilot project")),
            commands = listOf(unresolved),
            cursor = "29",
        )

        val migrated = store.loadSyncState(PILOT_DATABASE_TENANT)

        assertEquals(1, migrated.commands.size)
        assertEquals("pilot-original-operation-key", migrated.commands.single().idempotencyKey)
        assertEquals("28", migrated.commands.single().afterCursor)
        assertTrue(migrated.commands.single().deliveryUncertain)
        assertEquals("29", migrated.changeCursor)
        assertEquals(listOf(Project("pilot-project", "Pilot project")), migrated.projects)
        assertEquals(ProjectCacheState(), migrated.projectCache)
        assertEquals(2, databaseVersion(PILOT_DATABASE_TENANT))
        assertFalse(databaseFile(PILOT_DATABASE_TENANT).readBytes().containsBytes("Pilot outcome".toByteArray()))

        val api03Command = ProjectMutation(
            operationId = "api-03-original-operation-key",
            type = ProjectMutationType.CREATE_PROJECT,
            targetId = "new-project",
            idempotencyKey = "api-03-original-operation-key",
            projectName = "New project",
        )
        val api03Project = ProjectSummary(
            id = "new-project",
            tenantId = PILOT_DATABASE_TENANT,
            name = "New project",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 1,
            createdAt = "2026-09-07T12:00:00Z",
            updatedAt = "2026-09-07T12:00:00Z",
        )
        val evolved = migrated.copy(
            projectCache = ProjectCacheState(
                activeProjects = listOf(api03Project),
                activeProjectsLoaded = true,
                commands = listOf(api03Command),
            ),
        )

        store.saveSyncState(PILOT_DATABASE_TENANT, evolved)

        val restarted = store.loadSyncState(PILOT_DATABASE_TENANT)
        assertEquals("pilot-original-operation-key", restarted.commands.single().idempotencyKey)
        assertEquals("api-03-original-operation-key", restarted.projectCache.commands.single().operationId)
        assertEquals(listOf(api03Project), restarted.projectCache.activeProjects)
    }

    private fun createSignedPilotDatabase(
        tenantId: String,
        key: javax.crypto.SecretKey,
        projects: List<Project>,
        commands: List<QueuedCommand>,
        cursor: String,
    ) {
        val database = context.openOrCreateDatabase(databaseName(tenantId), Context.MODE_PRIVATE, null)
        try {
            database.execSQL(
                "CREATE TABLE sync_metadata (" +
                    "name TEXT PRIMARY KEY NOT NULL, ciphertext BLOB NOT NULL)",
            )
            listOf("cached_projects", "latest_reentry", "pending_commands", "retired_keys").forEach { table ->
                database.execSQL(
                    "CREATE TABLE $table (" +
                        "position INTEGER PRIMARY KEY NOT NULL, ciphertext BLOB NOT NULL)",
                )
            }
            database.version = 1
            writePilotMetadata(database, tenantId, key, "schema_version", "1")
            writePilotMetadata(database, tenantId, key, "projects_loaded", "true")
            writePilotMetadata(database, tenantId, key, "reentry_loaded", "false")
            writePilotMetadata(database, tenantId, key, "change_cursor", cursor)
            projects.forEachIndexed { position, project ->
                writePilotRow(database, tenantId, key, "cached_projects", position, Gson().toJson(project))
            }
            commands.forEachIndexed { position, command ->
                writePilotRow(database, tenantId, key, "pending_commands", position, Gson().toJson(command))
            }
        } finally {
            database.close()
        }
    }

    private fun writePilotMetadata(
        database: SQLiteDatabase,
        tenantId: String,
        key: javax.crypto.SecretKey,
        name: String,
        value: String,
    ) {
        val row = ContentValues().apply {
            put("name", name)
            put("ciphertext", encryptDatabaseRow(key, tenantId, "sync_metadata", name, value))
        }
        database.insertOrThrow("sync_metadata", null, row)
    }

    private fun writePilotRow(
        database: SQLiteDatabase,
        tenantId: String,
        key: javax.crypto.SecretKey,
        table: String,
        position: Int,
        value: String,
    ) {
        val row = ContentValues().apply {
            put("position", position)
            put("ciphertext", encryptDatabaseRow(key, tenantId, table, position.toString(), value))
        }
        database.insertOrThrow(table, null, row)
    }

    private fun encryptDatabaseRow(
        key: javax.crypto.SecretKey,
        tenantId: String,
        table: String,
        identity: String,
        value: String,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("blocks-sync-db:$tenantId:$table:$identity".toByteArray())
        }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray())
    }

    private fun databaseVersion(tenantId: String): Int =
        SQLiteDatabase.openDatabase(databaseFile(tenantId).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.version
        }

    private fun createLegacyKey(tenantId: String) = createKey(legacyKeyAlias(tenantId))

    private fun createKey(alias: String) =
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

    private fun databaseFile(tenantId: String) = context.getDatabasePath(databaseName(tenantId))

    private fun databaseName(tenantId: String) = "blocks_sync_${tenantDigest(tenantId)}.db"

    private fun databaseKeyAlias(tenantId: String) = "blocks-sync-db-v1-${tenantDigest(tenantId)}"

    private fun legacyKeyAlias(tenantId: String) = "blocks-sync-state-v1-${tenantDigest(tenantId)}"

    private fun legacyPreferences(tenantId: String) = context.getSharedPreferences(
        "encrypted_sync_state_v1_${tenantDigest(tenantId)}",
        Context.MODE_PRIVATE,
    )

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun tenantDigest(tenantId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tenantId.toByteArray())
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.containsBytes(value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > size) return false
        return indices.take(size - value.size + 1).any { offset ->
            value.indices.all { index -> this[offset + index] == value[index] }
        }
    }

    private companion object {
        const val TENANT_A = "00000000-0000-0000-0000-00000000000a"
        const val TENANT_B = "00000000-0000-0000-0000-00000000000b"
        const val LEGACY_TENANT = "00000000-0000-0000-0000-00000000000c"
        const val PILOT_DATABASE_TENANT = "00000000-0000-0000-0000-00000000000d"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
