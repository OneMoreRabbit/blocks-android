package dev.blocks.android.sync

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.AuthoritativeOrder
import dev.blocks.android.project.InteractionAcknowledgement
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.visibleParking
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Api07InteractionSyncTest {
    @Test
    fun drainsAllThreeDurableOperationsWithAuthoritativeIdentitiesAndOriginalKeys() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd()
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), OP_PROJECT_ORDER)
        repository.moveResourceLink(SESSION_A, LINK_B, -1, OP_LINK_ORDER)
        repository.deleteParking(SESSION_A, PARK_A, OP_DELETE_PARK)
        repository.backgroundSync(SESSION_A)

        assertEquals(listOf(OP_PROJECT_ORDER), farEnd.projectReorders.map { it.operationId })
        assertEquals(listOf(CLIENT_B, CLIENT_A), farEnd.projectReorders.single().orderedClientIds)
        assertEquals(listOf(OP_LINK_ORDER), farEnd.linkReorders.map { it.operationId })
        assertEquals(listOf(LINK_B, LINK_A), farEnd.linkReorders.single().orderedClientIds)
        assertEquals(listOf(OP_DELETE_PARK), farEnd.parkDeletes.map { it.operationId })
        assertEquals(1, farEnd.parkDeletes.single().baseVersion)

        val durable = store.loadSyncState(TENANT_A).projectCache
        assertTrue(durable.interactionOperations.isEmpty())
        assertEquals(
            setOf(OP_PROJECT_ORDER, OP_LINK_ORDER, OP_DELETE_PARK),
            durable.retiredInteractionOperationIds.toSet(),
        )
        assertEquals(PROJECT_AGGREGATE, durable.projectOrder.aggregateId)
        assertEquals(LINK_AGGREGATE_A, durable.linkOrders.single { it.projectId == PROJECT_A }.aggregateId)
        assertEquals(listOf(PROJECT_B, PROJECT_A), durable.projectOrder.orderedProjectIds)
        assertEquals(listOf(LINK_B, LINK_A), durable.linkOrders.single { it.projectId == PROJECT_A }.orderedLinkIds)
        assertEquals(parking(), durable.parkingRecords.single { it.id == PARK_A })
        assertEquals(2, durable.parkingTombstones.single { it.parkingId == PARK_A }.version)
        assertTrue(durable.visibleParking(PROJECT_A).isEmpty())
    }

    @Test
    fun authoritativeDiscoveryAfterLostResponseRetiresOnlyTheExactPersistedOperation() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd().apply { loseNextProjectOrderResponse = true }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), OP_PROJECT_ORDER)

        repository.backgroundSync(SESSION_A)
        val uncertain = store.loadSyncState(TENANT_A).projectCache.interactionOperations.single()
        assertEquals(OP_PROJECT_ORDER, uncertain.operationId)
        assertTrue(uncertain.deliveryUncertain)
        assertEquals(1, farEnd.projectReorders.size)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        val converged = store.loadSyncState(TENANT_A).projectCache
        assertTrue(converged.interactionOperations.isEmpty())
        assertTrue(OP_PROJECT_ORDER in converged.retiredInteractionOperationIds)
        assertEquals(1, farEnd.projectReorders.size)
        assertThrows(IllegalArgumentException::class.java) {
            repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_A, PROJECT_B), OP_PROJECT_ORDER)
        }
    }

    @Test
    fun staleOrderConflictKeepsTheLocalProjectionAndCompleteEnvelopeAcrossRestart() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd().apply { conflictNextProjectOrder = true }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), OP_PROJECT_ORDER)

        repository.backgroundSync(SESSION_A)
        val conflicted = store.loadSyncState(TENANT_A).projectCache
        assertEquals(listOf(PROJECT_B, PROJECT_A), conflicted.projectOrder.orderedProjectIds)
        assertEquals(OP_PROJECT_ORDER, conflicted.interactionOperations.single().operationId)
        assertEquals(listOf(CLIENT_B, CLIENT_A), conflicted.interactionOperations.single().orderedClientIds)
        assertEquals(dev.blocks.android.project.ProjectMutationStatus.REQUIRES_RECONCILIATION,
            conflicted.interactionOperations.single().status)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        val restored = repository.snapshot(SESSION_A).cache
        assertEquals(listOf(PROJECT_B, PROJECT_A), restored.projectOrder.orderedProjectIds)
        assertEquals(OP_PROJECT_ORDER, restored.interactionOperations.single().operationId)
    }

    @Test
    fun identifierOnlyParkEventCommitsTombstoneAndCursorWithoutDeletingAuthoredContent() {
        val store = MemoryStore().apply {
            saveSyncState(TENANT_A, state())
            saveSyncState(TENANT_B, state(TENANT_B))
        }
        val farEnd = FarEnd().apply {
            tombstones[PROJECT_A] = listOf(
                ParkingTombstone(PARK_A, PROJECT_A, 2, LATER, REMOTE_DELETE),
            )
            changes += ChangePage(
                events = listOf(
                    ChangeEvent(
                        eventId = "42",
                        eventType = "project.parking_deleted",
                        eventVersion = "0.1",
                        tenantId = TENANT_A,
                        payload = mapOf(
                            "projectId" to PROJECT_A,
                            "parkingRecordId" to PARK_A,
                            "version" to 2.0,
                            "operationId" to REMOTE_DELETE,
                        ),
                    ),
                ),
                nextCursor = "42",
                hasMore = false,
            )
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        val synced = store.loadSyncState(TENANT_A)
        assertEquals("42", synced.changeCursor)
        assertEquals(parking(), synced.projectCache.parkingRecords.single { it.id == PARK_A })
        assertTrue(synced.projectCache.visibleParking(PROJECT_A).isEmpty())
        assertEquals(REMOTE_DELETE, synced.projectCache.parkingTombstones.single().lastOperationId)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertTrue(repository.snapshot(SESSION_A).cache.visibleParking(PROJECT_A).isEmpty())

        repository.activate(SESSION_B)
        assertFalse(repository.snapshot(SESSION_B).cache.parkingTombstones.any { it.parkingId == PARK_A })
        assertEquals(TENANT_B, repository.snapshot(SESSION_B).cache.activeProjects.single().tenantId)
    }

    private fun repository(store: SyncStateStore, service: ProjectService) = ProjectSyncRepository(
        service = service,
        legacyReplayer = NoOpLegacyQueueReplay,
        legacyDraftStore = NoOpDraftStore,
        syncStore = store,
        now = { NOW },
        coverMediaStore = InMemoryProjectCoverMediaStore(),
    )

    private fun state(tenantId: String = TENANT_A): TenantSyncState {
        val first = project(PROJECT_A, CLIENT_A, tenantId)
        val second = project(PROJECT_B, CLIENT_B, tenantId)
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = if (tenantId == TENANT_A) listOf(first, second) else listOf(first),
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = if (tenantId == TENANT_A) {
                    listOf(
                        ProjectPage(first, ProjectNotes(PROJECT_A, "", 1, NOW), parking()),
                        ProjectPage(second, ProjectNotes(PROJECT_B, "", 1, NOW), null),
                    )
                } else {
                    listOf(ProjectPage(first, ProjectNotes(PROJECT_A, "", 1, NOW), null))
                },
                parkingRecords = if (tenantId == TENANT_A) listOf(parking()) else emptyList(),
                parkingLoadedProjectIds = if (tenantId == TENANT_A) listOf(PROJECT_A, PROJECT_B) else listOf(PROJECT_A),
                resourceLinks = if (tenantId == TENANT_A) listOf(link(LINK_A), link(LINK_B)) else emptyList(),
                blocksLoaded = true,
            ),
            projectDraftsMigrated = true,
        )
    }

    private fun project(id: String, clientId: String, tenantId: String) = ProjectSummary(
        id = id,
        tenantId = tenantId,
        name = id.takeLast(4),
        lifecycle = ProjectLifecycle.ACTIVE,
        rowVersion = 1,
        createdAt = NOW,
        updatedAt = NOW,
        clientId = clientId,
        navigationKey = "00000000-0000-4000-8000-000000000099",
    )

    private fun link(id: String) = ProjectResourceLink(id, PROJECT_A, id.takeLast(4), "https://example.com", NOW, NOW)

    private fun parking() = ProjectParking(
        id = PARK_A,
        projectId = PROJECT_A,
        location = "authored location",
        nextAction = "authored next action",
        mustNotForget = "authored reminder",
        authoredByUserId = USER_A,
        createdAt = NOW,
        occurredAt = NOW,
        clientId = PARK_A,
        rowVersion = 1,
    )

    private class MemoryStore : SyncStateStore {
        private val states = mutableMapOf<String, TenantSyncState>()
        override fun loadSyncState(tenantId: String) = states[tenantId] ?: TenantSyncState()
        override fun saveSyncState(tenantId: String, state: TenantSyncState) {
            states[tenantId] = state
        }
        override fun clearSyncState(tenantId: String) {
            states.remove(tenantId)
        }
    }

    private class FarEnd {
        var projectOrder = AuthoritativeOrder(PROJECT_AGGREGATE, null, listOf(CLIENT_A, CLIENT_B), 1, null)
        val linkOrders = mutableMapOf(
            PROJECT_A to AuthoritativeOrder(LINK_AGGREGATE_A, PROJECT_A, listOf(LINK_A, LINK_B), 1, null),
            PROJECT_B to AuthoritativeOrder(LINK_AGGREGATE_B, PROJECT_B, emptyList(), 1, null),
        )
        val tombstones = mutableMapOf(PROJECT_A to emptyList<ParkingTombstone>(), PROJECT_B to emptyList())
        val changes = ArrayDeque<ChangePage>()
        val projectReorders = mutableListOf<InteractionMutation>()
        val linkReorders = mutableListOf<InteractionMutation>()
        val parkDeletes = mutableListOf<InteractionMutation>()
        var loseNextProjectOrderResponse = false
        var conflictNextProjectOrder = false

        @Suppress("UNCHECKED_CAST")
        fun service(): ProjectService = Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "changes" -> if (changes.isEmpty()) ChangePage(emptyList(), null, false) else changes.removeFirst()
                "projectOrder" -> projectOrder
                "linkOrder" -> linkOrders.getValue(args[1] as String)
                "parkingTombstones" -> tombstones[args[1] as String].orEmpty()
                "reorderProjects" -> {
                    val operation = args[1] as InteractionMutation
                    projectReorders += operation
                    if (conflictNextProjectOrder) {
                        conflictNextProjectOrder = false
                        throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale order"))
                    }
                    projectOrder = AuthoritativeOrder(
                        operation.aggregateId,
                        null,
                        operation.orderedClientIds,
                        projectOrder.version + 1,
                        operation.operationId,
                    )
                    if (loseNextProjectOrderResponse) {
                        loseNextProjectOrderResponse = false
                        throw ServiceException(503, ApiError(ApiErrorCode.UNKNOWN, "response lost"))
                    }
                    InteractionAcknowledgement.Order(
                        operation.operationId,
                        operation.aggregateId,
                        projectOrder.version,
                        null,
                        operation.orderedClientIds,
                    )
                }
                "reorderLinks" -> {
                    val operation = args[1] as InteractionMutation
                    linkReorders += operation
                    val projectId = requireNotNull(operation.projectId)
                    val next = AuthoritativeOrder(
                        operation.aggregateId,
                        projectId,
                        operation.orderedClientIds,
                        linkOrders.getValue(projectId).version + 1,
                        operation.operationId,
                    )
                    linkOrders[projectId] = next
                    InteractionAcknowledgement.Order(
                        operation.operationId,
                        operation.aggregateId,
                        next.version,
                        projectId,
                        operation.orderedClientIds,
                    )
                }
                "deleteParking" -> {
                    val operation = args[1] as InteractionMutation
                    parkDeletes += operation
                    val tombstone = ParkingTombstone(
                        requireNotNull(operation.targetId),
                        requireNotNull(operation.projectId),
                        operation.baseVersion + 1,
                        LATER,
                        operation.operationId,
                    )
                    tombstones[requireNotNull(operation.projectId)] = listOf(tombstone)
                    InteractionAcknowledgement.Parking(
                        operation.operationId,
                        operation.aggregateId,
                        tombstone.version,
                        tombstone.projectId,
                        tombstone.parkingId,
                        tombstone.deletedAt,
                        true,
                    )
                }
                else -> error("Unexpected Service call ${method.name}")
            }
        } as ProjectService
    }

    private companion object {
        const val TENANT_A = "00000000-0000-4000-8000-00000000000a"
        const val TENANT_B = "00000000-0000-4000-8000-00000000000b"
        const val USER_A = "00000000-0000-4000-8000-00000000000c"
        const val PROJECT_A = "10000000-0000-4000-8000-000000000001"
        const val PROJECT_B = "10000000-0000-4000-8000-000000000002"
        const val CLIENT_A = "11000000-0000-4000-8000-000000000001"
        const val CLIENT_B = "11000000-0000-4000-8000-000000000002"
        const val LINK_A = "20000000-0000-4000-8000-000000000001"
        const val LINK_B = "20000000-0000-4000-8000-000000000002"
        const val PARK_A = "30000000-0000-4000-8000-000000000001"
        const val PROJECT_AGGREGATE = "40000000-0000-4000-8000-000000000001"
        const val LINK_AGGREGATE_A = "40000000-0000-4000-8000-000000000002"
        const val LINK_AGGREGATE_B = "40000000-0000-4000-8000-000000000003"
        const val OP_PROJECT_ORDER = "50000000-0000-4000-8000-000000000001"
        const val OP_LINK_ORDER = "50000000-0000-4000-8000-000000000002"
        const val OP_DELETE_PARK = "50000000-0000-4000-8000-000000000003"
        const val REMOTE_DELETE = "50000000-0000-4000-8000-000000000004"
        const val NOW = "2026-10-01T10:00:00Z"
        const val LATER = "2026-10-01T10:01:00Z"
        val SESSION_A = Session(TENANT_A, "token-a")
        val SESSION_B = Session(TENANT_B, "token-b")
    }
}
