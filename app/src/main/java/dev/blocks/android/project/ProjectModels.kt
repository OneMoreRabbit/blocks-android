package dev.blocks.android.project

import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
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
    val deviceId: String? = null,
    val supersedesId: String? = null,
)

data class ProjectPage(
    val project: ProjectSummary,
    val notes: ProjectNotes,
    val newestParking: ProjectParking?,
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

/** A complete, immutable retry description for one API 0.3 operation. */
data class ProjectMutation(
    val operationId: String,
    val type: ProjectMutationType,
    val targetId: String,
    val projectId: String? = null,
    val idempotencyKey: String? = null,
    val ifMatch: Int? = null,
    val projectName: String? = null,
    val notesBody: String? = null,
    val parking: ParkingDraft? = null,
    val supersedesId: String? = null,
    val status: ProjectMutationStatus = ProjectMutationStatus.PENDING,
    val deliveryUncertain: Boolean = false,
    val failureMessage: String? = null,
)

data class ProjectCacheState(
    val activeProjects: List<ProjectSummary> = emptyList(),
    val activeProjectsLoaded: Boolean = false,
    val archivedProjects: List<ProjectSummary> = emptyList(),
    val archivedProjectsLoaded: Boolean = false,
    val pages: List<ProjectPage> = emptyList(),
    val stalePageIds: List<String> = emptyList(),
    val blocks: List<PhysicalBlock> = emptyList(),
    val blocksLoaded: Boolean = false,
    val commands: List<ProjectMutation> = emptyList(),
    val retiredOperationIds: List<String> = emptyList(),
)

data class BackgroundSyncResult(
    val cache: ProjectCacheState,
    val cursor: String?,
    val pendingLegacyOperations: Int,
    val pendingProjectOperations: Int,
    val reconciliationRequired: Boolean,
)

interface ProjectService {
    fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary>
    fun createProject(session: Session, name: String, idempotencyKey: String): ProjectSummary
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
        idempotencyKey: String,
    ): ProjectParking
    fun parking(session: Session, projectId: String): List<ProjectParking>
    fun blocks(session: Session): List<PhysicalBlock>
    /**
     * Direct-only foundation adapter. A later NFC state machine must custody [RegisteredBlock.tagToken]
     * before acknowledging registration; the generic background queue deliberately cannot call this.
     */
    fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock
    fun bindBlock(session: Session, blockId: String, projectId: String, idempotencyKey: String): BlockBinding
    fun unbindBlock(session: Session, blockId: String)
    fun lookupBlock(session: Session, tagToken: String): BlockLookup
    fun changes(session: Session, cursor: String?, limit: Int = 100): ChangePage
}

fun newOperationIdentity(): String = UUID.randomUUID().toString()
