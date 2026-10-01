package dev.blocks.android.project

/** Device-local dashboard projection. Only [CUSTOM] has a synchronized sequence. */
enum class DashboardOrderMode { CUSTOM, NAME, LAST_ACTIVITY }

data class ProjectOrderState(
    val orderedProjectIds: List<String> = emptyList(),
    val version: Int = 0,
    val updatedAt: String = "",
    val aggregateId: String? = null,
)

data class LinkOrderState(
    val projectId: String,
    val orderedLinkIds: List<String> = emptyList(),
    val version: Int = 0,
    val updatedAt: String = "",
    val aggregateId: String? = null,
)

/** Visibility metadata only. The immutable [ProjectParking] row remains untouched. */
data class ParkingTombstone(
    val parkingId: String,
    val projectId: String,
    val version: Int,
    val deletedAt: String,
    val lastOperationId: String,
)

data class AuthoritativeOrder(
    val aggregateId: String,
    val projectId: String?,
    val orderedClientIds: List<String>,
    val version: Int,
    val operationId: String?,
)

enum class InteractionMutationType {
    REORDER_PROJECTS,
    REORDER_LINKS,
    DELETE_PARKING,
}

/** Durable API 0.7 envelope. Preparation binds the local intent to the authoritative aggregate before first send. */
data class InteractionMutation(
    val operationId: String,
    val type: InteractionMutationType,
    val aggregateId: String,
    val projectId: String? = null,
    val targetId: String? = null,
    val orderedClientIds: List<String> = emptyList(),
    val baseVersion: Int,
    val dependsOnOperationIds: List<String> = emptyList(),
    val createdAt: String,
    val status: ProjectMutationStatus = ProjectMutationStatus.PENDING,
    val deliveryUncertain: Boolean = false,
    val contractVersion: String = "0.7",
)

sealed interface InteractionAcknowledgement {
    val operationId: String
    val aggregateId: String
    val version: Int

    data class Order(
        override val operationId: String,
        override val aggregateId: String,
        override val version: Int,
        val projectId: String?,
        val orderedClientIds: List<String>,
    ) : InteractionAcknowledgement

    data class Parking(
        override val operationId: String,
        override val aggregateId: String,
        override val version: Int,
        val projectId: String,
        val targetId: String,
        val deletedAt: String?,
        val changed: Boolean,
    ) : InteractionAcknowledgement
}

internal fun ProjectCacheState.ensureInteractionState(): ProjectCacheState {
    val activeIds = activeProjects.map { it.id }
    val activeIdSet = activeIds.toSet()
    val retainedProjects = projectOrder.orderedProjectIds.filter { it in activeIdSet }
    val retainedProjectSet = retainedProjects.toSet()
    val normalizedProjectOrder = projectOrder.copy(
        orderedProjectIds = retainedProjects + activeIds.filterNot(retainedProjectSet::contains),
    )

    val liveLinksByProject = resourceLinks.filter { it.deletedAt == null }.groupBy { it.projectId }
    val normalizedLinkOrders = buildList {
        val existingByProject = linkOrders.associateBy { it.projectId }
        val projectIds = (existingByProject.keys + liveLinksByProject.keys).sorted()
        projectIds.forEach { projectId ->
            val liveIds = liveLinksByProject[projectId].orEmpty().map { it.id }
            val liveIdSet = liveIds.toSet()
            val existing = existingByProject[projectId] ?: LinkOrderState(projectId)
            val retained = existing.orderedLinkIds.filter { it in liveIdSet }
            val retainedSet = retained.toSet()
            add(existing.copy(orderedLinkIds = retained + liveIds.filterNot(retainedSet::contains)))
        }
    }
    val hiddenParkingIds = parkingTombstones.mapTo(mutableSetOf()) { it.parkingId }
    val normalizedPages = pages.map { page ->
        val newest = parkingRecords
            .filter { it.projectId == page.project.id && it.id !in hiddenParkingIds }
            .maxWithOrNull(
                compareBy<ProjectParking> { it.occurredAt }.thenBy { it.createdAt }.thenBy { it.id },
            )
        page.copy(newestParking = newest)
    }
    return copy(
        projectOrder = normalizedProjectOrder,
        linkOrders = normalizedLinkOrders,
        pages = normalizedPages,
    )
}

internal fun ProjectCacheState.orderedResourceLinks(projectId: String): List<ProjectResourceLink> {
    val live = resourceLinks.filter { it.projectId == projectId && it.deletedAt == null }
    val byId = live.associateBy { it.id }
    val sequence = linkOrders.firstOrNull { it.projectId == projectId }?.orderedLinkIds.orEmpty()
    return sequence.mapNotNull(byId::get) + live.filterNot { it.id in sequence }
}

internal fun ProjectCacheState.visibleParking(projectId: String): List<ProjectParking> {
    val hidden = parkingTombstones.mapTo(mutableSetOf()) { it.parkingId }
    return parkingRecords.filter { it.projectId == projectId && it.id !in hidden }
}

internal fun ProjectCacheState.dashboardProjects(mode: DashboardOrderMode): List<ProjectSummary> = when (mode) {
    DashboardOrderMode.CUSTOM -> {
        val byId = activeProjects.associateBy { it.id }
        projectOrder.orderedProjectIds.mapNotNull(byId::get) +
            activeProjects.filterNot { it.id in projectOrder.orderedProjectIds }
    }
    DashboardOrderMode.NAME -> activeProjects.sortedWith(
        compareBy<ProjectSummary> { it.name.lowercase() }.thenBy { it.id },
    )
    DashboardOrderMode.LAST_ACTIVITY -> activeProjects.sortedWith(
        compareByDescending<ProjectSummary> { projectLastActivity(it.id) }.thenBy { it.id },
    )
}

private fun ProjectCacheState.projectLastActivity(projectId: String): String = buildList {
    activeProjects.firstOrNull { it.id == projectId }?.updatedAt?.let(::add)
    noteItems.filter { it.projectId == projectId && it.deletedAt == null }.mapTo(this) { it.updatedAt }
    resourceLinks.filter { it.projectId == projectId && it.deletedAt == null }.mapTo(this) { it.updatedAt }
    val hidden = parkingTombstones.mapTo(mutableSetOf()) { it.parkingId }
    parkingRecords.filter { it.projectId == projectId && it.id !in hidden }.mapTo(this) { it.occurredAt }
}.maxOrNull().orEmpty()
