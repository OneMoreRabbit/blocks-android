package dev.blocks.android.project

import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.OpaqueTagTokenFactory
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagProgrammingFailure
import dev.blocks.android.tag.LocalBlockOperation
import java.util.UUID

enum class ProjectLifecycle(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived"),
}

data class ProjectSummary(
    val id: String,
    val tenantId: String,
    val name: String,
    val lifecycle: ProjectLifecycle,
    val rowVersion: Int,
    val createdAt: String,
    val updatedAt: String,
    val archivedAt: String? = null,
    val clientId: String? = null,
)

data class ProjectNotes(
    val projectId: String,
    val body: String,
    val rowVersion: Int,
    val updatedAt: String,
)

data class ProjectParking(
    val id: String,
    val projectId: String,
    val location: String,
    val nextAction: String,
    val mustNotForget: String,
    val authoredByUserId: String,
    val createdAt: String,
    val occurredAt: String = createdAt,
    val clientId: String? = null,
    val deviceId: String? = null,
    val supersedesId: String? = null,
)

data class ProjectPage(
    val project: ProjectSummary,
    val notes: ProjectNotes,
    val newestParking: ProjectParking?,
)

data class ParkingHistoryPage(
    val records: List<ProjectParking>,
    val hasMore: Boolean,
)

data class PhysicalBlock(
    val id: String,
    val label: String,
    val state: String,
    val boundProjectId: String? = null,
)

/** Direct registration result. [tagToken] is returned once and must never be logged. */
data class RegisteredBlock(
    val block: PhysicalBlock,
    val tagToken: String,
)

data class BlockBinding(
    val id: String,
    val blockId: String,
    val projectId: String,
)

data class BlockLookup(
    val blockId: String,
    val label: String,
    val projectId: String?,
)

enum class ProjectMutationType {
    CREATE_PROJECT,
    RENAME_PROJECT,
    ARCHIVE_PROJECT,
    REOPEN_PROJECT,
    SAVE_NOTES,
    ADD_PARKING,
    BIND_BLOCK,
    UNBIND_BLOCK,
}

enum class ProjectMutationStatus { PENDING, REQUIRES_RECONCILIATION }

enum class DashboardView { LIST, TILES }

data class ProjectUiPreferences(
    val dashboardView: DashboardView = DashboardView.LIST,
    val archiveMode: Boolean = false,
)

/** A complete, immutable retry description for one Android-owned synchronization operation. */
data class ProjectMutation(
    val operationId: String,
    val type: ProjectMutationType,
    val targetId: String,
    val projectId: String? = null,
    val idempotencyKey: String? = null,
    val clientResourceId: String? = null,
    val ifMatch: Int? = null,
    val projectName: String? = null,
    val notesBody: String? = null,
    val parking: ParkingDraft? = null,
    val supersedesId: String? = null,
    val dependsOnOperationId: String? = null,
    val localCreatedAt: String? = null,
    val status: ProjectMutationStatus = ProjectMutationStatus.PENDING,
    val deliveryUncertain: Boolean = false,
    val failureMessage: String? = null,
    val authoritativeStateConfirmed: Boolean = false,
    val authoritativePage: ProjectPage? = null,
    val contractVersion: String = "0.4",
)

data class ProjectIdentityMapping(
    val provisionalId: String,
    val serverId: String,
)

data class ProjectCacheState(
    val activeProjects: List<ProjectSummary> = emptyList(),
    val activeProjectsLoaded: Boolean = false,
    val archivedProjects: List<ProjectSummary> = emptyList(),
    val archivedProjectsLoaded: Boolean = false,
    val pages: List<ProjectPage> = emptyList(),
    val parkingRecords: List<ProjectParking> = emptyList(),
    val parkingLoadedProjectIds: List<String> = emptyList(),
    val stalePageIds: List<String> = emptyList(),
    val blocks: List<PhysicalBlock> = emptyList(),
    val blocksLoaded: Boolean = false,
    val localBlocks: List<LocalBlockRecord> = emptyList(),
    val localBlockOperations: List<LocalBlockOperation> = emptyList(),
    val commands: List<ProjectMutation> = emptyList(),
    val retiredOperationIds: List<String> = emptyList(),
    val provisionalProjectIds: List<String> = emptyList(),
    val projectIdMappings: List<ProjectIdentityMapping> = emptyList(),
    val projectsAwaitingNotesBaseline: List<String> = emptyList(),
)

data class BackgroundSyncResult(
    val cache: ProjectCacheState,
    val cursor: String?,
    val pendingLegacyOperations: Int,
    val pendingProjectOperations: Int,
    val reconciliationRequired: Boolean,
    val conflict: ProjectConflict? = null,
    val pendingBlockOperations: Int = 0,
)

data class ProjectConflict(
    val operationId: String,
    val type: ProjectMutationType,
    val projectId: String?,
    val localProjectName: String? = null,
    val localNotes: String? = null,
    val localParking: ParkingDraft? = null,
    val authoritativePage: ProjectPage? = null,
) {
    val canRetry: Boolean
        get() = type in setOf(
            ProjectMutationType.RENAME_PROJECT,
            ProjectMutationType.ARCHIVE_PROJECT,
            ProjectMutationType.REOPEN_PROJECT,
            ProjectMutationType.SAVE_NOTES,
        ) && authoritativePage != null
}

