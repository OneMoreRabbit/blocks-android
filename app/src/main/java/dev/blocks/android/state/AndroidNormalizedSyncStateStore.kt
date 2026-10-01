package dev.blocks.android.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.InteractionMutationType
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockRecord
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tenant-partitioned app-private state introduced in 0.7.1.
 *
 * Ordinary project state is stored as independently keyed SQLite rows. A save diffs the
 * requested projection inside one transaction, so a button action touches only changed
 * domain rows and its queue row. Legacy raw tag tokens are the sole encrypted database
 * payloads; their key remains in Android Keystore. The released 0.7.0 database is retained
 * until the normalized copy has been read back and verified.
 */
class AndroidSyncStateStore internal constructor(
    context: Context,
    private val migrationVerifier: (TenantSyncState, TenantSyncState) -> Unit = { expected, actual ->
        check(actual == expected) { "Normalized migration verification failed" }
    },
    private val migrationCheckpoint: (MigrationCheckpoint) -> Unit = {},
    private val beforeMutationCommit: (ProjectMutationType) -> Unit = {},
    private val beforeItemMutationCommit: (ProjectItemMutationType) -> Unit = {},
) : SyncStateStore {
    private val applicationContext = context.applicationContext
    private val rowCodec = SyncStateRowCodec()
    private val gson = Gson()

    // Retaining these adapters builds Gson's reflective metadata at store creation so the first
    // foreground interaction does not pay that one-off work inside its local-first commit.
    @Suppress("unused")
    private val warmedInteractionAdapters = listOf(
        gson.getAdapter(InteractionMutation::class.java),
        gson.getAdapter(dev.blocks.android.project.ProjectOrderState::class.java),
        gson.getAdapter(LinkOrderState::class.java),
        gson.getAdapter(ParkingTombstone::class.java),
    )

    @Synchronized
    override fun loadSyncState(tenantId: String): TenantSyncState {
        if (!databaseFile(tenantId).exists()) return migrateReleasedStore(tenantId)
        if (!isAuthoritative(tenantId)) {
            check(releasedStateExists(tenantId)) {
                "Unverified normalized tenant state has no released recovery source"
            }
            discardNormalizedReplacement(tenantId)
            return migrateReleasedStore(tenantId)
        }
        val (rows, state) = open(tenantId).use { helper ->
            val database = helper.writableDatabase
            val encoded = readRows(database, tenantId)
            // Prepare SQLite's transaction path while restoring cached state, before the first
            // user action enters its atomic local-first commit boundary.
            database.beginTransaction()
            try {
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            encoded to rowCodec.decode(encoded)
        }
        validateTenant(tenantId, state)
        if (rows.schemaVersion < SyncStateRowCodec.CURRENT_SCHEMA_VERSION) {
            persist(tenantId, previous = null, state = state)
        }
        retireReleasedStoreIfPresent(tenantId)
        return state
    }

    @Synchronized
    override fun saveSyncState(tenantId: String, state: TenantSyncState) {
        persist(tenantId, previous = null, state = state)
    }

    @Synchronized
    override fun saveSyncState(tenantId: String, previous: TenantSyncState, state: TenantSyncState) {
        persist(tenantId, previous = previous, state = state)
    }

    @Synchronized
    override fun saveProjectMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectMutationType,
    ) {
        if (!databaseFile(tenantId).exists()) {
            persist(tenantId, previous = null, state = state)
            return
        }
        validateTenant(tenantId, state)
        open(tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                persistMutationRows(database, previous, state, type)
                beforeMutationCommit(type)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    @Synchronized
    override fun saveProjectItemMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectItemMutationType,
    ) {
        if (!databaseFile(tenantId).exists()) {
            persist(tenantId, previous = null, state = state)
            return
        }
        validateTenant(tenantId, state)
        open(tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                persistItemMutationRows(database, previous, state, type)
                beforeItemMutationCommit(type)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    @Synchronized
    override fun saveInteractionMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        projectMutationType: ProjectMutationType?,
        itemMutationType: ProjectItemMutationType?,
    ) {
        if (!databaseFile(tenantId).exists()) {
            persist(tenantId, previous = null, state = state)
            return
        }
        require(projectMutationType == null || itemMutationType == null) {
            "One local action cannot add both a Project and item mutation"
        }
        validateTenant(tenantId, state)
        val operation = validateInteractionAppend(previous, state)
        open(tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                projectMutationType?.let { persistMutationRows(database, previous, state, it) }
                itemMutationType?.let { persistItemMutationRows(database, previous, state, it) }
                persistInteractionAppend(database, state, operation)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun persistInteractionAppend(
        database: SQLiteDatabase,
        state: TenantSyncState,
        operation: InteractionMutation,
    ) {
        upsertRecord(
            database,
            TABLE_INTERACTION_OPERATIONS,
            operation.operationId,
            state.projectCache.interactionOperations.lastIndex,
            gson.toJson(operation),
        )
        when (operation.type) {
            InteractionMutationType.REORDER_PROJECTS -> upsertRecord(
                database,
                TABLE_PROJECT_ORDER,
                SINGLETON_ID,
                0,
                gson.toJson(state.projectCache.projectOrder),
            )
            InteractionMutationType.REORDER_LINKS -> {
                val order = state.projectCache.linkOrders.single { it.projectId == operation.projectId }
                upsertRecord(
                    database,
                    TABLE_LINK_ORDERS,
                    order.projectId,
                    state.projectCache.linkOrders.indexOf(order),
                    gson.toJson(order),
                )
            }
            InteractionMutationType.DELETE_PARKING -> {
                val tombstone = state.projectCache.parkingTombstones.single {
                    it.parkingId == operation.targetId
                }
                upsertRecord(
                    database,
                    TABLE_PARKING_TOMBSTONES,
                    tombstone.parkingId,
                    state.projectCache.parkingTombstones.indexOf(tombstone),
                    gson.toJson(tombstone),
                )
            }
        }
    }

    private fun validateInteractionAppend(
        previous: TenantSyncState,
        state: TenantSyncState,
    ): InteractionMutation {
        val before = previous.projectCache
        val current = state.projectCache
        require(current.interactionOperations.size == before.interactionOperations.size + 1 &&
            current.interactionOperations.dropLast(1) == before.interactionOperations) {
            "An interaction transaction must append exactly one operation"
        }
        require(current.retiredInteractionOperationIds == before.retiredInteractionOperationIds) {
            "An interaction append cannot also retire operations"
        }
        val operation = current.interactionOperations.last()
        require(operation.operationId.isNotBlank() && operation.contractVersion == "0.7" &&
            before.interactionOperations.none { it.operationId == operation.operationId } &&
            operation.operationId !in current.retiredInteractionOperationIds) {
            "The appended interaction operation identity is invalid"
        }
        require(operation.baseVersion >= 0) { "The interaction base version is invalid" }
        val knownDependencies = before.interactionOperations.mapTo(mutableSetOf()) { it.operationId }.apply {
            addAll(current.commands.map { it.operationId })
            addAll(current.itemOperations.map { it.operationId })
            addAll(current.retiredOperationIds)
            addAll(current.retiredItemOperationIds)
            addAll(current.retiredInteractionOperationIds)
        }
        require(operation.dependsOnOperationIds.all(knownDependencies::contains)) {
            "An interaction operation dependency must precede it"
        }
        when (operation.type) {
            InteractionMutationType.REORDER_PROJECTS -> {
                require(operation.projectId == null && operation.targetId == null)
                val clientIdByProjectId = current.activeProjects.associate { project ->
                    project.id to project.clientId
                }
                val orderedClientIds = current.projectOrder.orderedProjectIds.map { projectId ->
                    clientIdByProjectId[projectId] ?: projectId
                }
                require(current.projectOrder.version == before.projectOrder.version + 1 &&
                    orderedClientIds == operation.orderedClientIds &&
                    current.projectOrder.orderedProjectIds.toSet() == current.activeProjects.map { it.id }.toSet() &&
                    current.projectOrder.orderedProjectIds.distinct().size == current.projectOrder.orderedProjectIds.size) {
                    "The appended Project order does not match its aggregate"
                }
            }
            InteractionMutationType.REORDER_LINKS -> {
                val projectId = requireNotNull(operation.projectId)
                require(operation.targetId == null)
                val old = before.linkOrders.firstOrNull { it.projectId == projectId } ?: LinkOrderState(projectId)
                val order = current.linkOrders.single { it.projectId == projectId }
                val liveIds = current.resourceLinks.filter {
                    it.projectId == projectId && it.deletedAt == null
                }.map { it.id }
                require(order.version == old.version + 1 && order.orderedLinkIds == operation.orderedClientIds &&
                    order.orderedLinkIds.toSet() == liveIds.toSet() &&
                    order.orderedLinkIds.distinct().size == order.orderedLinkIds.size) {
                    "The appended Link order does not match its aggregate"
                }
            }
            InteractionMutationType.DELETE_PARKING -> {
                val projectId = requireNotNull(operation.projectId)
                val parkingId = requireNotNull(operation.targetId)
                require(operation.orderedClientIds.isEmpty())
                require(current.parkingTombstones.size == before.parkingTombstones.size + 1 &&
                    current.parkingTombstones.dropLast(1) == before.parkingTombstones) {
                    "A Park deletion must append exactly one tombstone"
                }
                val tombstone = current.parkingTombstones.last()
                require(tombstone.parkingId == parkingId && tombstone.projectId == projectId &&
                    tombstone.version == operation.baseVersion + 1 &&
                    tombstone.lastOperationId == operation.operationId && tombstone.deletedAt.isNotBlank() &&
                    current.parkingRecords.any { it.id == parkingId && it.projectId == projectId }) {
                    "The Park tombstone does not match its immutable authored row"
                }
            }
        }
        return operation
    }

    private fun persistItemMutationRows(
        database: SQLiteDatabase,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectItemMutationType,
    ) {
        val operation = state.projectCache.itemOperations.last()
        require(operation.type == type && previous.projectCache.itemOperations.none {
            it.operationId == operation.operationId
        })
        upsertRecord(
            database,
            TABLE_PROJECT_ITEM_OPERATIONS,
            operation.operationId,
            state.projectCache.itemOperations.lastIndex,
            gson.toJson(operation),
        )
        when (type) {
            ProjectItemMutationType.CREATE_NOTE,
            ProjectItemMutationType.EDIT_NOTE,
            ProjectItemMutationType.PIN_NOTE,
            ProjectItemMutationType.DELETE_NOTE,
            -> {
                val note = state.projectCache.noteItems.single { it.id == operation.targetId }
                upsertRecord(
                    database,
                    TABLE_PROJECT_NOTE_ITEMS,
                    note.id,
                    state.projectCache.noteItems.indexOf(note),
                    gson.toJson(note),
                )
            }
            ProjectItemMutationType.CREATE_LINK,
            ProjectItemMutationType.EDIT_LINK,
            ProjectItemMutationType.DELETE_LINK,
            -> {
                val link = state.projectCache.resourceLinks.single { it.id == operation.targetId }
                upsertRecord(
                    database,
                    TABLE_PROJECT_RESOURCE_LINKS,
                    link.id,
                    state.projectCache.resourceLinks.indexOf(link),
                    gson.toJson(link),
                )
            }
        }
        if (previous.projectDrafts != state.projectDrafts) {
            upsertRecord(database, TABLE_PROJECT_DRAFTS, SINGLETON_ID, 0, gson.toJson(state.projectDrafts))
        }
    }

    private fun persistMutationRows(
        database: SQLiteDatabase,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectMutationType,
    ) {
        val command = state.projectCache.commands.last()
        require(command.type == type && previous.projectCache.commands.none { it.operationId == command.operationId })
        upsertRecord(
            database,
            TABLE_PROJECT_COMMANDS,
            command.operationId,
            state.projectCache.commands.lastIndex,
            gson.toJson(command),
        )
        when (type) {
            ProjectMutationType.CREATE_PROJECT -> {
                val project = state.projectCache.activeProjects.single { it.id == command.targetId }
                val page = state.projectCache.pages.single { it.project.id == command.targetId }
                val link = state.projectCache.projectLinks.single { it.projectId == command.targetId }
                upsertRecord(database, TABLE_ACTIVE_PROJECTS, project.id, state.projectCache.activeProjects.indexOf(project), gson.toJson(project))
                upsertRecord(database, TABLE_PROJECT_PAGES, project.id, state.projectCache.pages.indexOf(page), gson.toJson(project))
                upsertRecord(database, TABLE_PROJECT_NOTES, project.id, state.projectCache.pages.indexOf(page), gson.toJson(page.notes))
                upsertRecord(database, TABLE_PROJECT_LINKS, project.id, state.projectCache.projectLinks.indexOf(link), gson.toJson(link))
                upsertRecord(database, TABLE_PROVISIONAL_PROJECTS, project.id, state.projectCache.provisionalProjectIds.indexOf(project.id), project.id)
                upsertRecord(
                    database,
                    TABLE_PARKING_LOADED_PROJECTS,
                    project.id,
                    state.projectCache.parkingLoadedProjectIds.indexOf(project.id),
                    project.id,
                )
                upsertMetadata(database, META_ACTIVE_PROJECTS_LOADED, "true", previousKnown = true)
            }
            ProjectMutationType.RENAME_PROJECT -> persistProjectSummary(database, state, command.targetId)
            ProjectMutationType.ARCHIVE_PROJECT -> {
                database.delete(TABLE_ACTIVE_PROJECTS, "$COLUMN_ID = ?", arrayOf(command.targetId))
                persistProjectSummary(database, state, command.targetId)
            }
            ProjectMutationType.REOPEN_PROJECT -> {
                database.delete(TABLE_ARCHIVED_PROJECTS, "$COLUMN_ID = ?", arrayOf(command.targetId))
                persistProjectSummary(database, state, command.targetId)
            }
            ProjectMutationType.SAVE_NOTES -> {
                val page = state.projectCache.pages.single { it.project.id == command.targetId }
                upsertRecord(database, TABLE_PROJECT_NOTES, command.targetId, state.projectCache.pages.indexOf(page), gson.toJson(page.notes))
            }
            ProjectMutationType.ADD_PARKING -> {
                val parking = state.projectCache.parkingRecords.single { it.id == command.clientResourceId }
                upsertRecord(
                    database,
                    TABLE_PARKING_RECORDS,
                    parking.id,
                    state.projectCache.parkingRecords.indexOf(parking),
                    gson.toJson(parking),
                )
            }
            ProjectMutationType.BIND_BLOCK, ProjectMutationType.UNBIND_BLOCK -> Unit
        }
        if (previous.projectDrafts != state.projectDrafts) {
            upsertRecord(database, TABLE_PROJECT_DRAFTS, SINGLETON_ID, 0, gson.toJson(state.projectDrafts))
        }
    }

    private fun persistProjectSummary(database: SQLiteDatabase, state: TenantSyncState, projectId: String) {
        val page = state.projectCache.pages.single { it.project.id == projectId }
        val activeIndex = state.projectCache.activeProjects.indexOfFirst { it.id == projectId }
        val archivedIndex = state.projectCache.archivedProjects.indexOfFirst { it.id == projectId }
        if (activeIndex >= 0) {
            upsertRecord(database, TABLE_ACTIVE_PROJECTS, projectId, activeIndex, gson.toJson(page.project))
        }
        if (archivedIndex >= 0) {
            upsertRecord(database, TABLE_ARCHIVED_PROJECTS, projectId, archivedIndex, gson.toJson(page.project))
        }
        upsertRecord(database, TABLE_PROJECT_PAGES, projectId, state.projectCache.pages.indexOf(page), gson.toJson(page.project))
    }

    private fun upsertRecord(
        database: SQLiteDatabase,
        table: String,
        id: String,
        position: Int,
        value: String,
    ) {
        check(position >= 0)
        val payload = value.toByteArray(Charsets.UTF_8)
        database.insertWithOnConflict(
            table,
            null,
            ContentValues().apply {
                put(COLUMN_ID, id)
                put(COLUMN_POSITION, position)
                put(COLUMN_PAYLOAD, payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) }
    }

    private fun persist(tenantId: String, previous: TenantSyncState?, state: TenantSyncState) {
        if (state == TenantSyncState()) {
            clearSyncState(tenantId)
            return
        }
        validateTenant(tenantId, state)
        val desired = rowCodec.encode(state)
        val existed = databaseFile(tenantId).exists()
        try {
            open(tenantId).use { helper ->
                val database = helper.writableDatabase
                database.beginTransaction()
                try {
                    writeDiff(database, tenantId, previous?.takeIf { existed }?.let(rowCodec::encode), desired)
                    upsertMetadata(database, META_AUTHORITATIVE, "true", previousKnown = existed)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        } catch (error: Exception) {
            if (!existed) {
                applicationContext.deleteDatabase(databaseName(tenantId))
                deleteSecretKey(tenantId)
            }
            throw IllegalStateException("Could not transactionally persist tenant sync state", error)
        }
    }

    @Synchronized
    override fun clearSyncState(tenantId: String) {
        val failures = mutableListOf<Throwable>()
        runCatching {
            if (databaseFile(tenantId).exists()) {
                check(applicationContext.deleteDatabase(databaseName(tenantId))) {
                    "Could not delete tenant state database"
                }
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching { deleteSecretKey(tenantId) }.exceptionOrNull()?.let(failures::add)
        runCatching { LegacyEncryptedSyncStateStore(applicationContext).clearSyncState(tenantId) }
            .exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw IllegalStateException("Could not clear tenant local state", failure)
        }
    }

    private fun migrateReleasedStore(tenantId: String): TenantSyncState {
        if (!releasedStateExists(tenantId)) return TenantSyncState()
        val released = LegacyEncryptedSyncStateStore(applicationContext).loadSyncState(tenantId)
        try {
            writeMigratedState(tenantId, released)
            migrationCheckpoint(MigrationCheckpoint.NORMALIZED_COMMITTED)
            val verified = open(tenantId).use { helper ->
                rowCodec.decode(readRows(helper.readableDatabase, tenantId))
            }
            migrationVerifier(released, verified)
            validateTenant(tenantId, verified)
        } catch (error: Exception) {
            discardNormalizedReplacement(tenantId)
            throw IllegalStateException("Could not migrate the released tenant store", error)
        }
        markAuthoritative(tenantId)
        migrationCheckpoint(MigrationCheckpoint.VERIFIED_MARKER_COMMITTED)
        try {
            LegacyEncryptedSyncStateStore(applicationContext).clearSyncState(tenantId)
        } catch (error: Exception) {
            throw IllegalStateException("Could not retire the verified released tenant store", error)
        }
        return open(tenantId).use { helper ->
            rowCodec.decode(readRows(helper.readableDatabase, tenantId))
        }
    }

    private fun writeMigratedState(tenantId: String, state: TenantSyncState) {
        validateTenant(tenantId, state)
        open(tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                writeDiff(database, tenantId, previous = null, rows = rowCodec.encode(state))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun markAuthoritative(tenantId: String) {
        open(tenantId).use { helper ->
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                upsertMetadata(database, META_AUTHORITATIVE, "true", previousKnown = false)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun isAuthoritative(tenantId: String): Boolean = runCatching {
        open(tenantId).use { helper ->
            helper.readableDatabase.query(
                TABLE_METADATA,
                arrayOf(COLUMN_VALUE),
                "$COLUMN_ID = ?",
                arrayOf(META_AUTHORITATIVE),
                null,
                null,
                null,
            ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "true" }
        }
    }.getOrDefault(false)

    private fun discardNormalizedReplacement(tenantId: String) {
        if (databaseFile(tenantId).exists()) {
            check(applicationContext.deleteDatabase(databaseName(tenantId))) {
                "Could not discard an unverified normalized tenant store"
            }
        }
        deleteSecretKey(tenantId)
    }

    private fun retireReleasedStoreIfPresent(tenantId: String) {
        if (releasedArtifactsExist(tenantId)) {
            LegacyEncryptedSyncStateStore(applicationContext).clearSyncState(tenantId)
        }
    }

    private fun releasedArtifactsExist(tenantId: String): Boolean =
        releasedStateExists(tenantId) || KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias("blocks-sync-db-v1-${tenantDigest(tenantId)}") ||
                containsAlias("blocks-sync-state-v1-${tenantDigest(tenantId)}")
        }

    private fun releasedStateExists(tenantId: String): Boolean =
        applicationContext.getDatabasePath("blocks_sync_${tenantDigest(tenantId)}.db").exists() ||
            applicationContext.getSharedPreferences(
                "encrypted_sync_state_v1_${tenantDigest(tenantId)}",
                Context.MODE_PRIVATE,
            ).contains("state")

    private fun validateTenant(tenantId: String, state: TenantSyncState) {
        val projectTenants = (state.projectCache.activeProjects + state.projectCache.archivedProjects)
            .map(ProjectSummary::tenantId)
        require(projectTenants.all { it == tenantId }) { "Project state crosses a tenant boundary" }
        require(state.projectCache.projectCovers.all { it.tenantId == tenantId }) {
            "Project cover state crosses a tenant boundary"
        }
        validateProjectItemOperations(state.projectCache)
    }

    private fun readRows(database: SQLiteDatabase, tenantId: String): EncodedSyncRows {
        fun metadata(name: String, default: String? = null): String? = database.query(
            TABLE_METADATA,
            arrayOf(COLUMN_VALUE),
            "$COLUMN_ID = ?",
            arrayOf(name),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else default }

        fun rows(table: String): List<String> = database.query(
            table,
            arrayOf(COLUMN_PAYLOAD),
            null,
            null,
            null,
            null,
            "$COLUMN_POSITION ASC, $COLUMN_ID ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val packed = cursor.getBlob(0)
                    add(if (table in SECRET_TABLES) decryptSecret(tenantId, table, packed) else packed.toString(Charsets.UTF_8))
                }
            }
        }

        val pageProjects = rows(TABLE_PROJECT_PAGES).map { gson.fromJson(it, ProjectSummary::class.java) }
        val pageNotes = rows(TABLE_PROJECT_NOTES).map { gson.fromJson(it, dev.blocks.android.project.ProjectNotes::class.java) }
        val parkingRows = rows(TABLE_PARKING_RECORDS)
        val parking = parkingRows.map { gson.fromJson(it, ProjectParking::class.java) }
        val pages = pageProjects.map { project ->
            val notes = pageNotes.single { it.projectId == project.id }
            val newest = parking.filter { it.projectId == project.id }
                .maxWithOrNull(compareBy<ProjectParking> { it.occurredAt }.thenBy { it.createdAt }.thenBy { it.id })
            gson.toJson(ProjectPage(project, notes, newest))
        }
        return EncodedSyncRows(
            schemaVersion = metadata(META_SCHEMA_VERSION)?.toInt()
                ?: error("Local state schema is missing"),
            projectsLoaded = metadata(META_PROJECTS_LOADED, "false")!!.toBooleanStrict(),
            reentryLoaded = metadata(META_REENTRY_LOADED, "false")!!.toBooleanStrict(),
            changeCursor = metadata(META_CHANGE_CURSOR),
            notice = metadata(META_NOTICE),
            projects = rows(TABLE_PROJECTS),
            reentry = rows(TABLE_REENTRY).singleOrNull(),
            commands = rows(TABLE_COMMANDS),
            retiredIdempotencyKeys = rows(TABLE_RETIRED_KEYS),
            activeProjectsLoaded = metadata(META_ACTIVE_PROJECTS_LOADED, "false")!!.toBooleanStrict(),
            archivedProjectsLoaded = metadata(META_ARCHIVED_PROJECTS_LOADED, "false")!!.toBooleanStrict(),
            blocksLoaded = metadata(META_BLOCKS_LOADED, "false")!!.toBooleanStrict(),
            activeProjects = rows(TABLE_ACTIVE_PROJECTS),
            archivedProjects = rows(TABLE_ARCHIVED_PROJECTS),
            projectPages = pages,
            parkingRecords = parkingRows,
            parkingLoadedProjectIds = rows(TABLE_PARKING_LOADED_PROJECTS),
            stalePageIds = rows(TABLE_STALE_PROJECT_PAGES),
            blocks = rows(TABLE_BLOCKS),
            localBlocks = rows(TABLE_LOCAL_BLOCKS),
            localBlockOperations = rows(TABLE_LOCAL_BLOCK_OPERATIONS),
            projectLinks = rows(TABLE_PROJECT_LINKS),
            projectCovers = rows(TABLE_PROJECT_COVERS),
            projectNoteItems = rows(TABLE_PROJECT_NOTE_ITEMS),
            projectResourceLinks = rows(TABLE_PROJECT_RESOURCE_LINKS),
            projectItemOperations = rows(TABLE_PROJECT_ITEM_OPERATIONS),
            retiredProjectItemOperations = rows(TABLE_RETIRED_PROJECT_ITEM_OPERATIONS),
            projectCommands = rows(TABLE_PROJECT_COMMANDS),
            retiredOperationIds = rows(TABLE_RETIRED_OPERATIONS),
            provisionalProjectIds = rows(TABLE_PROVISIONAL_PROJECTS),
            projectIdMappings = rows(TABLE_PROJECT_ID_MAPPINGS),
            projectsAwaitingNotesBaseline = rows(TABLE_PROJECTS_AWAITING_NOTES_BASELINE),
            projectDrafts = rows(TABLE_PROJECT_DRAFTS).singleOrNull(),
            projectDraftsMigrated = metadata(META_PROJECT_DRAFTS_MIGRATED, "false")!!.toBooleanStrict(),
            projectUiPreferences = rows(TABLE_PROJECT_UI_PREFERENCES).singleOrNull(),
            projectOrder = rows(TABLE_PROJECT_ORDER).singleOrNull(),
            linkOrders = rows(TABLE_LINK_ORDERS),
            parkingTombstones = rows(TABLE_PARKING_TOMBSTONES),
            interactionOperations = rows(TABLE_INTERACTION_OPERATIONS),
            retiredInteractionOperations = rows(TABLE_RETIRED_INTERACTION_OPERATIONS),
        )
    }

    private fun writeDiff(
        database: SQLiteDatabase,
        tenantId: String,
        previous: EncodedSyncRows?,
        rows: EncodedSyncRows,
    ) {
        val metadata = linkedMapOf(
            META_SCHEMA_VERSION to rows.schemaVersion.toString(),
            META_PROJECTS_LOADED to rows.projectsLoaded.toString(),
            META_REENTRY_LOADED to rows.reentryLoaded.toString(),
            META_ACTIVE_PROJECTS_LOADED to rows.activeProjectsLoaded.toString(),
            META_ARCHIVED_PROJECTS_LOADED to rows.archivedProjectsLoaded.toString(),
            META_BLOCKS_LOADED to rows.blocksLoaded.toString(),
            META_PROJECT_DRAFTS_MIGRATED to rows.projectDraftsMigrated.toString(),
            META_CHANGE_CURSOR to rows.changeCursor,
            META_NOTICE to rows.notice,
        )
        val previousMetadata = previous?.let(::metadataRows).orEmpty()
        metadata.forEach { (name, value) ->
            if (previous == null || previousMetadata[name] != value) {
                upsertMetadata(database, name, value, previousKnown = previous != null)
            }
        }

        val tableRows = stateTableRows(rows)
        val previousTableRows = previous?.let(::stateTableRows).orEmpty()
        tableRows.forEach { (table, values) ->
            if (previous == null || previousTableRows[table] != values) {
                writeTableDiff(database, tenantId, table, previousTableRows[table], values)
            }
        }
    }

    private fun metadataRows(rows: EncodedSyncRows): Map<String, String?> = linkedMapOf(
        META_SCHEMA_VERSION to rows.schemaVersion.toString(),
        META_PROJECTS_LOADED to rows.projectsLoaded.toString(),
        META_REENTRY_LOADED to rows.reentryLoaded.toString(),
        META_ACTIVE_PROJECTS_LOADED to rows.activeProjectsLoaded.toString(),
        META_ARCHIVED_PROJECTS_LOADED to rows.archivedProjectsLoaded.toString(),
        META_BLOCKS_LOADED to rows.blocksLoaded.toString(),
        META_PROJECT_DRAFTS_MIGRATED to rows.projectDraftsMigrated.toString(),
        META_CHANGE_CURSOR to rows.changeCursor,
        META_NOTICE to rows.notice,
    )

    private fun stateTableRows(rows: EncodedSyncRows): Map<String, List<String>> {
        val pages = rows.projectPages.map { gson.fromJson(it, ProjectPage::class.java) }
        return linkedMapOf(
            TABLE_PROJECTS to rows.projects,
            TABLE_REENTRY to listOfNotNull(rows.reentry),
            TABLE_COMMANDS to rows.commands,
            TABLE_RETIRED_KEYS to rows.retiredIdempotencyKeys,
            TABLE_ACTIVE_PROJECTS to rows.activeProjects,
            TABLE_ARCHIVED_PROJECTS to rows.archivedProjects,
            TABLE_PROJECT_PAGES to pages.map { gson.toJson(it.project) },
            TABLE_PROJECT_NOTES to pages.map { gson.toJson(it.notes) },
            TABLE_PARKING_RECORDS to rows.parkingRecords,
            TABLE_PARKING_LOADED_PROJECTS to rows.parkingLoadedProjectIds,
            TABLE_STALE_PROJECT_PAGES to rows.stalePageIds,
            TABLE_BLOCKS to rows.blocks,
            TABLE_LOCAL_BLOCKS to rows.localBlocks,
            TABLE_LOCAL_BLOCK_OPERATIONS to rows.localBlockOperations,
            TABLE_PROJECT_LINKS to rows.projectLinks,
            TABLE_PROJECT_COVERS to rows.projectCovers,
            TABLE_PROJECT_NOTE_ITEMS to rows.projectNoteItems,
            TABLE_PROJECT_RESOURCE_LINKS to rows.projectResourceLinks,
            TABLE_PROJECT_ITEM_OPERATIONS to rows.projectItemOperations,
            TABLE_RETIRED_PROJECT_ITEM_OPERATIONS to rows.retiredProjectItemOperations,
            TABLE_PROJECT_COMMANDS to rows.projectCommands,
            TABLE_RETIRED_OPERATIONS to rows.retiredOperationIds,
            TABLE_PROVISIONAL_PROJECTS to rows.provisionalProjectIds,
            TABLE_PROJECT_ID_MAPPINGS to rows.projectIdMappings,
            TABLE_PROJECTS_AWAITING_NOTES_BASELINE to rows.projectsAwaitingNotesBaseline,
            TABLE_PROJECT_DRAFTS to listOfNotNull(rows.projectDrafts),
            TABLE_PROJECT_UI_PREFERENCES to listOfNotNull(rows.projectUiPreferences),
            TABLE_PROJECT_ORDER to listOfNotNull(rows.projectOrder),
            TABLE_LINK_ORDERS to rows.linkOrders,
            TABLE_PARKING_TOMBSTONES to rows.parkingTombstones,
            TABLE_INTERACTION_OPERATIONS to rows.interactionOperations,
            TABLE_RETIRED_INTERACTION_OPERATIONS to rows.retiredInteractionOperations,
        )
    }

    private fun upsertMetadata(database: SQLiteDatabase, name: String, value: String?, previousKnown: Boolean) {
        if (value == null) {
            database.delete(TABLE_METADATA, "$COLUMN_ID = ?", arrayOf(name))
            return
        }
        if (!previousKnown) {
            val current = database.query(
                TABLE_METADATA,
                arrayOf(COLUMN_VALUE),
                "$COLUMN_ID = ?",
                arrayOf(name),
                null,
                null,
                null,
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (current == value) return
        }
        database.insertWithOnConflict(
            TABLE_METADATA,
            null,
            ContentValues().apply { put(COLUMN_ID, name); put(COLUMN_VALUE, value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) }
    }

    private fun writeTableDiff(
        database: SQLiteDatabase,
        tenantId: String,
        table: String,
        previousValues: List<String>?,
        values: List<String>,
    ) {
        val desired = values.mapIndexed { position, value -> rowIdentity(table, value) to StoredRow(position, value) }
        require(desired.map { it.first }.distinct().size == desired.size) { "Duplicate $table row identity" }
        val desiredById = desired.toMap()
        val existing = previousValues?.mapIndexed { position, value ->
            rowIdentity(table, value) to StoredRow(position, value)
        }?.toMap() ?: database.query(
                table,
                arrayOf(COLUMN_ID, COLUMN_POSITION, COLUMN_PAYLOAD),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val payload = cursor.getBlob(2)
                        val value = if (table in SECRET_TABLES) {
                            decryptSecret(tenantId, table, payload)
                        } else {
                            payload.toString(Charsets.UTF_8)
                        }
                        put(id, StoredRow(cursor.getInt(1), value))
                    }
                }
            }
        (existing.keys - desiredById.keys).forEach { id ->
            database.delete(table, "$COLUMN_ID = ?", arrayOf(id))
        }
        desiredById.forEach { (id, row) ->
            if (existing[id] == row) return@forEach
            val payload = if (table in SECRET_TABLES) {
                encryptSecret(tenantId, table, row.value)
            } else {
                row.value.toByteArray(Charsets.UTF_8)
            }
            database.insertWithOnConflict(
                table,
                null,
                ContentValues().apply {
                    put(COLUMN_ID, id)
                    put(COLUMN_POSITION, row.position)
                    put(COLUMN_PAYLOAD, payload)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            ).also { check(it != -1L) }
        }
    }

    private fun rowIdentity(table: String, value: String): String = when (table) {
        TABLE_PROJECTS -> gson.fromJson(value, dev.blocks.android.mvp.Project::class.java).id
        TABLE_REENTRY -> SINGLETON_ID
        TABLE_COMMANDS -> gson.fromJson(value, QueuedCommand::class.java).idempotencyKey
        TABLE_ACTIVE_PROJECTS, TABLE_ARCHIVED_PROJECTS -> gson.fromJson(value, ProjectSummary::class.java).id
        TABLE_PROJECT_PAGES -> gson.fromJson(value, ProjectSummary::class.java).id
        TABLE_PROJECT_NOTES -> gson.fromJson(value, dev.blocks.android.project.ProjectNotes::class.java).projectId
        TABLE_PARKING_RECORDS -> gson.fromJson(value, ProjectParking::class.java).id
        TABLE_BLOCKS -> gson.fromJson(value, PhysicalBlock::class.java).id
        TABLE_LOCAL_BLOCKS -> gson.fromJson(value, LocalBlockRecord::class.java).clientBlockId
        TABLE_LOCAL_BLOCK_OPERATIONS -> gson.fromJson(value, LocalBlockOperation::class.java).operationId
        TABLE_PROJECT_LINKS -> gson.fromJson(value, dev.blocks.android.tag.ProjectLinkIdentity::class.java).projectId
        TABLE_PROJECT_COVERS -> gson.fromJson(value, ProjectCover::class.java).projectId
        TABLE_PROJECT_NOTE_ITEMS -> gson.fromJson(value, ProjectNoteItem::class.java).id
        TABLE_PROJECT_RESOURCE_LINKS -> gson.fromJson(value, ProjectResourceLink::class.java).id
        TABLE_PROJECT_ITEM_OPERATIONS -> gson.fromJson(value, ProjectItemMutation::class.java).operationId
        TABLE_PROJECT_COMMANDS -> gson.fromJson(value, ProjectMutation::class.java).operationId
        TABLE_PROJECT_ID_MAPPINGS -> gson.fromJson(value, ProjectIdentityMapping::class.java).provisionalId
        TABLE_PROJECT_DRAFTS, TABLE_PROJECT_UI_PREFERENCES -> SINGLETON_ID
        TABLE_PROJECT_ORDER -> SINGLETON_ID
        TABLE_LINK_ORDERS -> gson.fromJson(value, LinkOrderState::class.java).projectId
        TABLE_PARKING_TOMBSTONES -> gson.fromJson(value, ParkingTombstone::class.java).parkingId
        TABLE_INTERACTION_OPERATIONS -> gson.fromJson(value, InteractionMutation::class.java).operationId
        else -> value
    }

    private fun encryptSecret(tenantId: String, table: String, value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey(tenantId))
            updateAAD(secretAad(tenantId, table))
        }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun decryptSecret(tenantId: String, table: String, packed: ByteArray): String {
        require(packed.isNotEmpty()) { "Protected local secret is empty" }
        val ivSize = packed.first().toInt() and 0xff
        require(ivSize > 0 && packed.size > ivSize + 1) { "Protected local secret is malformed" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(tenantId), GCMParameterSpec(128, packed.copyOfRange(1, ivSize + 1)))
            updateAAD(secretAad(tenantId, table))
        }
        return cipher.doFinal(packed.copyOfRange(ivSize + 1, packed.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(tenantId: String): SecretKey {
        val alias = secretKeyAlias(tenantId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
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
    }

    private fun deleteSecretKey(tenantId: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            val alias = secretKeyAlias(tenantId)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private fun open(tenantId: String) = TenantDatabaseHelper(applicationContext, databaseName(tenantId))
    private fun databaseFile(tenantId: String): File = applicationContext.getDatabasePath(databaseName(tenantId))
    private fun databaseName(tenantId: String) = "$DATABASE_PREFIX${tenantDigest(tenantId)}.db"
    private fun secretKeyAlias(tenantId: String) = "$SECRET_KEY_PREFIX${tenantDigest(tenantId)}"
    private fun secretAad(tenantId: String, table: String) = "blocks-local-secret:$tenantId:$table".toByteArray()
    private fun tenantDigest(tenantId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(tenantId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class TenantDatabaseHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onConfigure(database: SQLiteDatabase) {
            database.setForeignKeyConstraintsEnabled(true)
            database.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1)
            }
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $TABLE_METADATA ($COLUMN_ID TEXT PRIMARY KEY NOT NULL, $COLUMN_VALUE TEXT NOT NULL)",
            )
            STATE_TABLES.forEach { table ->
                database.execSQL(
                    "CREATE TABLE $table (" +
                        "$COLUMN_ID TEXT PRIMARY KEY NOT NULL, " +
                        "$COLUMN_POSITION INTEGER NOT NULL, " +
                        "$COLUMN_PAYLOAD BLOB NOT NULL)",
                )
            }
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1..2 && newVersion == 3) {
                "No normalized local-state migration from $oldVersion to $newVersion"
            }
            val additions = buildList {
                if (oldVersion < 2) addAll(PROJECT_ITEM_TABLES)
                addAll(INTERACTION_TABLES)
            }
            additions.forEach { table ->
                database.execSQL(
                    "CREATE TABLE $table (" +
                        "$COLUMN_ID TEXT PRIMARY KEY NOT NULL, " +
                        "$COLUMN_POSITION INTEGER NOT NULL, " +
                        "$COLUMN_PAYLOAD BLOB NOT NULL)",
                )
            }
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Local-state downgrade from $oldVersion to $newVersion is not supported")
        }
    }

    private data class StoredRow(val position: Int, val value: String)

    internal companion object {
        const val DATABASE_VERSION = 3
        const val DATABASE_PREFIX = "blocks_local_v10_"
        const val SECRET_KEY_PREFIX = "blocks-local-secrets-v1-"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TABLE_METADATA = "local_metadata"
        const val TABLE_PROJECTS = "legacy_projects"
        const val TABLE_REENTRY = "legacy_reentry"
        const val TABLE_COMMANDS = "legacy_commands"
        const val TABLE_RETIRED_KEYS = "legacy_retired_keys"
        const val TABLE_ACTIVE_PROJECTS = "projects_active"
        const val TABLE_ARCHIVED_PROJECTS = "projects_archived"
        const val TABLE_PROJECT_PAGES = "project_pages"
        const val TABLE_PROJECT_NOTES = "project_notes"
        const val TABLE_PARKING_RECORDS = "project_parking"
        const val TABLE_PARKING_LOADED_PROJECTS = "parking_loaded_projects"
        const val TABLE_STALE_PROJECT_PAGES = "stale_project_pages"
        const val TABLE_BLOCKS = "service_blocks"
        const val TABLE_LOCAL_BLOCKS = "protected_local_blocks"
        const val TABLE_LOCAL_BLOCK_OPERATIONS = "protected_block_operations"
        const val TABLE_PROJECT_LINKS = "project_links"
        const val TABLE_PROJECT_COVERS = "project_covers"
        const val TABLE_PROJECT_NOTE_ITEMS = "project_note_items"
        const val TABLE_PROJECT_RESOURCE_LINKS = "project_resource_links"
        const val TABLE_PROJECT_ITEM_OPERATIONS = "project_item_operations"
        const val TABLE_RETIRED_PROJECT_ITEM_OPERATIONS = "retired_project_item_operations"
        const val TABLE_PROJECT_COMMANDS = "sync_operations"
        const val TABLE_RETIRED_OPERATIONS = "retired_operations"
        const val TABLE_PROVISIONAL_PROJECTS = "provisional_projects"
        const val TABLE_PROJECT_ID_MAPPINGS = "project_identity_mappings"
        const val TABLE_PROJECTS_AWAITING_NOTES_BASELINE = "projects_awaiting_notes_baseline"
        const val TABLE_PROJECT_DRAFTS = "legacy_project_drafts"
        const val TABLE_PROJECT_UI_PREFERENCES = "project_ui_preferences"
        const val TABLE_PROJECT_ORDER = "project_custom_order"
        const val TABLE_LINK_ORDERS = "project_link_orders"
        const val TABLE_PARKING_TOMBSTONES = "project_parking_tombstones"
        const val TABLE_INTERACTION_OPERATIONS = "interaction_operations"
        const val TABLE_RETIRED_INTERACTION_OPERATIONS = "retired_interaction_operations"
        const val COLUMN_ID = "record_id"
        const val COLUMN_POSITION = "position"
        const val COLUMN_PAYLOAD = "payload"
        const val COLUMN_VALUE = "value"
        const val SINGLETON_ID = "singleton"
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_PROJECTS_LOADED = "legacy_projects_loaded"
        const val META_REENTRY_LOADED = "legacy_reentry_loaded"
        const val META_CHANGE_CURSOR = "change_cursor"
        const val META_NOTICE = "notice"
        const val META_ACTIVE_PROJECTS_LOADED = "active_projects_loaded"
        const val META_ARCHIVED_PROJECTS_LOADED = "archived_projects_loaded"
        const val META_BLOCKS_LOADED = "blocks_loaded"
        const val META_PROJECT_DRAFTS_MIGRATED = "project_drafts_migrated"
        const val META_AUTHORITATIVE = "authoritative_cutover"
        val SECRET_TABLES = setOf(TABLE_LOCAL_BLOCKS, TABLE_LOCAL_BLOCK_OPERATIONS)
        val PROJECT_ITEM_TABLES = listOf(
            TABLE_PROJECT_NOTE_ITEMS,
            TABLE_PROJECT_RESOURCE_LINKS,
            TABLE_PROJECT_ITEM_OPERATIONS,
            TABLE_RETIRED_PROJECT_ITEM_OPERATIONS,
        )
        val INTERACTION_TABLES = listOf(
            TABLE_PROJECT_ORDER,
            TABLE_LINK_ORDERS,
            TABLE_PARKING_TOMBSTONES,
            TABLE_INTERACTION_OPERATIONS,
            TABLE_RETIRED_INTERACTION_OPERATIONS,
        )
        val STATE_TABLES = listOf(
            TABLE_PROJECTS,
            TABLE_REENTRY,
            TABLE_COMMANDS,
            TABLE_RETIRED_KEYS,
            TABLE_ACTIVE_PROJECTS,
            TABLE_ARCHIVED_PROJECTS,
            TABLE_PROJECT_PAGES,
            TABLE_PROJECT_NOTES,
            TABLE_PARKING_RECORDS,
            TABLE_PARKING_LOADED_PROJECTS,
            TABLE_STALE_PROJECT_PAGES,
            TABLE_BLOCKS,
            TABLE_LOCAL_BLOCKS,
            TABLE_LOCAL_BLOCK_OPERATIONS,
            TABLE_PROJECT_LINKS,
            TABLE_PROJECT_COVERS,
            TABLE_PROJECT_NOTE_ITEMS,
            TABLE_PROJECT_RESOURCE_LINKS,
            TABLE_PROJECT_ITEM_OPERATIONS,
            TABLE_RETIRED_PROJECT_ITEM_OPERATIONS,
            TABLE_PROJECT_COMMANDS,
            TABLE_RETIRED_OPERATIONS,
            TABLE_PROVISIONAL_PROJECTS,
            TABLE_PROJECT_ID_MAPPINGS,
            TABLE_PROJECTS_AWAITING_NOTES_BASELINE,
            TABLE_PROJECT_DRAFTS,
            TABLE_PROJECT_UI_PREFERENCES,
            TABLE_PROJECT_ORDER,
            TABLE_LINK_ORDERS,
            TABLE_PARKING_TOMBSTONES,
            TABLE_INTERACTION_OPERATIONS,
            TABLE_RETIRED_INTERACTION_OPERATIONS,
        )
    }
}

internal enum class MigrationCheckpoint {
    NORMALIZED_COMMITTED,
    VERIFIED_MARKER_COMMITTED,
}
