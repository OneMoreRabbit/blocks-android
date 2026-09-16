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
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectResourceUri
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.project.legacyNoteItemIdentity
import dev.blocks.android.project.noteItemDraftKey
import dev.blocks.android.mvp.NoteItemDraft
import dev.blocks.android.project.ensureProjectLinks
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectKeyFactory
import dev.blocks.android.tag.ProjectLinkIdentity
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
    val activeProjectsLoaded: Boolean = false,
    val archivedProjectsLoaded: Boolean = false,
    val blocksLoaded: Boolean = false,
    val activeProjects: List<String> = emptyList(),
    val archivedProjects: List<String> = emptyList(),
    val projectPages: List<String> = emptyList(),
    val parkingRecords: List<String> = emptyList(),
    val parkingLoadedProjectIds: List<String> = emptyList(),
    val stalePageIds: List<String> = emptyList(),
    val blocks: List<String> = emptyList(),
    val localBlocks: List<String> = emptyList(),
    val localBlockOperations: List<String> = emptyList(),
    val projectLinks: List<String> = emptyList(),
    val projectCovers: List<String> = emptyList(),
    val projectNoteItems: List<String> = emptyList(),
    val projectResourceLinks: List<String> = emptyList(),
    val projectItemOperations: List<String> = emptyList(),
    val retiredProjectItemOperations: List<String> = emptyList(),
    val projectCommands: List<String> = emptyList(),
    val retiredOperationIds: List<String> = emptyList(),
    val provisionalProjectIds: List<String> = emptyList(),
    val projectIdMappings: List<String> = emptyList(),
    val projectsAwaitingNotesBaseline: List<String> = emptyList(),
    val projectDrafts: String? = null,
    val projectDraftsMigrated: Boolean = false,
    val projectUiPreferences: String? = null,
)

