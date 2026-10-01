package dev.blocks.android.sync

import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.DashboardOrderMode
import dev.blocks.android.project.InteractionMutationType
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.dashboardProjects
import dev.blocks.android.project.orderedResourceLinks
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectInteractionOrderingTest {
    @Test
    fun linkOrderAppendsMovesPreservesEditAndRemovesDeleteAcrossRestart() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        var repository = repository(store)
        repository.activate(SESSION_A)

        repository.addResourceLink(SESSION_A, PROJECT_A, "First", "https://example.com/1", "create-1", LINK_A)
        repository.addResourceLink(SESSION_A, PROJECT_A, "Second", "https://example.com/2", "create-2", LINK_B)
        assertEquals(listOf(LINK_A, LINK_B), store.cache(TENANT_A).orderedResourceLinks(PROJECT_A).map { it.id })

        repository.moveResourceLink(SESSION_A, LINK_B, -1, "order-links")
        repository.editResourceLink(SESSION_A, LINK_A, "First edited", "https://example.com/edited", "edit-1")
        assertEquals(listOf(LINK_B, LINK_A), store.cache(TENANT_A).orderedResourceLinks(PROJECT_A).map { it.id })

        repository.deleteResourceLink(SESSION_A, LINK_B, "delete-2")
        val durable = store.cache(TENANT_A)
        assertEquals(listOf(LINK_A), durable.orderedResourceLinks(PROJECT_A).map { it.id })
        assertTrue(durable.resourceLinks.single { it.id == LINK_B }.deletedAt != null)
        val lastOrder = durable.interactionOperations.last { it.type == InteractionMutationType.REORDER_LINKS }
        assertEquals(listOf(LINK_A), lastOrder.orderedClientIds)
        assertTrue("delete-2" in lastOrder.dependsOnOperationIds)
        assertEquals(4, durable.linkOrders.single { it.projectId == PROJECT_A }.version)

        repository.deactivate(SESSION_A)
        repository = repository(store)
        repository.activate(SESSION_A)
        assertEquals(listOf(LINK_A), repository.snapshot(SESSION_A).cache.orderedResourceLinks(PROJECT_A).map { it.id })
        assertEquals(4, repository.snapshot(SESSION_A).pendingInteractionOperations)
    }

    @Test
    fun directDragCanCommitAnArbitraryLinkMoveAsOneAtomicOrderOperation() {
        val initial = state().copy(
            projectCache = state().projectCache.copy(
                resourceLinks = listOf(link(LINK_A, "A"), link(LINK_B, "B"), link(LINK_C, "C")),
            ),
        )
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val repository = repository(store)
        repository.activate(SESSION_A)

        repository.moveResourceLink(SESSION_A, LINK_A, 2, "drag-order")

        val cache = store.cache(TENANT_A)
        assertEquals(listOf(LINK_B, LINK_C, LINK_A), cache.orderedResourceLinks(PROJECT_A).map { it.id })
        val operations = cache.interactionOperations.filter { it.type == InteractionMutationType.REORDER_LINKS }
        assertEquals(1, operations.size)
        assertEquals("drag-order", operations.single().operationId)
        assertEquals(listOf(LINK_B, LINK_C, LINK_A), operations.single().orderedClientIds)
    }

    @Test
    fun linkReorderRollsBackStateAndQueueWhenTheAtomicStoreCommitFails() {
        val initial = state().copy(
            projectCache = state().projectCache.copy(
                resourceLinks = listOf(link(LINK_A, "A"), link(LINK_B, "B")),
            ),
        )
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val repository = repository(store)
        repository.activate(SESSION_A)
        val before = store.loadSyncState(TENANT_A)

        store.failNextSave = true
        assertThrows(IllegalStateException::class.java) {
            repository.moveResourceLink(SESSION_A, LINK_B, -1, "failed-order")
        }

        assertEquals(before, store.loadSyncState(TENANT_A))
        assertFalse(store.cache(TENANT_A).interactionOperations.any { it.operationId == "failed-order" })
    }

    @Test
    fun customProjectOrderIsVersionedDeterministicAndTenantIsolated() {
        val store = MemoryStore().apply {
            saveSyncState(TENANT_A, state())
            saveSyncState(TENANT_B, state(TENANT_B))
        }
        var repository = repository(store)
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), "project-order-1")
        repository.updateUiPreferences(SESSION_A) { it.copy(dashboardOrderMode = DashboardOrderMode.CUSTOM) }

        val first = store.loadSyncState(TENANT_A)
        assertEquals(listOf(PROJECT_B, PROJECT_A), first.projectCache.projectOrder.orderedProjectIds)
        assertEquals(1, first.projectCache.projectOrder.version)
        assertEquals(DashboardOrderMode.CUSTOM, first.projectUiPreferences.dashboardOrderMode)

        assertThrows(IllegalArgumentException::class.java) {
            repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_A, PROJECT_B), "project-order-1")
        }

        repository.activate(SESSION_B)
        assertTrue(repository.snapshot(SESSION_B).cache.interactionOperations.isEmpty())
        assertEquals(DashboardOrderMode.NAME, repository.uiPreferences(SESSION_B).dashboardOrderMode)

        repository = repository(store)
        repository.activate(SESSION_A)
        assertEquals(listOf(PROJECT_B, PROJECT_A), repository.snapshot(SESSION_A).cache.projectOrder.orderedProjectIds)
        assertEquals(DashboardOrderMode.CUSTOM, repository.uiPreferences(SESSION_A).dashboardOrderMode)
    }

    @Test
    fun projectLifecycleMaintainsAppendRemoveOrderAndQueuesAfterEachMutation() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val repository = repository(store)
        repository.activate(SESSION_A)

        repository.queueArchiveProject(SESSION_A, PROJECT_A, 1, "archive-a")
        var cache = store.cache(TENANT_A)
        assertEquals(listOf(PROJECT_B), cache.projectOrder.orderedProjectIds)
        assertTrue(cache.interactionOperations.last().dependsOnOperationIds.contains("archive-a"))

        repository.queueReopenProject(SESSION_A, PROJECT_A, 1, "reopen-a")
        cache = store.cache(TENANT_A)
        assertEquals(listOf(PROJECT_B, PROJECT_A), cache.projectOrder.orderedProjectIds)
        assertTrue(cache.interactionOperations.last().dependsOnOperationIds.contains("reopen-a"))

        repository.queueCreateProject(SESSION_A, "New Project", "create-c", PROJECT_C)
        cache = store.cache(TENANT_A)
        assertEquals(listOf(PROJECT_B, PROJECT_A, PROJECT_C), cache.projectOrder.orderedProjectIds)
        assertTrue(cache.interactionOperations.last().dependsOnOperationIds.contains("create-c"))
        assertEquals(3, cache.projectOrder.version)
    }

    @Test
    fun exactProjectOrderReplayIsConvergentAndEveryQueuedEnvelopeRetainsItsStaleBase() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val repository = repository(store)
        repository.activate(SESSION_A)

        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), "order-1")
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_A, PROJECT_B), "order-2")
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_A, PROJECT_B), "order-2")

        val operations = store.cache(TENANT_A).interactionOperations.filter {
            it.type == InteractionMutationType.REORDER_PROJECTS
        }
        assertEquals(2, operations.size)
        assertEquals(0, operations[0].baseVersion)
        assertEquals(1, operations[1].baseVersion)
        assertEquals(listOf("order-1"), operations[1].dependsOnOperationIds)
        assertEquals(2, store.cache(TENANT_A).projectOrder.version)
    }

    @Test
    fun nameAndLastActivityAreStableProjectionsWithoutChangingCustomOrder() {
        val cache = state().projectCache.copy(
            activeProjects = listOf(
                project(PROJECT_A, "zeta", updatedAt = "2026-09-01T00:00:00Z"),
                project(PROJECT_B, "Alpha", updatedAt = "2026-09-02T00:00:00Z"),
            ),
            noteItems = listOf(
                ProjectNoteItem(
                    id = NOTE_A,
                    projectId = PROJECT_A,
                    body = "Recent",
                    pinned = false,
                    authoredByUserId = null,
                    createdAt = NOW,
                    updatedAt = "2026-09-10T00:00:00Z",
                ),
            ),
        ).let { it.copy(projectOrder = it.projectOrder.copy(orderedProjectIds = listOf(PROJECT_B, PROJECT_A))) }

        assertEquals(listOf(PROJECT_B, PROJECT_A), cache.dashboardProjects(DashboardOrderMode.NAME).map { it.id })
        assertEquals(listOf(PROJECT_A, PROJECT_B), cache.dashboardProjects(DashboardOrderMode.LAST_ACTIVITY).map { it.id })
        assertEquals(listOf(PROJECT_B, PROJECT_A), cache.dashboardProjects(DashboardOrderMode.CUSTOM).map { it.id })
        assertEquals(listOf(PROJECT_B, PROJECT_A), cache.projectOrder.orderedProjectIds)
    }

    @Test
    fun parkingDeleteCreatesAnIdentifierOnlyTombstoneAndNeverChangesTheAuthoredRow() {
        val older = parking(PARK_A, "2026-09-01T00:00:00Z")
        val newest = parking(PARK_B, "2026-09-02T00:00:00Z")
        val initial = state().let { loaded ->
            loaded.copy(
                projectCache = loaded.projectCache.copy(
                    parkingRecords = listOf(older, newest),
                    parkingLoadedProjectIds = listOf(PROJECT_A),
                    pages = loaded.projectCache.pages.map {
                        if (it.project.id == PROJECT_A) it.copy(newestParking = newest) else it
                    },
                ),
            )
        }
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        var repository = repository(store)
        repository.activate(SESSION_A)

        repository.deleteParking(SESSION_A, PARK_B, "delete-park")
        val durable = store.cache(TENANT_A)
        assertEquals(newest, durable.parkingRecords.single { it.id == PARK_B })
        assertEquals(listOf(PARK_A), repository.parkingHistory(SESSION_A, PROJECT_A, 0, 10).records.map { it.id })
        assertEquals(PARK_A, repository.projectPage(SESSION_A, PROJECT_A).newestParking?.id)
        val tombstone = durable.parkingTombstones.single()
        assertEquals(PARK_B, tombstone.parkingId)
        assertEquals(1, tombstone.version)
        val operation = durable.interactionOperations.single { it.type == InteractionMutationType.DELETE_PARKING }
        assertEquals(PARK_B, operation.targetId)
        assertTrue(operation.orderedClientIds.isEmpty())

        repository.deactivate(SESSION_A)
        repository = repository(store)
        repository.activate(SESSION_A)
        assertEquals(listOf(PARK_A), repository.parkingHistory(SESSION_A, PROJECT_A, 0, 10).records.map { it.id })
        repository.deleteParking(SESSION_A, PARK_B, "unused-replay-id")
        assertEquals(1, store.cache(TENANT_A).parkingTombstones.size)
        assertEquals(1, store.cache(TENANT_A).interactionOperations.count { it.type == InteractionMutationType.DELETE_PARKING })
    }

    private fun repository(store: MemoryStore) = ProjectSyncRepository(
        service = noTransportService(),
        legacyReplayer = NoOpLegacyQueueReplay,
        legacyDraftStore = NoOpDraftStore,
        syncStore = store,
        now = { NOW },
        coverMediaStore = InMemoryProjectCoverMediaStore(),
    )

    private fun state(tenantId: String = TENANT_A): TenantSyncState {
        val projects = listOf(project(PROJECT_A, "zeta", tenantId), project(PROJECT_B, "Alpha", tenantId))
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = projects,
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = projects.map { ProjectPage(it, ProjectNotes(it.id, "", 1, NOW), null) },
                blocksLoaded = true,
            ),
            projectDraftsMigrated = true,
        )
    }

    private fun project(
        id: String,
        name: String,
        tenantId: String = TENANT_A,
        updatedAt: String = NOW,
    ) = ProjectSummary(id, tenantId, name, ProjectLifecycle.ACTIVE, 1, NOW, updatedAt, clientId = id)

    private fun link(id: String, title: String) = dev.blocks.android.project.ProjectResourceLink(
        id, PROJECT_A, title, "https://example.com/$id", NOW, NOW,
    )

    private fun parking(id: String, occurredAt: String) = ProjectParking(
        id = id,
        projectId = PROJECT_A,
        location = "Bench $id",
        nextAction = "Fit part $id",
        mustNotForget = "Keep note $id",
        authoredByUserId = "user-a",
        createdAt = occurredAt,
        occurredAt = occurredAt,
        clientId = id,
    )

    @Suppress("UNCHECKED_CAST")
    private fun noTransportService(): ProjectService = Proxy.newProxyInstance(
        ProjectService::class.java.classLoader,
        arrayOf(ProjectService::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "changes" -> ChangePage(emptyList(), null, false)
            else -> error("Local interaction called Service.${method.name}")
        }
    } as ProjectService

    private class MemoryStore : SyncStateStore {
        private val values = mutableMapOf<String, TenantSyncState>()
        var failNextSave = false

        override fun loadSyncState(tenantId: String) = values[tenantId] ?: TenantSyncState()

        override fun saveSyncState(tenantId: String, state: TenantSyncState) {
            if (failNextSave) {
                failNextSave = false
                error("simulated atomic rollback")
            }
            values[tenantId] = state
        }

        override fun clearSyncState(tenantId: String) {
            values.remove(tenantId)
        }

        fun cache(tenantId: String) = loadSyncState(tenantId).projectCache
    }

    private companion object {
        const val TENANT_A = "00000000-0000-0000-0000-00000000000a"
        const val TENANT_B = "00000000-0000-0000-0000-00000000000b"
        const val PROJECT_A = "10000000-0000-4000-8000-000000000001"
        const val PROJECT_B = "10000000-0000-4000-8000-000000000002"
        const val PROJECT_C = "10000000-0000-4000-8000-000000000003"
        const val LINK_A = "20000000-0000-4000-8000-000000000001"
        const val LINK_B = "20000000-0000-4000-8000-000000000002"
        const val LINK_C = "20000000-0000-4000-8000-000000000003"
        const val NOTE_A = "30000000-0000-4000-8000-000000000001"
        const val PARK_A = "40000000-0000-4000-8000-000000000001"
        const val PARK_B = "40000000-0000-4000-8000-000000000002"
        const val NOW = "2026-09-30T10:00:00Z"
        val SESSION_A = Session(TENANT_A, "token-a")
        val SESSION_B = Session(TENANT_B, "token-b")
    }
}