interface ProjectService {
    fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary>
    fun createProject(
        session: Session,
        name: String,
        clientId: String?,
        idempotencyKey: String,
    ): ProjectSummary
    fun projectPage(session: Session, projectId: String): ProjectPage
    fun renameProject(session: Session, projectId: String, rowVersion: Int, name: String): ProjectSummary
    fun archiveProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary
    fun reopenProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary
    fun notes(session: Session, projectId: String): ProjectNotes
    fun saveNotes(session: Session, projectId: String, rowVersion: Int, body: String): ProjectNotes
    fun addParking(
        session: Session,
        projectId: String,
        parking: ParkingDraft,
        supersedesId: String?,
        clientId: String?,
        occurredAt: String?,
        idempotencyKey: String,
    ): ProjectParking
    fun parking(session: Session, projectId: String): List<ProjectParking>
    fun blocks(session: Session): List<PhysicalBlock>
    /**
     * Legacy API 0.4 direct registration adapter. Android 0.6 local block operations do not call it:
     * API 0.4 generates its token remotely, while the local-first journey persists its token before I/O.
     */
    fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock
    fun bindBlock(session: Session, blockId: String, projectId: String, idempotencyKey: String): BlockBinding
    fun unbindBlock(session: Session, blockId: String)
    fun lookupBlock(session: Session, tagToken: String): BlockLookup
    fun changes(session: Session, cursor: String?, limit: Int = 100): ChangePage
}

fun interface SessionAuthenticator {
    fun login(identityToken: String): Session
}

/** Presentation-facing boundary around the durable project cache and command dispatcher. */
interface ProjectWorkspace {
    fun activate(session: Session)
    fun deactivate(session: Session) = Unit
    fun snapshot(session: Session): BackgroundSyncResult
    fun projectDrafts(session: Session): FormDrafts
    fun updateProjectDrafts(session: Session, update: (FormDrafts) -> FormDrafts): FormDrafts
    fun uiPreferences(session: Session): ProjectUiPreferences
    fun updateUiPreferences(
        session: Session,
        update: (ProjectUiPreferences) -> ProjectUiPreferences,
    ): ProjectUiPreferences
    fun archivedProjects(session: Session): List<ProjectSummary>
    fun projectPage(session: Session, projectId: String): ProjectPage
    fun parkingHistory(
        session: Session,
        projectId: String,
        offset: Int,
        limit: Int,
    ): ParkingHistoryPage
    fun localBlocks(session: Session): List<LocalBlockRecord> = snapshot(session).cache.localBlocks
    fun beginTagProgramming(
        session: Session,
        projectId: String,
        label: String,
        clientBlockId: String = newOperationIdentity(),
        token: OpaqueTagToken = OpaqueTagTokenFactory().create(),
        registrationOperationId: String = newOperationIdentity(),
        bindingOperationId: String = newOperationIdentity(),
    ): LocalBlockRecord = throw UnsupportedOperationException("Local tag programming is unavailable")
    fun markTagWriteAttempt(session: Session, clientBlockId: String): LocalBlockRecord =
        throw UnsupportedOperationException("Local tag programming is unavailable")
    fun recordTagProgrammingFailure(
        session: Session,
        clientBlockId: String,
        failure: TagProgrammingFailure,
    ): LocalBlockRecord = throw UnsupportedOperationException("Local tag programming is unavailable")
    fun discardPendingTagProgramming(session: Session, clientBlockId: String): BackgroundSyncResult =
        throw UnsupportedOperationException("Local tag programming is unavailable")
    fun authorizeTagReplacement(
        session: Session,
        clientBlockId: String,
        existingToken: OpaqueTagToken,
    ): LocalBlockRecord = throw UnsupportedOperationException("Local tag programming is unavailable")
    fun completeTagProgramming(
        session: Session,
        clientBlockId: String,
        verifiedToken: OpaqueTagToken,
        medium: TagActivationMedium,
    ): BackgroundSyncResult = throw UnsupportedOperationException("Local tag programming is unavailable")
    fun resolveTagLocally(session: Session, token: OpaqueTagToken): LocalTagResolution =
        LocalTagResolution.Unknown(token)
    fun resolveTagThroughService(session: Session, token: OpaqueTagToken): LocalTagResolution =
        LocalTagResolution.Unknown(token)
    fun assignLocalBlock(
        session: Session,
        clientBlockId: String,
        projectId: String,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult = throw UnsupportedOperationException("Local tag association is unavailable")
    fun unbindLocalBlock(
        session: Session,
        clientBlockId: String,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult = throw UnsupportedOperationException("Local tag association is unavailable")
    fun backgroundSync(session: Session): BackgroundSyncResult
    fun queueCreateProject(
        session: Session,
        name: String,
        operationId: String = newOperationIdentity(),
        clientId: String = operationId,
    ): BackgroundSyncResult
    fun queueRenameProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        name: String,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult
    fun queueArchiveProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult
    fun queueReopenProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult
    fun queueSaveNotes(
        session: Session,
        projectId: String,
        rowVersion: Int,
        body: String,
        operationId: String = newOperationIdentity(),
    ): BackgroundSyncResult
    fun queueParking(
        session: Session,
        projectId: String,
        parking: ParkingDraft,
        supersedesId: String? = null,
        operationId: String = newOperationIdentity(),
        clientId: String = operationId,
    ): BackgroundSyncResult
    fun discardConflict(session: Session, operationId: String): BackgroundSyncResult
    fun retryConflict(
        session: Session,
        operationId: String,
        newOperationId: String = newOperationIdentity(),
    ): BackgroundSyncResult
    fun discardPendingOperation(session: Session, operationId: String): BackgroundSyncResult
    fun clearLocalState(session: Session)
}

fun newOperationIdentity(): String = UUID.randomUUID().toString()
