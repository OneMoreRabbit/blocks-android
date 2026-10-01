package dev.blocks.android.mvp

import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectUiPreferences

data class ChangeEvent(
    val eventId: String,
    val eventType: String,
    val eventVersion: String,
    val tenantId: String,
    val payload: Map<String, Any?>,
)

data class ChangePage(
    val events: List<ChangeEvent>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

enum class OfflineOperation {
    PICKUP,
    PARK,
    PARK_AND_CLOSE,
    CLOSE_EXISTING_CONTINUATION,
    CLOSE_WITH_OUTCOME,
}

enum class QueuedCommandStatus { PENDING, REQUIRES_RECONCILIATION }

data class QueuedCommand(
    val operation: OfflineOperation,
    val targetId: String,
    val projectId: String,
    val idempotencyKey: String,
    val ifMatch: Int? = null,
    val workSession: WorkSession? = null,
    val pickup: PickupRequest? = null,
    val parking: ParkingRecord? = null,
    val outcomeTier: OutcomeTier? = null,
    val afterCursor: String? = null,
    val baselineSessionId: String? = null,
    val status: QueuedCommandStatus = QueuedCommandStatus.PENDING,
    val deliveryUncertain: Boolean = false,
    val remoteEventObserved: Boolean = false,
    val failureMessage: String? = null,
)

data class TenantSyncState(
    val projects: List<Project> = emptyList(),
    val projectsLoaded: Boolean = false,
    val reentry: Reentry? = null,
    val reentryLoaded: Boolean = false,
    val commands: List<QueuedCommand> = emptyList(),
    val changeCursor: String? = null,
    val retiredIdempotencyKeys: List<String> = emptyList(),
    val notice: String? = null,
    val projectCache: ProjectCacheState = ProjectCacheState(),
    val projectDrafts: FormDrafts = FormDrafts(),
    val projectDraftsMigrated: Boolean = false,
    val projectUiPreferences: ProjectUiPreferences = ProjectUiPreferences(),
)

data class SyncStatus(
    val pendingCommands: Int = 0,
    val reconciliationRequired: Boolean = false,
    val message: String? = null,
)

data class SyncResult(
    val reentry: Reentry,
    val status: SyncStatus = SyncStatus(),
)

interface SyncStateStore {
    fun loadSyncState(tenantId: String): TenantSyncState
    fun saveSyncState(tenantId: String, state: TenantSyncState)
    fun saveSyncState(tenantId: String, previous: TenantSyncState, state: TenantSyncState) =
        saveSyncState(tenantId, state)
    fun saveProjectMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectMutationType,
    ) = saveSyncState(tenantId, previous, state)
    fun saveProjectItemMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        type: ProjectItemMutationType,
    ) = saveSyncState(tenantId, previous, state)
    fun saveInteractionMutation(
        tenantId: String,
        previous: TenantSyncState,
        state: TenantSyncState,
        projectMutationType: ProjectMutationType? = null,
        itemMutationType: ProjectItemMutationType? = null,
    ) = saveSyncState(tenantId, previous, state)
    fun clearSyncState(tenantId: String)
}

object NoOpSyncStateStore : SyncStateStore {
    override fun loadSyncState(tenantId: String) = TenantSyncState()
    override fun saveSyncState(tenantId: String, state: TenantSyncState) = Unit
    override fun clearSyncState(tenantId: String) = Unit
}

class QueuedOfflineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MembershipRevokedException(cause: Throwable) : RuntimeException(
    "This account no longer has access to the selected Blocks tenant. Sign in again to continue.",
    cause,
)