internal class SyncStateRowCodec(
    private val gson: Gson = Gson(),
    private val projectKeyFactory: ProjectKeyFactory = ProjectKeyFactory(),
) {
    fun encode(state: TenantSyncState): EncodedSyncRows {
        validateProjects(state.projects)
        validateCommands(state.commands)
        val retiredKeys = state.retiredIdempotencyKeys.distinct()
        require(state.commands.none { it.idempotencyKey in retiredKeys }) {
            "A queued idempotency key is already retired"
        }
        val projectCache = state.projectCache.ensureProjectLinks(projectKeyFactory)
        validateProjectCache(projectCache)
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
            activeProjectsLoaded = projectCache.activeProjectsLoaded,
            archivedProjectsLoaded = projectCache.archivedProjectsLoaded,
            blocksLoaded = projectCache.blocksLoaded,
            activeProjects = projectCache.activeProjects.map(gson::toJson),
            archivedProjects = projectCache.archivedProjects.map(gson::toJson),
            projectPages = projectCache.pages.map(gson::toJson),
            parkingRecords = projectCache.parkingRecords.map(gson::toJson),
            parkingLoadedProjectIds = projectCache.parkingLoadedProjectIds.distinct(),
            stalePageIds = projectCache.stalePageIds.distinct(),
            blocks = projectCache.blocks.map(gson::toJson),
            localBlocks = projectCache.localBlocks.map(gson::toJson),
            localBlockOperations = projectCache.localBlockOperations.map(gson::toJson),
            projectLinks = projectCache.projectLinks.map(gson::toJson),
            projectCovers = projectCache.projectCovers.map(gson::toJson),
            projectNoteItems = projectCache.noteItems.map(gson::toJson),
            projectResourceLinks = projectCache.resourceLinks.map(gson::toJson),
            projectItemOperations = projectCache.itemOperations.map(gson::toJson),
            retiredProjectItemOperations = projectCache.retiredItemOperationIds,
            projectCommands = projectCache.commands.map(gson::toJson),
            retiredOperationIds = projectCache.retiredOperationIds.distinct(),
            provisionalProjectIds = projectCache.provisionalProjectIds.distinct(),
            projectIdMappings = projectCache.projectIdMappings.map(gson::toJson),
            projectsAwaitingNotesBaseline = projectCache.projectsAwaitingNotesBaseline.distinct(),
            projectDrafts = gson.toJson(state.projectDrafts),
            projectDraftsMigrated = state.projectDraftsMigrated,
            projectUiPreferences = gson.toJson(state.projectUiPreferences),
        )
    }

    fun decode(rows: EncodedSyncRows): TenantSyncState {
        require(rows.schemaVersion in PILOT_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
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
        var projectCache = if (rows.schemaVersion == PILOT_SCHEMA_VERSION) {
            ProjectCacheState()
        } else {
            ProjectCacheState(
                activeProjects = rows.activeProjects.map { gson.fromJson(it, ProjectSummary::class.java) },
                activeProjectsLoaded = rows.activeProjectsLoaded,
                archivedProjects = rows.archivedProjects.map { gson.fromJson(it, ProjectSummary::class.java) },
                archivedProjectsLoaded = rows.archivedProjectsLoaded,
                pages = rows.projectPages.map { gson.fromJson(it, ProjectPage::class.java) },
                parkingRecords = if (rows.schemaVersion >= PARKING_HISTORY_SCHEMA_VERSION) {
                    rows.parkingRecords.map { gson.fromJson(it, ProjectParking::class.java) }
                } else {
                    rows.projectPages.map { gson.fromJson(it, ProjectPage::class.java) }
                        .mapNotNull { it.newestParking }
                        .distinctBy { it.id }
                },
                parkingLoadedProjectIds = if (rows.schemaVersion >= PARKING_HISTORY_SCHEMA_VERSION) {
                    rows.parkingLoadedProjectIds.distinct()
                } else {
                    emptyList()
                },
                stalePageIds = rows.stalePageIds.distinct(),
                blocks = rows.blocks.map { gson.fromJson(it, PhysicalBlock::class.java) },
                blocksLoaded = rows.blocksLoaded,
                localBlocks = if (rows.schemaVersion >= LOCAL_TAG_SCHEMA_VERSION) {
                    rows.localBlocks.map { gson.fromJson(it, LocalBlockRecord::class.java) }
                } else {
                    emptyList()
                },
                localBlockOperations = if (rows.schemaVersion >= LOCAL_TAG_SCHEMA_VERSION) {
                    rows.localBlockOperations.map { gson.fromJson(it, LocalBlockOperation::class.java) }
                } else {
                    emptyList()
                },
                projectLinks = if (rows.schemaVersion >= PROJECT_LINK_SCHEMA_VERSION) {
                    rows.projectLinks.map { gson.fromJson(it, ProjectLinkIdentity::class.java) }
                } else {
                    emptyList()
                },
                projectCovers = if (rows.schemaVersion >= PROJECT_COVER_SCHEMA_VERSION) {
                    rows.projectCovers.map { gson.fromJson(it, ProjectCover::class.java) }
                } else {
                    emptyList()
                },
                noteItems = if (rows.schemaVersion >= PROJECT_ITEM_SCHEMA_VERSION) {
                    rows.projectNoteItems.map { gson.fromJson(it, ProjectNoteItem::class.java) }
                } else {
                    emptyList()
                },
                resourceLinks = if (rows.schemaVersion >= PROJECT_ITEM_SCHEMA_VERSION) {
                    rows.projectResourceLinks.map { gson.fromJson(it, ProjectResourceLink::class.java) }
                } else {
                    emptyList()
                },
                itemOperations = if (rows.schemaVersion >= PROJECT_ITEM_SCHEMA_VERSION) {
                    rows.projectItemOperations.map { gson.fromJson(it, ProjectItemMutation::class.java) }
                } else {
                    emptyList()
                },
                retiredItemOperationIds = if (rows.schemaVersion >= PROJECT_ITEM_SCHEMA_VERSION) {
                    rows.retiredProjectItemOperations.distinct()
                } else {
                    emptyList()
                },
                commands = rows.projectCommands.map {
                    gson.fromJson(it, ProjectMutation::class.java).migrateFrom(rows.schemaVersion)
                },
                retiredOperationIds = rows.retiredOperationIds.distinct(),
                provisionalProjectIds = if (rows.schemaVersion >= LOCAL_FIRST_SCHEMA_VERSION) {
                    rows.provisionalProjectIds.distinct()
                } else {
                    emptyList()
                },
                projectIdMappings = if (rows.schemaVersion >= LOCAL_FIRST_SCHEMA_VERSION) {
                    rows.projectIdMappings.map { gson.fromJson(it, ProjectIdentityMapping::class.java) }
                } else {
                    emptyList()
                },
                projectsAwaitingNotesBaseline = if (rows.schemaVersion >= LOCAL_FIRST_SCHEMA_VERSION) {
                    rows.projectsAwaitingNotesBaseline.distinct()
                } else {
                    emptyList()
                },
            ).ensureProjectLinks(projectKeyFactory)
        }
        if (rows.schemaVersion < PROJECT_ITEM_SCHEMA_VERSION) {
            projectCache = projectCache.copy(
                noteItems = projectCache.pages.mapNotNull { page ->
                    page.notes.body.takeIf(String::isNotEmpty)?.let { body ->
                        ProjectNoteItem(
                            id = legacyNoteItemIdentity(page.project.id),
                            projectId = page.project.id,
                            body = body,
                            pinned = false,
                            authoredByUserId = "legacy",
                            createdAt = page.notes.updatedAt,
                            updatedAt = page.notes.updatedAt,
                            rowVersion = page.notes.rowVersion,
                            legacySingleton = true,
                        )
                    }
                },
            )
        }
        validateProjectCache(projectCache)
        val decodedDrafts = if (rows.schemaVersion >= LOCAL_FIRST_SCHEMA_VERSION) {
            rows.projectDrafts?.let { gson.fromJson(it, FormDrafts::class.java) } ?: FormDrafts()
        } else {
            FormDrafts()
        }
        val migratedDrafts = if (rows.schemaVersion < PROJECT_ITEM_SCHEMA_VERSION) {
            decodedDrafts.copy(
                projectNoteItems = decodedDrafts.projectNoteItems + decodedDrafts.projectNotes.mapKeys { (projectId, _) ->
                    noteItemDraftKey(projectId, legacyNoteItemIdentity(projectId))
                }.mapValues { (_, body) -> NoteItemDraft(body) },
                projectNotes = emptyMap(),
            )
        } else {
            decodedDrafts
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
            projectCache = projectCache,
            projectDrafts = migratedDrafts,
            projectDraftsMigrated = rows.schemaVersion >= LOCAL_FIRST_SCHEMA_VERSION && rows.projectDraftsMigrated,
            projectUiPreferences = if (rows.schemaVersion >= STANDALONE_SCHEMA_VERSION) {
                rows.projectUiPreferences?.let { gson.fromJson(it, ProjectUiPreferences::class.java) }
                    ?: ProjectUiPreferences()
            } else {
                ProjectUiPreferences()
            },
        )
    }

    private fun ProjectMutation.migrateFrom(schemaVersion: Int): ProjectMutation =
        if (schemaVersion >= STANDALONE_SCHEMA_VERSION) {
            this
        } else {
            copy(
                clientResourceId = null,
                contractVersion = "0.3",
            )
        }

    companion object {
        const val PILOT_SCHEMA_VERSION = 1
        const val LOCAL_FIRST_SCHEMA_VERSION = 4
        const val STANDALONE_SCHEMA_VERSION = 5
        const val PARKING_HISTORY_SCHEMA_VERSION = 6
        const val LOCAL_TAG_SCHEMA_VERSION = 7
        const val PROJECT_LINK_SCHEMA_VERSION = 8
        const val PROJECT_COVER_SCHEMA_VERSION = 9
        const val PROJECT_ITEM_SCHEMA_VERSION = 10
        const val CURRENT_SCHEMA_VERSION = PROJECT_ITEM_SCHEMA_VERSION
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

private fun validateProjectCache(cache: ProjectCacheState) {
    require(cache.activeProjects.map { it.id }.distinct().size == cache.activeProjects.size) {
        "Cached active project identifiers are not unique"
    }
    require(cache.archivedProjects.map { it.id }.distinct().size == cache.archivedProjects.size) {
        "Cached archived project identifiers are not unique"
    }
    require((cache.activeProjects.map { it.id }.toSet() intersect cache.archivedProjects.map { it.id }.toSet()).isEmpty()) {
        "A cached project cannot be both active and archived"
    }
    require(cache.pages.map { it.project.id }.distinct().size == cache.pages.size) {
        "Cached project page identifiers are not unique"
    }
    require(cache.parkingRecords.map { it.id }.distinct().size == cache.parkingRecords.size) {
        "Cached parking-record identifiers are not unique"
    }
    require(cache.parkingLoadedProjectIds.distinct().size == cache.parkingLoadedProjectIds.size) {
        "Projects with loaded parking history are not unique"
    }
    require(cache.stalePageIds.distinct().size == cache.stalePageIds.size) {
        "Stale project page identifiers are not unique"
    }
    require(cache.blocks.map { it.id }.distinct().size == cache.blocks.size) {
        "Cached block identifiers are not unique"
    }
    require(cache.localBlocks.map { it.clientBlockId }.distinct().size == cache.localBlocks.size) {
        "Local block client identifiers are not unique"
    }
    require(cache.localBlocks.map { it.token.encoded }.distinct().size == cache.localBlocks.size) {
        "Local tag tokens are not unique"
    }
    cache.localBlocks.forEach { block ->
        require(OpaqueTagToken.parse(block.token.encoded) == block.token) { "A local tag token is not canonical" }
        require(block.clientBlockId.isNotBlank()) { "A local block client identity is missing" }
        require(block.label.isNotBlank()) { "A local block label is missing" }
        when (block.state) {
            LocalBlockState.AWAITING_WRITE,
            LocalBlockState.WRITE_UNCERTAIN,
            -> {
                require(block.intendedProjectId != null && block.boundProjectId == null) {
                    "A pending local block must retain intent without claiming a binding"
                }
                require(!block.registrationOperationId.isNullOrBlank()) {
                    "A pending local block registration identity is missing"
                }
                require(!block.initialBindingOperationId.isNullOrBlank()) {
                    "A pending local block binding identity is missing"
                }
            }
            LocalBlockState.ACTIVE -> require(block.boundProjectId != null) {
                "An active local block must be bound"
            }
            LocalBlockState.UNBOUND -> require(block.boundProjectId == null) {
                "An unbound local block cannot retain a binding"
            }
        }
    }
    require(cache.projectLinks.map { it.projectId }.distinct().size == cache.projectLinks.size) {
        "A project has more than one local navigation key"
    }
    require(cache.projectLinks.map { it.key }.distinct().size == cache.projectLinks.size) {
        "Local project navigation keys are not unique"
    }
    cache.projectLinks.forEach { identity ->
        require(identity.projectId.isNotBlank()) { "A project navigation identity has no project" }
        require(ProjectKey.parse(identity.key.encoded) == identity.key) {
            "A local project navigation key is not canonical"
        }
    }
    val knownProjectIds = (
        cache.activeProjects.map { it.id } +
            cache.archivedProjects.map { it.id } +
            cache.pages.map { it.project.id }
        ).toSet()
    require(knownProjectIds.all { projectId -> cache.projectLinks.any { it.projectId == projectId } }) {
        "A cached project has no local navigation key"
    }
    require(cache.projectCovers.map { it.projectId }.distinct().size == cache.projectCovers.size) {
        "A project has more than one local cover"
    }
    cache.projectCovers.forEach { cover ->
        require(
            cover.coverId.matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
            ),
        ) { "A local cover identity is invalid" }
        require(cover.tenantId.isNotBlank()) { "A local cover tenant is missing" }
        require(cover.projectId in knownProjectIds) { "A local cover references an unknown project" }
        require(cover.mediaType == "image/jpeg") { "A local cover media type is invalid" }
        require(cover.displayContentRef.isNotBlank() && cover.thumbnailContentRef.isNotBlank()) {
            "A local cover content reference is missing"
        }
        require(cover.displayWidth > 0 && cover.displayHeight > 0) { "A local display cover has invalid bounds" }
        require(cover.thumbnailWidth > 0 && cover.thumbnailHeight > 0) {
            "A local cover thumbnail has invalid bounds"
        }
        require(cover.displayByteSize > 0 && cover.thumbnailByteSize > 0) { "A local cover size is invalid" }
        require(cover.displayDigest.matches(Regex("[0-9a-f]{64}"))) { "A display cover digest is invalid" }
        require(cover.thumbnailDigest.matches(Regex("[0-9a-f]{64}"))) { "A thumbnail digest is invalid" }
        require(cover.contentDigest.matches(Regex("[0-9a-f]{64}"))) { "A local cover digest is invalid" }
        require(cover.revision > 0) { "A local cover revision is invalid" }
        require(cover.createdAt.isNotBlank() && cover.updatedAt.isNotBlank()) { "A local cover timestamp is missing" }
    }
    require(cache.noteItems.map { it.id }.distinct().size == cache.noteItems.size) {
        "Project note item identifiers are not unique"
    }
    cache.noteItems.forEach { note ->
        require(note.id.isCanonicalUuid()) { "A project note item identity is invalid" }
        require(note.projectId in knownProjectIds) { "A project note item references an unknown project" }
        require(note.body.length <= 100_000) { "A project note item exceeds the local body limit" }
        require(note.rowVersion >= 0) { "A project note item row version is invalid" }
    }
    require(cache.resourceLinks.map { it.id }.distinct().size == cache.resourceLinks.size) {
        "Project resource link identifiers are not unique"
    }
    cache.resourceLinks.forEach { link ->
        require(link.id.isCanonicalUuid()) { "A project resource link identity is invalid" }
        require(link.projectId in knownProjectIds) { "A project resource link references an unknown project" }
        require(link.title.isNotBlank()) { "A project resource link title is missing" }
        require(link.title.length <= 200) { "A project resource link title exceeds the local limit" }
        require(runCatching { ProjectResourceUri.validate(link.uri) }.getOrNull() == link.uri) {
            "A project resource link URI is unsafe or non-canonical"
        }
        require(link.rowVersion >= 0) { "A project resource link row version is invalid" }
    }
    require(cache.itemOperations.map { it.operationId }.distinct().size == cache.itemOperations.size) {
        "Project item operation identifiers are not unique"
    }
    require(cache.itemOperations.none { it.operationId in cache.retiredItemOperationIds }) {
        "A pending project item operation is already retired"
    }
    val itemOperationPositions = cache.itemOperations.mapIndexed { index, operation ->
        operation.operationId to index
    }.toMap()
    cache.itemOperations.forEachIndexed { index, operation ->
        require(operation.operationId.isNotBlank()) { "A project item operation identity is missing" }
        require(operation.projectId in knownProjectIds) { "A project item operation references an unknown project" }
        require(operation.contractVersion == "0.6") { "A project item operation has the wrong contract boundary" }
        operation.dependsOnOperationId?.let { dependency ->
            val pendingPosition = itemOperationPositions[dependency]
            val validPending = pendingPosition != null && pendingPosition < index
            val validLegacyProjectCreate = cache.commands.any {
                it.operationId == dependency && it.type == dev.blocks.android.project.ProjectMutationType.CREATE_PROJECT
            }
            require(validPending || validLegacyProjectCreate) {
                "A project item operation dependency must precede the dependent operation"
            }
        }
    }
    require(cache.localBlockOperations.map { it.operationId }.distinct().size == cache.localBlockOperations.size) {
        "Local block operation identities are not unique"
    }
    val localBlockIds = cache.localBlocks.map { it.clientBlockId }.toSet()
    val localOperationPositions = cache.localBlockOperations.mapIndexed { index, operation ->
        operation.operationId to index
    }.toMap()
    cache.localBlockOperations.forEachIndexed { index, operation ->
        require(operation.clientBlockId in localBlockIds) { "A local block operation has no local block" }
        require(operation.contractVersion == "0.5") { "A local block operation has an unexpected contract version" }
        operation.dependsOnOperationId?.let { dependency ->
            require(localOperationPositions[dependency]?.let { it < index } == true) {
                "A local block operation dependency must precede the dependent operation"
            }
        }
        when (operation.type) {
            LocalBlockOperationType.REGISTER_BLOCK -> {
                require(operation.token != null && !operation.label.isNullOrBlank()) {
                    "A local block registration payload is incomplete"
                }
                require(operation.projectId == null && operation.dependsOnOperationId == null) {
                    "A local block registration cannot depend on a binding"
                }
            }
            LocalBlockOperationType.BIND_BLOCK -> require(!operation.projectId.isNullOrBlank()) {
                "A local block binding project is missing"
            }
            LocalBlockOperationType.UNBIND_BLOCK -> require(operation.projectId == null) {
                "A local block unbind cannot carry a project"
            }
        }
    }
    val retired = cache.retiredOperationIds.distinct()
    require(cache.commands.none { it.operationId in retired }) {
        "A queued project operation is already retired"
    }
    require(cache.commands.map { it.operationId }.distinct().size == cache.commands.size) {
        "Queued project operation identifiers are not unique"
    }
    val commandPositions = cache.commands.mapIndexed { index, command -> command.operationId to index }.toMap()
    cache.commands.forEachIndexed { index, command ->
        command.dependsOnOperationId?.let { dependency ->
            require(dependency != command.operationId) { "A project operation cannot depend on itself" }
            require(commandPositions[dependency]?.let { it < index } != false) {
                "A project operation dependency must precede the dependent operation"
            }
        }
    }
    val retryKeys = cache.commands.mapNotNull { it.idempotencyKey }
    require(retryKeys.distinct().size == retryKeys.size) {
        "Queued project idempotency keys are not unique"
    }
    cache.commands.forEach(::validateProjectMutation)
    require(cache.provisionalProjectIds.distinct().size == cache.provisionalProjectIds.size) {
        "Provisional project identifiers are not unique"
    }
    require(cache.projectIdMappings.map { it.provisionalId }.distinct().size == cache.projectIdMappings.size) {
        "A provisional project has more than one server identity"
    }
    require(cache.projectIdMappings.map { it.serverId }.distinct().size == cache.projectIdMappings.size) {
        "A server project identity is mapped from more than one provisional project"
    }
    require(cache.projectIdMappings.none { it.provisionalId == it.serverId }) {
        "A provisional project identity cannot map to itself"
    }
    require(cache.projectsAwaitingNotesBaseline.distinct().size == cache.projectsAwaitingNotesBaseline.size) {
        "Projects awaiting a notes baseline are not unique"
    }
}

private fun String.isCanonicalUuid(): Boolean = runCatching {
    java.util.UUID.fromString(this).toString() == lowercase()
}.getOrDefault(false)

private fun validateProjectMutation(command: ProjectMutation) {
    require(command.operationId.isNotBlank()) { "Queued project operation identity is missing" }
    require(command.targetId.isNotBlank()) { "Queued project operation target is missing" }
    when (command.type) {
        ProjectMutationType.CREATE_PROJECT -> {
            require(!command.projectName.isNullOrBlank()) { "Queued project name is missing" }
            require(!command.idempotencyKey.isNullOrBlank()) { "Queued project creation retry identity is missing" }
            if (command.contractVersion == "0.4") {
                require(!command.clientResourceId.isNullOrBlank()) {
                    "Queued project creation client identity is missing"
                }
            }
        }
        ProjectMutationType.RENAME_PROJECT -> {
            require(!command.projectName.isNullOrBlank()) { "Queued project rename is missing" }
            require(command.ifMatch != null || command.dependsOnOperationId != null) {
                "Queued project rename has neither If-Match nor a dependency"
            }
        }
        ProjectMutationType.ARCHIVE_PROJECT,
        ProjectMutationType.REOPEN_PROJECT,
        -> require(command.ifMatch != null || command.dependsOnOperationId != null) {
            "Queued project lifecycle has neither If-Match nor a dependency"
        }
        ProjectMutationType.SAVE_NOTES -> {
            require(command.notesBody != null) { "Queued project notes body is missing" }
            require(command.ifMatch != null || command.dependsOnOperationId != null) {
                "Queued project notes has neither If-Match nor a dependency"
            }
        }
        ProjectMutationType.ADD_PARKING -> {
            requireNotNull(command.parking) { "Queued project parking is missing" }
            require(!command.idempotencyKey.isNullOrBlank()) { "Queued project parking retry identity is missing" }
            if (command.contractVersion == "0.4") {
                require(!command.clientResourceId.isNullOrBlank()) {
                    "Queued project parking client identity is missing"
                }
                require(!command.localCreatedAt.isNullOrBlank()) {
                    "Queued project parking authored time is missing"
                }
            }
        }
        ProjectMutationType.BIND_BLOCK -> {
            require(!command.projectId.isNullOrBlank()) { "Queued block project is missing" }
            require(command.idempotencyKey == command.operationId) {
                "Queued block binding must retain its original retry identity"
            }
        }
        ProjectMutationType.UNBIND_BLOCK -> Unit
    }
}

private fun requireCoverTenantScope(tenantId: String, state: TenantSyncState) {
    require(state.projectCache.projectCovers.all { it.tenantId == tenantId }) {
        "Encrypted cover metadata crosses a tenant boundary"
    }
}

/** Released 0.7.0 encrypted snapshot reader; new writes use [AndroidSyncStateStore]. */
/** Read-only compatibility implementation retained for the forward migration from 0.7.0. */
internal class LegacyEncryptedSyncStateStore(context: Context) : SyncStateStore {
    private val applicationContext = context.applicationContext
    private val rowCodec = SyncStateRowCodec()
    private val legacyCodec = LegacySyncStateJsonCodec()

    @Synchronized
    override fun loadSyncState(tenantId: String): TenantSyncState {
        if (!databaseFile(tenantId).exists()) return migrateLegacyState(tenantId).also {
            requireCoverTenantScope(tenantId, it)
        }
        val secretKey = requireNotNull(existingKey(databaseKeyAlias(tenantId))) {
            "Encrypted sync database key is unavailable"
        }
        val helper = TenantDatabaseHelper(applicationContext, databaseName(tenantId))
        return try {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                val rows = readStateRows(database, tenantId, secretKey)
                val state = rowCodec.decode(rows).also { requireCoverTenantScope(tenantId, it) }
                if (rows.schemaVersion < SyncStateRowCodec.CURRENT_SCHEMA_VERSION) {
                    replaceStateRows(database, tenantId, secretKey, rowCodec.encode(state))
                }
                state.also { database.setTransactionSuccessful() }
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
        requireCoverTenantScope(tenantId, state)
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
                replaceStateRows(database, tenantId, secretKey, rows)
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

    private fun readStateRows(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
    ): EncodedSyncRows {
        val schemaVersion = readMetadata(database, tenantId, secretKey, META_SCHEMA_VERSION).toInt()
        return EncodedSyncRows(
            schemaVersion = schemaVersion,
            projectsLoaded = readMetadata(database, tenantId, secretKey, META_PROJECTS_LOADED).toBooleanStrict(),
            reentryLoaded = readMetadata(database, tenantId, secretKey, META_REENTRY_LOADED).toBooleanStrict(),
            changeCursor = readOptionalMetadata(database, tenantId, secretKey, META_CHANGE_CURSOR),
            notice = readOptionalMetadata(database, tenantId, secretKey, META_NOTICE),
            projects = readRows(database, tenantId, secretKey, TABLE_PROJECTS),
            reentry = readRows(database, tenantId, secretKey, TABLE_REENTRY).singleOrNull(),
            commands = readRows(database, tenantId, secretKey, TABLE_COMMANDS),
            retiredIdempotencyKeys = readRows(database, tenantId, secretKey, TABLE_RETIRED_KEYS),
            activeProjectsLoaded = readOptionalMetadata(
                database,
                tenantId,
                secretKey,
                META_ACTIVE_PROJECTS_LOADED,
            )?.toBooleanStrict() ?: false,
            archivedProjectsLoaded = readOptionalMetadata(
                database,
                tenantId,
                secretKey,
                META_ARCHIVED_PROJECTS_LOADED,
            )?.toBooleanStrict() ?: false,
            blocksLoaded = readOptionalMetadata(
                database,
                tenantId,
                secretKey,
                META_BLOCKS_LOADED,
            )?.toBooleanStrict() ?: false,
            activeProjects = readRows(database, tenantId, secretKey, TABLE_ACTIVE_PROJECTS),
            archivedProjects = readRows(database, tenantId, secretKey, TABLE_ARCHIVED_PROJECTS),
            projectPages = readRows(database, tenantId, secretKey, TABLE_PROJECT_PAGES),
            parkingRecords = readRows(database, tenantId, secretKey, TABLE_PARKING_RECORDS),
            parkingLoadedProjectIds = readRows(
                database,
                tenantId,
                secretKey,
                TABLE_PARKING_LOADED_PROJECTS,
            ),
            stalePageIds = readRows(database, tenantId, secretKey, TABLE_STALE_PROJECT_PAGES),
            blocks = readRows(database, tenantId, secretKey, TABLE_BLOCKS),
            localBlocks = readRows(database, tenantId, secretKey, TABLE_LOCAL_BLOCKS),
            localBlockOperations = readRows(database, tenantId, secretKey, TABLE_LOCAL_BLOCK_OPERATIONS),
            projectLinks = readRows(database, tenantId, secretKey, TABLE_PROJECT_LINKS),
            projectCovers = readRows(database, tenantId, secretKey, TABLE_PROJECT_COVERS),
            projectNoteItems = readRows(database, tenantId, secretKey, TABLE_PROJECT_NOTE_ITEMS),
            projectResourceLinks = readRows(database, tenantId, secretKey, TABLE_PROJECT_RESOURCE_LINKS),
            projectItemOperations = readRows(database, tenantId, secretKey, TABLE_PROJECT_ITEM_OPERATIONS),
            retiredProjectItemOperations = readRows(
                database,
                tenantId,
                secretKey,
                TABLE_RETIRED_PROJECT_ITEM_OPERATIONS,
            ),
            projectCommands = readRows(database, tenantId, secretKey, TABLE_PROJECT_COMMANDS),
            retiredOperationIds = readRows(database, tenantId, secretKey, TABLE_RETIRED_OPERATIONS),
            provisionalProjectIds = readRows(database, tenantId, secretKey, TABLE_PROVISIONAL_PROJECTS),
            projectIdMappings = readRows(database, tenantId, secretKey, TABLE_PROJECT_ID_MAPPINGS),
            projectsAwaitingNotesBaseline = readRows(
                database,
                tenantId,
                secretKey,
                TABLE_PROJECTS_AWAITING_NOTES_BASELINE,
            ),
            projectDrafts = readRows(database, tenantId, secretKey, TABLE_PROJECT_DRAFTS).singleOrNull(),
            projectDraftsMigrated = readOptionalMetadata(
                database,
                tenantId,
                secretKey,
                META_PROJECT_DRAFTS_MIGRATED,
            )?.toBooleanStrict() ?: false,
            projectUiPreferences = readRows(
                database,
                tenantId,
                secretKey,
                TABLE_PROJECT_UI_PREFERENCES,
            ).singleOrNull(),
        )
    }

    private fun replaceStateRows(
        database: SQLiteDatabase,
        tenantId: String,
        secretKey: SecretKey,
        rows: EncodedSyncRows,
    ) {
        clearTables(database)
        writeMetadata(database, tenantId, secretKey, META_SCHEMA_VERSION, rows.schemaVersion.toString())
        writeMetadata(database, tenantId, secretKey, META_PROJECTS_LOADED, rows.projectsLoaded.toString())
        writeMetadata(database, tenantId, secretKey, META_REENTRY_LOADED, rows.reentryLoaded.toString())
        writeMetadata(
            database,
            tenantId,
            secretKey,
            META_ACTIVE_PROJECTS_LOADED,
            rows.activeProjectsLoaded.toString(),
        )
        writeMetadata(
            database,
            tenantId,
            secretKey,
            META_ARCHIVED_PROJECTS_LOADED,
            rows.archivedProjectsLoaded.toString(),
        )
        writeMetadata(database, tenantId, secretKey, META_BLOCKS_LOADED, rows.blocksLoaded.toString())
        writeMetadata(
            database,
            tenantId,
            secretKey,
            META_PROJECT_DRAFTS_MIGRATED,
            rows.projectDraftsMigrated.toString(),
        )
        rows.changeCursor?.let { writeMetadata(database, tenantId, secretKey, META_CHANGE_CURSOR, it) }
        rows.notice?.let { writeMetadata(database, tenantId, secretKey, META_NOTICE, it) }
        writeRows(database, tenantId, secretKey, TABLE_PROJECTS, rows.projects)
        writeRows(database, tenantId, secretKey, TABLE_REENTRY, listOfNotNull(rows.reentry))
        writeRows(database, tenantId, secretKey, TABLE_COMMANDS, rows.commands)
        writeRows(database, tenantId, secretKey, TABLE_RETIRED_KEYS, rows.retiredIdempotencyKeys)
        writeRows(database, tenantId, secretKey, TABLE_ACTIVE_PROJECTS, rows.activeProjects)
        writeRows(database, tenantId, secretKey, TABLE_ARCHIVED_PROJECTS, rows.archivedProjects)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_PAGES, rows.projectPages)
        writeRows(database, tenantId, secretKey, TABLE_PARKING_RECORDS, rows.parkingRecords)
        writeRows(
            database,
            tenantId,
            secretKey,
            TABLE_PARKING_LOADED_PROJECTS,
            rows.parkingLoadedProjectIds,
        )
        writeRows(database, tenantId, secretKey, TABLE_STALE_PROJECT_PAGES, rows.stalePageIds)
        writeRows(database, tenantId, secretKey, TABLE_BLOCKS, rows.blocks)
        writeRows(database, tenantId, secretKey, TABLE_LOCAL_BLOCKS, rows.localBlocks)
        writeRows(database, tenantId, secretKey, TABLE_LOCAL_BLOCK_OPERATIONS, rows.localBlockOperations)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_LINKS, rows.projectLinks)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_COVERS, rows.projectCovers)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_NOTE_ITEMS, rows.projectNoteItems)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_RESOURCE_LINKS, rows.projectResourceLinks)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_ITEM_OPERATIONS, rows.projectItemOperations)
        writeRows(
            database,
            tenantId,
            secretKey,
            TABLE_RETIRED_PROJECT_ITEM_OPERATIONS,
            rows.retiredProjectItemOperations,
        )
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_COMMANDS, rows.projectCommands)
        writeRows(database, tenantId, secretKey, TABLE_RETIRED_OPERATIONS, rows.retiredOperationIds)
        writeRows(database, tenantId, secretKey, TABLE_PROVISIONAL_PROJECTS, rows.provisionalProjectIds)
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_ID_MAPPINGS, rows.projectIdMappings)
        writeRows(
            database,
            tenantId,
            secretKey,
            TABLE_PROJECTS_AWAITING_NOTES_BASELINE,
            rows.projectsAwaitingNotesBaseline,
        )
        writeRows(database, tenantId, secretKey, TABLE_PROJECT_DRAFTS, listOfNotNull(rows.projectDrafts))
        writeRows(
            database,
            tenantId,
            secretKey,
            TABLE_PROJECT_UI_PREFERENCES,
            listOfNotNull(rows.projectUiPreferences),
        )
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
            database.rawQuery("PRAGMA secure_delete=ON", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                    "Could not enable secure SQLite deletion"
                }
            }
        }

        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE $TABLE_METADATA (" +
                    "$COLUMN_NAME TEXT PRIMARY KEY NOT NULL, " +
                    "$COLUMN_CIPHERTEXT BLOB NOT NULL)",
            )
            POSITION_TABLES.forEach { table -> createPositionTable(database, table) }
        }

        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            require(oldVersion in 1 until newVersion && newVersion == DATABASE_VERSION) {
                "No sync database migration from $oldVersion to $newVersion"
            }
            if (oldVersion < 2) PROJECT_MODEL_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 3) LOCAL_FIRST_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 4) STANDALONE_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 5) PARKING_HISTORY_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 6) LOCAL_TAG_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 7) PROJECT_LINK_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 8) PROJECT_COVER_TABLES.forEach { table -> createPositionTable(database, table) }
            if (oldVersion < 9) PROJECT_ITEM_TABLES.forEach { table -> createPositionTable(database, table) }
        }

        override fun onDowngrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Sync database downgrade from $oldVersion to $newVersion is not supported")
        }

        private fun createPositionTable(database: SQLiteDatabase, table: String) {
            database.execSQL(
                "CREATE TABLE $table (" +
                    "$COLUMN_POSITION INTEGER PRIMARY KEY NOT NULL, " +
                    "$COLUMN_CIPHERTEXT BLOB NOT NULL)",
            )
        }
    }

    private companion object {
        const val DATABASE_VERSION = 9
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
        const val TABLE_ACTIVE_PROJECTS = "active_projects_v3"
        const val TABLE_ARCHIVED_PROJECTS = "archived_projects_v3"
        const val TABLE_PROJECT_PAGES = "project_pages_v3"
        const val TABLE_PARKING_RECORDS = "parking_records_v6"
        const val TABLE_PARKING_LOADED_PROJECTS = "parking_loaded_projects_v6"
        const val TABLE_STALE_PROJECT_PAGES = "stale_project_pages_v3"
        const val TABLE_BLOCKS = "blocks_v3"
        const val TABLE_LOCAL_BLOCKS = "local_blocks_v7"
        const val TABLE_LOCAL_BLOCK_OPERATIONS = "local_block_operations_v7"
        const val TABLE_PROJECT_LINKS = "project_links_v8"
        const val TABLE_PROJECT_COVERS = "project_covers_v9"
        const val TABLE_PROJECT_NOTE_ITEMS = "project_note_items_v10"
        const val TABLE_PROJECT_RESOURCE_LINKS = "project_resource_links_v10"
        const val TABLE_PROJECT_ITEM_OPERATIONS = "project_item_operations_v10"
        const val TABLE_RETIRED_PROJECT_ITEM_OPERATIONS = "retired_project_item_operations_v10"
        const val TABLE_PROJECT_COMMANDS = "pending_project_commands_v3"
        const val TABLE_RETIRED_OPERATIONS = "retired_project_operations_v3"
        const val TABLE_PROVISIONAL_PROJECTS = "provisional_projects_v4"
        const val TABLE_PROJECT_ID_MAPPINGS = "project_id_mappings_v4"
        const val TABLE_PROJECTS_AWAITING_NOTES_BASELINE = "projects_awaiting_notes_baseline_v4"
        const val TABLE_PROJECT_DRAFTS = "project_drafts_v4"
        const val TABLE_PROJECT_UI_PREFERENCES = "project_ui_preferences_v5"
        const val COLUMN_NAME = "name"
        const val COLUMN_POSITION = "position"
        const val COLUMN_CIPHERTEXT = "ciphertext"
        const val META_SCHEMA_VERSION = "schema_version"
        const val META_PROJECTS_LOADED = "projects_loaded"
        const val META_REENTRY_LOADED = "reentry_loaded"
        const val META_CHANGE_CURSOR = "change_cursor"
        const val META_NOTICE = "notice"
        const val META_ACTIVE_PROJECTS_LOADED = "active_projects_loaded_v3"
        const val META_ARCHIVED_PROJECTS_LOADED = "archived_projects_loaded_v3"
        const val META_BLOCKS_LOADED = "blocks_loaded_v3"
        const val META_PROJECT_DRAFTS_MIGRATED = "project_drafts_migrated_v4"
        val PROJECT_MODEL_TABLES = listOf(
            TABLE_ACTIVE_PROJECTS,
            TABLE_ARCHIVED_PROJECTS,
            TABLE_PROJECT_PAGES,
            TABLE_STALE_PROJECT_PAGES,
            TABLE_BLOCKS,
            TABLE_PROJECT_COMMANDS,
            TABLE_RETIRED_OPERATIONS,
        )
        val LOCAL_FIRST_TABLES = listOf(
            TABLE_PROVISIONAL_PROJECTS,
            TABLE_PROJECT_ID_MAPPINGS,
            TABLE_PROJECTS_AWAITING_NOTES_BASELINE,
            TABLE_PROJECT_DRAFTS,
        )
        val STANDALONE_TABLES = listOf(TABLE_PROJECT_UI_PREFERENCES)
        val PARKING_HISTORY_TABLES = listOf(TABLE_PARKING_RECORDS, TABLE_PARKING_LOADED_PROJECTS)
        val LOCAL_TAG_TABLES = listOf(TABLE_LOCAL_BLOCKS, TABLE_LOCAL_BLOCK_OPERATIONS)
        val PROJECT_LINK_TABLES = listOf(TABLE_PROJECT_LINKS)
        val PROJECT_COVER_TABLES = listOf(TABLE_PROJECT_COVERS)
        val PROJECT_ITEM_TABLES = listOf(
            TABLE_PROJECT_NOTE_ITEMS,
            TABLE_PROJECT_RESOURCE_LINKS,
            TABLE_PROJECT_ITEM_OPERATIONS,
            TABLE_RETIRED_PROJECT_ITEM_OPERATIONS,
        )
        val POSITION_TABLES = listOf(
            TABLE_PROJECTS,
            TABLE_REENTRY,
            TABLE_COMMANDS,
            TABLE_RETIRED_KEYS,
        ) + PROJECT_MODEL_TABLES + LOCAL_FIRST_TABLES + STANDALONE_TABLES +
            PARKING_HISTORY_TABLES + LOCAL_TAG_TABLES + PROJECT_LINK_TABLES + PROJECT_COVER_TABLES +
            PROJECT_ITEM_TABLES
        val TABLES = listOf(
            TABLE_METADATA,
        ) + POSITION_TABLES
    }
}
