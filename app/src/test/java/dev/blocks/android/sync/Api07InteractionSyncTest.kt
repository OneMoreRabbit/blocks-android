package dev.blocks.android.sync

import dev.blocks.android.api.ApiError
import dev.blocks.android.api.ApiErrorCode
import dev.blocks.android.api.ServiceException
import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.AuthoritativeOrder
import dev.blocks.android.project.BackgroundSyncResult
import dev.blocks.android.project.InteractionAcknowledgement
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.NoteOrderState
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectItemAcknowledgement
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.visibleParking
import dev.blocks.android.project.orderedNotes
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Api07InteractionSyncTest {
    @Test
    fun unknownProjectInPagedOrderFeedHydratesAndAdvancesCursorAcrossRestart() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val third = project(PROJECT_C, CLIENT_C, TENANT_A)
        val farEnd = FarEnd().apply {
            projects += third
            projectOrder = AuthoritativeOrder(
                PROJECT_AGGREGATE,
                null,
                listOf(CLIENT_C, CLIENT_A, CLIENT_B),
                3,
                REMOTE_ORDER,
            )
            changes += ChangePage(
                listOf(change("41", "project.created", mapOf("projectId" to PROJECT_C))),
                "41",
                true,
            )
            changes += ChangePage(
                listOf(
                    change(
                        "42",
                        "project.order_changed",
                        mapOf("aggregateId" to PROJECT_AGGREGATE, "operationId" to REMOTE_ORDER),
                    ),
                ),
                "42",
                false,
            )
        }

        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        val synced = store.loadSyncState(TENANT_A)
        assertEquals("42", synced.changeCursor)
        assertEquals(listOf(PROJECT_C, PROJECT_A, PROJECT_B), synced.projectCache.projectOrder.orderedProjectIds)
        assertTrue(synced.projectCache.activeProjects.any { it.id == PROJECT_C })

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertEquals("42", repository.snapshot(SESSION_A).cursor)
        assertEquals(
            listOf(PROJECT_C, PROJECT_A, PROJECT_B),
            repository.snapshot(SESSION_A).cache.projectOrder.orderedProjectIds,
        )
    }

    @Test
    fun unknownProjectCreatedAndOrderedOnSamePageHydratesBeforeAggregateApplication() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val third = project(PROJECT_C, CLIENT_C, TENANT_A)
        val farEnd = FarEnd().apply {
            projects += third
            projectOrder = AuthoritativeOrder(
                PROJECT_AGGREGATE,
                null,
                listOf(CLIENT_A, CLIENT_C, CLIENT_B),
                2,
                REMOTE_ORDER,
            )
            changes += ChangePage(
                listOf(
                    change("61", "project.created", mapOf("projectId" to PROJECT_C)),
                    change(
                        "62",
                        "project.order_changed",
                        mapOf("aggregateId" to PROJECT_AGGREGATE, "operationId" to REMOTE_ORDER),
                    ),
                ),
                "62",
                false,
            )
        }
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.backgroundSync(SESSION_A)

        assertEquals("62", store.loadSyncState(TENANT_A).changeCursor)
        assertEquals(
            listOf(PROJECT_A, PROJECT_C, PROJECT_B),
            store.loadSyncState(TENANT_A).projectCache.projectOrder.orderedProjectIds,
        )
    }

    @Test
    fun inaccessibleProjectIdentityDoesNotEnterCacheOrBlockCursor() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd().apply {
            projectOrder = AuthoritativeOrder(
                PROJECT_AGGREGATE,
                null,
                listOf(CLIENT_A, FOREIGN_CLIENT, CLIENT_B),
                2,
                REMOTE_ORDER,
            )
            changes += ChangePage(
                listOf(
                    change(
                        "71",
                        "project.order_changed",
                        mapOf("aggregateId" to PROJECT_AGGREGATE, "operationId" to REMOTE_ORDER),
                    ),
                ),
                "71",
                false,
            )
        }
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.backgroundSync(SESSION_A)

        val durable = store.loadSyncState(TENANT_A)
        assertEquals("71", durable.changeCursor)
        assertFalse(durable.projectCache.activeProjects.any { it.clientId == FOREIGN_CLIENT })
        assertEquals(listOf(PROJECT_A, PROJECT_B), durable.projectCache.projectOrder.orderedProjectIds)
    }

    @Test
    fun knownAggregateIsPreparedAgainstCurrentVersionBeforeFirstDelivery() {
        val initial = state().let { state ->
            state.copy(
                projectCache = state.projectCache.copy(
                    projectOrder = dev.blocks.android.project.ProjectOrderState(
                        listOf(PROJECT_A, PROJECT_B),
                        1,
                        NOW,
                        PROJECT_AGGREGATE,
                    ),
                    interactionOperations = listOf(
                        InteractionMutation(
                            OP_PROJECT_ORDER,
                            dev.blocks.android.project.InteractionMutationType.REORDER_PROJECTS,
                            PROJECT_AGGREGATE,
                            orderedClientIds = listOf(CLIENT_B, CLIENT_A),
                            baseVersion = 1,
                            createdAt = NOW,
                        ),
                    ),
                ),
            )
        }
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            projectOrder = AuthoritativeOrder(PROJECT_AGGREGATE, null, listOf(CLIENT_A, CLIENT_B), 4, null)
        }
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.backgroundSync(SESSION_A)

        assertEquals(4, farEnd.projectReorders.single().baseVersion)
        assertTrue(store.loadSyncState(TENANT_A).projectCache.interactionOperations.isEmpty())
    }

    @Test
    fun acceptedOrderWithUnknownProjectRetiresOnceThenHydratesAcrossRestart() {
        val initial = state().let { state ->
            state.copy(
                projectCache = state.projectCache.copy(
                    projectOrder = dev.blocks.android.project.ProjectOrderState(
                        listOf(PROJECT_A, PROJECT_B),
                        1,
                        NOW,
                        PROJECT_AGGREGATE,
                    ),
                ),
            )
        }
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            projects += project(PROJECT_C, CLIENT_C, TENANT_A)
            projectOrder = AuthoritativeOrder(
                PROJECT_AGGREGATE,
                null,
                listOf(CLIENT_A, CLIENT_B, CLIENT_C),
                2,
                REMOTE_ORDER,
            )
            maxProjectOrderCallsPerOperation = 2
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), OP_PROJECT_ORDER)

        repository.backgroundSync(SESSION_A)

        val awaitingHydration = store.loadSyncState(TENANT_A).projectCache
        assertEquals(1, farEnd.projectReorders.count { it.operationId == OP_PROJECT_ORDER })
        assertTrue(awaitingHydration.interactionOperations.isEmpty())
        assertTrue(OP_PROJECT_ORDER in awaitingHydration.retiredInteractionOperationIds)
        assertFalse(awaitingHydration.activeProjectsLoaded)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        val converged = store.loadSyncState(TENANT_A).projectCache
        assertEquals(1, farEnd.projectReorders.count { it.operationId == OP_PROJECT_ORDER })
        assertEquals(listOf(PROJECT_B, PROJECT_A, PROJECT_C), converged.projectOrder.orderedProjectIds)
        assertTrue(converged.activeProjects.any { it.id == PROJECT_C })
    }

    @Test
    fun lifecycleTriggeredOrdersPrepareAfterServiceMembershipVersionChanges() {
        val initial = state().copy(
            projectCache = state().projectCache.copy(
                projectOrder = dev.blocks.android.project.ProjectOrderState(
                    listOf(PROJECT_A, PROJECT_B),
                    1,
                    NOW,
                    PROJECT_AGGREGATE,
                ),
            ),
        )
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd()
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.queueArchiveProject(SESSION_A, PROJECT_A, 1, OP_ARCHIVE)
        repository.backgroundSync(SESSION_A)
        val archivedVersion = repository.snapshot(SESSION_A).cache.archivedProjects.single { it.id == PROJECT_A }.rowVersion
        repository.queueReopenProject(SESSION_A, PROJECT_A, archivedVersion, OP_REOPEN)
        repository.backgroundSync(SESSION_A)
        repository.queueCreateProject(SESSION_A, "third", OP_CREATE, CLIENT_C)
        repository.backgroundSync(SESSION_A)

        assertEquals(listOf(2, 4, 6), farEnd.projectReorders.map { it.baseVersion })
        assertEquals(listOf(CLIENT_B, CLIENT_A, CLIENT_C), farEnd.projectOrder.orderedClientIds)
        assertTrue(repository.snapshot(SESSION_A).cache.interactionOperations.isEmpty())
    }

    @Test
    fun linkCreateAndDeleteOrdersPrepareAfterMembershipVersionChanges() {
        val initial = state().let { state ->
            state.copy(
                projectCache = state.projectCache.copy(
                    linkOrders = listOf(
                        LinkOrderState(PROJECT_A, listOf(LINK_A, LINK_B), 1, NOW, LINK_AGGREGATE_A),
                    ),
                ),
            )
        }
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd()
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.addResourceLink(
            SESSION_A,
            PROJECT_A,
            "third",
            "https://example.com/third",
            OP_CREATE_LINK,
            LINK_C,
        )
        repository.backgroundSync(SESSION_A)
        repository.deleteResourceLink(SESSION_A, LINK_C, OP_DELETE_LINK)
        repository.backgroundSync(SESSION_A)

        assertEquals(listOf(2, 4), farEnd.linkReorders.map { it.baseVersion })
        assertEquals(listOf(LINK_A, LINK_B), farEnd.linkOrders.getValue(PROJECT_A).orderedClientIds)
        assertEquals(
            listOf(
                listOf(LINK_A, LINK_B, LINK_C),
                listOf(LINK_A, LINK_B),
            ),
            farEnd.linkReorders.map { it.orderedClientIds },
        )
        assertTrue(repository.snapshot(SESSION_A).cache.itemOperations.isEmpty())
        assertTrue(repository.snapshot(SESSION_A).cache.interactionOperations.isEmpty())
    }

    @Test
    fun foregroundProjectOrderDuringAcknowledgementDoesNotResurrectRetiredOperation() {
        val initial = stateWithThirdProject()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            projects.clear()
            projects.addAll(initial.projectCache.activeProjects)
            projectOrder = AuthoritativeOrder(
                PROJECT_AGGREGATE,
                null,
                listOf(CLIENT_A, CLIENT_B, CLIENT_C),
                1,
                null,
            )
            blockFirstProjectReorder = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A, PROJECT_C), OP_PROJECT_ORDER)
        val executor = Executors.newSingleThreadExecutor()
        val sync = executor.submit<BackgroundSyncResult> { repository.backgroundSync(SESSION_A) }
        assertTrue(farEnd.projectReorderEntered.await(5, TimeUnit.SECONDS))

        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_C, PROJECT_B, PROJECT_A), OP_PROJECT_ORDER_B)
        farEnd.releaseProjectReorder.countDown()
        sync.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        val durable = store.loadSyncState(TENANT_A).projectCache
        assertEquals(listOf(OP_PROJECT_ORDER, OP_PROJECT_ORDER_B), farEnd.projectReorders.map { it.operationId })
        assertTrue(durable.interactionOperations.isEmpty())
        assertTrue(setOf(OP_PROJECT_ORDER, OP_PROJECT_ORDER_B).all(durable.retiredInteractionOperationIds::contains))
        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertEquals(listOf(PROJECT_C, PROJECT_B, PROJECT_A), repository.snapshot(SESSION_A).cache.projectOrder.orderedProjectIds)
    }

    @Test
    fun foregroundLinkOrderDuringAcknowledgementPreservesBothOperations() {
        val initial = stateWithThirdLink()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            linkOrders[PROJECT_A] = AuthoritativeOrder(
                LINK_AGGREGATE_A,
                PROJECT_A,
                listOf(LINK_A, LINK_B, LINK_C),
                1,
                null,
            )
            blockFirstLinkReorder = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.moveResourceLink(SESSION_A, LINK_B, -1, OP_LINK_ORDER)
        val executor = Executors.newSingleThreadExecutor()
        val sync = executor.submit<BackgroundSyncResult> { repository.backgroundSync(SESSION_A) }
        assertTrue(farEnd.linkReorderEntered.await(5, TimeUnit.SECONDS))

        repository.moveResourceLink(SESSION_A, LINK_C, -2, OP_LINK_ORDER_B)
        farEnd.releaseLinkReorder.countDown()
        sync.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        val durable = store.loadSyncState(TENANT_A).projectCache
        assertEquals(listOf(OP_LINK_ORDER, OP_LINK_ORDER_B), farEnd.linkReorders.map { it.operationId })
        assertTrue(durable.interactionOperations.isEmpty())
        assertTrue(setOf(OP_LINK_ORDER, OP_LINK_ORDER_B).all(durable.retiredInteractionOperationIds::contains))
        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertEquals(
            listOf(LINK_C, LINK_B, LINK_A),
            repository.snapshot(SESSION_A).cache.linkOrders.single { it.projectId == PROJECT_A }.orderedLinkIds,
        )
    }

    @Test
    fun remoteTombstoneAndConcurrentLocalDeleteBothPersistWithAdvancedCursor() {
        val initial = stateWithSecondPark()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            parkingRecords[PROJECT_A] = initial.projectCache.parkingRecords.toMutableList()
            tombstones[PROJECT_A] = listOf(
                ParkingTombstone(PARK_A, PROJECT_A, 2, LATER, REMOTE_DELETE),
            )
            changes += ChangePage(
                listOf(
                    change(
                        "51",
                        "project.parking_deleted",
                        mapOf(
                            "projectId" to PROJECT_A,
                            "parkingRecordId" to PARK_A,
                            "operationId" to REMOTE_DELETE,
                        ),
                    ),
                ),
                "51",
                false,
            )
            blockFirstTombstoneRead = true
            failSubsequentTombstoneReads = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        val executor = Executors.newSingleThreadExecutor()
        val sync = executor.submit<BackgroundSyncResult> { repository.backgroundSync(SESSION_A) }
        assertTrue(farEnd.tombstoneReadEntered.await(5, TimeUnit.SECONDS))

        repository.deleteParking(SESSION_A, PARK_B, OP_DELETE_PARK_B)
        farEnd.releaseTombstoneRead.countDown()
        sync.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        val durable = store.loadSyncState(TENANT_A)
        assertEquals("51", durable.changeCursor)
        assertEquals(setOf(PARK_A, PARK_B), durable.projectCache.parkingTombstones.map { it.parkingId }.toSet())
        assertTrue(durable.projectCache.parkingRecords.any { it.id == PARK_A })
        assertTrue(durable.projectCache.parkingRecords.any { it.id == PARK_B })
        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertTrue(repository.snapshot(SESSION_A).cache.visibleParking(PROJECT_A).isEmpty())
    }

    @Test
    fun provisionalParkDeleteRemapsToCanonicalIdentityAndSurvivesRestart() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd()
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.queueParking(
            SESSION_A,
            PROJECT_A,
            ParkingDraft("local location", "local next", "local reminder"),
            operationId = OP_ADD_PARK,
            clientId = LOCAL_PARK,
        )
        repository.queueParking(
            SESSION_A,
            PROJECT_A,
            ParkingDraft("newest location", "newest next", "newest reminder"),
            operationId = OP_ADD_PARK_B,
            clientId = LOCAL_PARK_B,
        )
        repository.deleteParking(SESSION_A, LOCAL_PARK, OP_DELETE_LOCAL_PARK)

        repository.backgroundSync(SESSION_A)

        assertEquals(SERVER_PARK, farEnd.parkDeletes.single().targetId)
        val durable = store.loadSyncState(TENANT_A).projectCache
        assertTrue(durable.interactionOperations.isEmpty())
        assertTrue(durable.parkingTombstones.any {
            it.parkingId == SERVER_PARK && it.lastOperationId == OP_DELETE_LOCAL_PARK
        })
        assertTrue(durable.parkingRecords.any {
            it.id == SERVER_PARK && it.clientId == LOCAL_PARK && it.location == "local location"
        })
        assertFalse(durable.visibleParking(PROJECT_A).any { it.id == SERVER_PARK })

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertFalse(repository.snapshot(SESSION_A).cache.visibleParking(PROJECT_A).any { it.id == SERVER_PARK })
    }

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
    fun preparedEnvelopeReplaysUnchangedAfterRestartWhenAggregateCannotRetireIt() {
        val store = MemoryStore().apply { saveSyncState(TENANT_A, state()) }
        val farEnd = FarEnd().apply {
            loseNextProjectOrderResponse = true
            hideAcceptedProjectOperationFromAggregate = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.reorderActiveProjects(SESSION_A, listOf(PROJECT_B, PROJECT_A), OP_PROJECT_ORDER)

        repository.backgroundSync(SESSION_A)
        val prepared = store.loadSyncState(TENANT_A).projectCache.interactionOperations.single()
        assertTrue(prepared.preparedForDelivery)
        assertTrue(prepared.deliveryUncertain)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        assertEquals(2, farEnd.projectReorders.size)
        assertEquals(
            farEnd.projectReorders.first().copy(deliveryUncertain = false),
            farEnd.projectReorders.last().copy(deliveryUncertain = false),
        )
        assertEquals(
            prepared.copy(deliveryUncertain = false),
            farEnd.projectReorders.last().copy(deliveryUncertain = false),
        )
        val converged = store.loadSyncState(TENANT_A).projectCache
        assertTrue(converged.interactionOperations.isEmpty())
        assertTrue(OP_PROJECT_ORDER in converged.retiredInteractionOperationIds)
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
    fun queuedPreActivationNoteOrderPreparesAndDeliversAfterProcessRestart() {
        val initial = stateWithNotes()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = initial.projectCache.noteItems.toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_A, NOTE_B),
            )
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.moveNoteItem(SESSION_A, NOTE_B, -1, OP_NOTE_ORDER)
        repository.deactivate(SESSION_A)

        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        val delivered = farEnd.noteReorders.single()
        assertEquals(OP_NOTE_ORDER, delivered.operationId)
        assertEquals("0.8", delivered.contractVersion)
        assertTrue(delivered.preparedForDelivery)
        assertEquals(NOTE_AGGREGATE_A, delivered.aggregateId)
        assertEquals(listOf(NOTE_B, NOTE_A), delivered.orderedClientIds)
        assertTrue(store.loadSyncState(TENANT_A).projectCache.interactionOperations.isEmpty())
        assertTrue(OP_NOTE_ORDER in store.loadSyncState(TENANT_A).projectCache.retiredInteractionOperationIds)
        assertEquals(
            listOf(NOTE_B, NOTE_A),
            store.loadSyncState(TENANT_A).projectCache.orderedNotes(PROJECT_A).map { it.id },
        )
    }

    @Test
    fun lostNoteOrderResponseReplaysTheExactPreparedEnvelopeWithoutSecondMutation() {
        val initial = stateWithNotes()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = initial.projectCache.noteItems.toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_A, NOTE_B),
            )
            loseNextNoteOrderResponse = true
            hideAcceptedNoteOperationFromAggregate = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.moveNoteItem(SESSION_A, NOTE_B, -1, OP_NOTE_ORDER)
        repository.backgroundSync(SESSION_A)

        val uncertain = store.loadSyncState(TENANT_A).projectCache.interactionOperations.single()
        assertTrue(uncertain.deliveryUncertain)
        assertTrue(uncertain.preparedForDelivery)

        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.backgroundSync(SESSION_A)

        assertEquals(2, farEnd.noteReorders.size)
        assertEquals(
            farEnd.noteReorders[0].copy(deliveryUncertain = false),
            farEnd.noteReorders[1].copy(deliveryUncertain = false),
        )
        assertEquals(1, farEnd.noteOrderMutationCount)
        assertTrue(store.loadSyncState(TENANT_A).projectCache.interactionOperations.isEmpty())
    }

    @Test
    fun staleNoteOrderConflictReadsAuthoritativeStateThenRetriesTheSameIdentity() {
        val initial = stateWithNotes()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = initial.projectCache.noteItems.toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_A, NOTE_B),
            )
            conflictNextNoteOrder = true
        }
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.moveNoteItem(SESSION_A, NOTE_B, -1, OP_NOTE_ORDER)

        repository.backgroundSync(SESSION_A)

        assertEquals(2, farEnd.noteReorders.size)
        assertEquals(listOf(OP_NOTE_ORDER, OP_NOTE_ORDER), farEnd.noteReorders.map { it.operationId })
        assertEquals(listOf(1, 2), farEnd.noteReorders.map { it.baseVersion })
        assertEquals(1, farEnd.noteOrderMutationCount)
        assertTrue(store.loadSyncState(TENANT_A).projectCache.interactionOperations.isEmpty())
        assertEquals(listOf(NOTE_B, NOTE_A), farEnd.noteOrders.getValue(PROJECT_A).orderedClientIds)
    }

    @Test
    fun noteOrderEventHydratesEveryUnknownMemberAndAdvancesCursorAtomically() {
        val initial = stateWithNotes()
        val remoteNote = note(NOTE_C)
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = (initial.projectCache.noteItems + remoteNote).toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_C, NOTE_A, NOTE_B),
                version = 2,
                operationId = REMOTE_NOTE_ORDER,
            )
            changes += ChangePage(
                listOf(
                    change(
                        "81",
                        "project.note_order_changed",
                        mapOf(
                            "projectId" to PROJECT_A,
                            "aggregateId" to NOTE_AGGREGATE_A,
                            "operationId" to REMOTE_NOTE_ORDER,
                        ),
                    ),
                ),
                "81",
                false,
            )
        }
        val repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)

        repository.backgroundSync(SESSION_A)

        val durable = store.loadSyncState(TENANT_A)
        assertEquals("81", durable.changeCursor)
        assertTrue(durable.projectCache.noteItems.any { it.id == NOTE_C })
        assertEquals(listOf(NOTE_C, NOTE_A, NOTE_B), durable.projectCache.orderedNotes(PROJECT_A).map { it.id })
    }

    @Test
    fun twoIndependentClientStoresConvergeThroughOneAuthoritativeNoteOrder() {
        val initial = stateWithNotes()
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = initial.projectCache.noteItems.toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_A, NOTE_B),
            )
        }
        val firstStore = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val secondStore = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val first = repository(firstStore, farEnd.service()).also { it.activate(SESSION_A) }
        val second = repository(secondStore, farEnd.service()).also { it.activate(SESSION_A) }

        first.moveNoteItem(SESSION_A, NOTE_B, -1, OP_NOTE_ORDER)
        first.backgroundSync(SESSION_A)
        second.backgroundSync(SESSION_A)

        assertEquals(
            firstStore.loadSyncState(TENANT_A).projectCache.orderedNotes(PROJECT_A).map { it.id },
            secondStore.loadSyncState(TENANT_A).projectCache.orderedNotes(PROJECT_A).map { it.id },
        )
        second.moveNoteItem(SESSION_A, NOTE_A, -1, OP_NOTE_ORDER_B)
        second.backgroundSync(SESSION_A)
        first.backgroundSync(SESSION_A)
        assertEquals(listOf(NOTE_A, NOTE_B), farEnd.noteOrders.getValue(PROJECT_A).orderedClientIds)
        assertEquals(
            firstStore.loadSyncState(TENANT_A).projectCache.orderedNotes(PROJECT_A).map { it.id },
            secondStore.loadSyncState(TENANT_A).projectCache.orderedNotes(PROJECT_A).map { it.id },
        )
    }

    @Test
    fun foregroundNoteOrderDuringAcknowledgementPreservesBothOperationsAcrossRestart() {
        val initial = stateWithNotes()
        val store = MemoryStore().apply { saveSyncState(TENANT_A, initial) }
        val farEnd = FarEnd().apply {
            noteItems[PROJECT_A] = initial.projectCache.noteItems.toMutableList()
            noteOrders[PROJECT_A] = noteOrders.getValue(PROJECT_A).copy(
                orderedClientIds = listOf(NOTE_A, NOTE_B),
            )
            blockFirstNoteReorder = true
        }
        var repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        repository.moveNoteItem(SESSION_A, NOTE_B, -1, OP_NOTE_ORDER)
        val executor = Executors.newSingleThreadExecutor()
        val sync = executor.submit<BackgroundSyncResult> { repository.backgroundSync(SESSION_A) }
        assertTrue(farEnd.noteReorderEntered.await(5, TimeUnit.SECONDS))

        repository.moveNoteItem(SESSION_A, NOTE_A, -1, OP_NOTE_ORDER_B)
        farEnd.releaseNoteReorder.countDown()
        sync.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals(listOf(OP_NOTE_ORDER, OP_NOTE_ORDER_B), farEnd.noteReorders.map { it.operationId })
        assertTrue(store.loadSyncState(TENANT_A).projectCache.interactionOperations.isEmpty())
        repository.deactivate(SESSION_A)
        repository = repository(store, farEnd.service())
        repository.activate(SESSION_A)
        assertEquals(
            listOf(NOTE_A, NOTE_B),
            repository.snapshot(SESSION_A).cache.orderedNotes(PROJECT_A).map { it.id },
        )
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

    private fun stateWithThirdProject(): TenantSyncState {
        val base = state()
        val third = project(PROJECT_C, CLIENT_C, TENANT_A)
        return base.copy(
            projectCache = base.projectCache.copy(
                activeProjects = base.projectCache.activeProjects + third,
                pages = base.projectCache.pages + ProjectPage(third, ProjectNotes(PROJECT_C, "", 1, NOW), null),
                parkingLoadedProjectIds = base.projectCache.parkingLoadedProjectIds + PROJECT_C,
                projectOrder = dev.blocks.android.project.ProjectOrderState(
                    listOf(PROJECT_A, PROJECT_B, PROJECT_C),
                    1,
                    NOW,
                    PROJECT_AGGREGATE,
                ),
            ),
        )
    }

    private fun stateWithThirdLink(): TenantSyncState {
        val base = state()
        return base.copy(
            projectCache = base.projectCache.copy(
                resourceLinks = base.projectCache.resourceLinks + link(LINK_C),
                linkOrders = listOf(
                    LinkOrderState(PROJECT_A, listOf(LINK_A, LINK_B, LINK_C), 1, NOW, LINK_AGGREGATE_A),
                    LinkOrderState(PROJECT_B, emptyList(), 1, NOW, LINK_AGGREGATE_B),
                ),
            ),
        )
    }

    private fun stateWithNotes(): TenantSyncState {
        val base = state()
        return base.copy(
            projectCache = base.projectCache.copy(
                noteItems = listOf(note(NOTE_A), note(NOTE_B)),
                noteOrders = listOf(
                    NoteOrderState(PROJECT_A, listOf(NOTE_A, NOTE_B), 1, NOW, NOTE_AGGREGATE_A),
                ),
            ),
        )
    }

    private fun stateWithSecondPark(): TenantSyncState {
        val base = state()
        val second = parking().copy(
            id = PARK_B,
            clientId = PARK_B,
            location = "second location",
            nextAction = "second next",
            mustNotForget = "second reminder",
            occurredAt = LATER,
            createdAt = LATER,
        )
        return base.copy(
            projectCache = base.projectCache.copy(
                parkingRecords = base.projectCache.parkingRecords + second,
                pages = base.projectCache.pages.map {
                    if (it.project.id == PROJECT_A) it.copy(newestParking = second) else it
                },
            ),
        )
    }

    private fun change(id: String, type: String, payload: Map<String, Any?>) = ChangeEvent(
        eventId = id,
        eventType = type,
        eventVersion = if (type in setOf(
                "project.order_changed",
                "project.link_order_changed",
                "project.note_order_changed",
                "project.parking_deleted",
            )
        ) "0.1" else "0.7",
        tenantId = TENANT_A,
        payload = payload,
    )

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

    private fun note(id: String) = ProjectNoteItem(
        id = id,
        projectId = PROJECT_A,
        body = id.takeLast(4),
        pinned = false,
        authoredByUserId = USER_A,
        createdAt = NOW,
        updatedAt = NOW,
        rowVersion = 1,
    )

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
        val projects = mutableListOf(
            projectStatic(PROJECT_A, CLIENT_A),
            projectStatic(PROJECT_B, CLIENT_B),
        )
        val parkingRecords = mutableMapOf(PROJECT_A to mutableListOf(parkingStatic()))
        val resourceLinks = mutableMapOf(
            PROJECT_A to mutableListOf(linkStatic(LINK_A), linkStatic(LINK_B)),
        )
        var projectOrder = AuthoritativeOrder(PROJECT_AGGREGATE, null, listOf(CLIENT_A, CLIENT_B), 1, null)
        val linkOrders = mutableMapOf(
            PROJECT_A to AuthoritativeOrder(LINK_AGGREGATE_A, PROJECT_A, listOf(LINK_A, LINK_B), 1, null),
            PROJECT_B to AuthoritativeOrder(LINK_AGGREGATE_B, PROJECT_B, emptyList(), 1, null),
            PROJECT_C to AuthoritativeOrder(LINK_AGGREGATE_C, PROJECT_C, emptyList(), 1, null),
        )
        val noteItems = mutableMapOf<String, MutableList<ProjectNoteItem>>()
        val noteOrders = mutableMapOf(
            PROJECT_A to AuthoritativeOrder(
                NOTE_AGGREGATE_A,
                PROJECT_A,
                emptyList(),
                1,
                null,
                dev.blocks.android.project.InteractionMutationType.REORDER_NOTES,
            ),
            PROJECT_B to AuthoritativeOrder(
                NOTE_AGGREGATE_B,
                PROJECT_B,
                emptyList(),
                1,
                null,
                dev.blocks.android.project.InteractionMutationType.REORDER_NOTES,
            ),
            PROJECT_C to AuthoritativeOrder(
                NOTE_AGGREGATE_C,
                PROJECT_C,
                emptyList(),
                1,
                null,
                dev.blocks.android.project.InteractionMutationType.REORDER_NOTES,
            ),
        )
        val tombstones = mutableMapOf(
            PROJECT_A to emptyList<ParkingTombstone>(),
            PROJECT_B to emptyList(),
            PROJECT_C to emptyList(),
        )
        val changes = ArrayDeque<ChangePage>()
        val projectReorders = mutableListOf<InteractionMutation>()
        val linkReorders = mutableListOf<InteractionMutation>()
        val noteReorders = mutableListOf<InteractionMutation>()
        val parkDeletes = mutableListOf<InteractionMutation>()
        val createdLinks = mutableListOf<ProjectItemMutation>()
        val deletedLinks = mutableListOf<ProjectItemMutation>()
        var loseNextProjectOrderResponse = false
        var hideAcceptedProjectOperationFromAggregate = false
        var maxProjectOrderCallsPerOperation = Int.MAX_VALUE
        private val projectOrderReceipts = mutableMapOf<
            String,
            Pair<InteractionMutation, InteractionAcknowledgement.Order>,
            >()
        private val linkOrderReceipts = mutableMapOf<
            String,
            Pair<InteractionMutation, InteractionAcknowledgement.Order>,
            >()
        private val noteOrderReceipts = mutableMapOf<
            String,
            Pair<InteractionMutation, InteractionAcknowledgement.Order>,
            >()
        private val parkDeleteReceipts = mutableMapOf<
            String,
            Pair<InteractionMutation, InteractionAcknowledgement.Parking>,
            >()
        var conflictNextProjectOrder = false
        var conflictNextNoteOrder = false
        var loseNextNoteOrderResponse = false
        var hideAcceptedNoteOperationFromAggregate = false
        var noteOrderMutationCount = 0
        var blockFirstNoteReorder = false
        val noteReorderEntered = CountDownLatch(1)
        val releaseNoteReorder = CountDownLatch(1)
        var blockFirstProjectReorder = false
        val projectReorderEntered = CountDownLatch(1)
        val releaseProjectReorder = CountDownLatch(1)
        var blockFirstLinkReorder = false
        val linkReorderEntered = CountDownLatch(1)
        val releaseLinkReorder = CountDownLatch(1)
        var blockFirstTombstoneRead = false
        var failSubsequentTombstoneReads = false
        val tombstoneReadEntered = CountDownLatch(1)
        val releaseTombstoneRead = CountDownLatch(1)
        var tombstoneReads = 0

        @Suppress("UNCHECKED_CAST")
        fun service(): ProjectService = Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "listProjects" -> projects.filter { it.lifecycle == args[1] }
                "projectPage" -> {
                    val project = projects.single { it.id == args[1] }
                    ProjectPage(
                        project,
                        ProjectNotes(project.id, "", 1, project.updatedAt),
                        parkingRecords[project.id].orEmpty().maxByOrNull { it.occurredAt },
                    )
                }
                "archiveProject" -> {
                    val projectId = args[1] as String
                    val index = projects.indexOfFirst { it.id == projectId }
                    val updated = projects[index].copy(
                        lifecycle = ProjectLifecycle.ARCHIVED,
                        rowVersion = projects[index].rowVersion + 1,
                        updatedAt = LATER,
                        archivedAt = LATER,
                    )
                    projects[index] = updated
                    projectOrder = projectOrder.copy(
                        orderedClientIds = projectOrder.orderedClientIds - requireNotNull(updated.clientId),
                        version = projectOrder.version + 1,
                    )
                    updated
                }
                "reopenProject" -> {
                    val projectId = args[1] as String
                    val index = projects.indexOfFirst { it.id == projectId }
                    val updated = projects[index].copy(
                        lifecycle = ProjectLifecycle.ACTIVE,
                        rowVersion = projects[index].rowVersion + 1,
                        updatedAt = LATER,
                        archivedAt = null,
                    )
                    projects[index] = updated
                    projectOrder = projectOrder.copy(
                        orderedClientIds = projectOrder.orderedClientIds + requireNotNull(updated.clientId),
                        version = projectOrder.version + 1,
                    )
                    updated
                }
                "createProject" -> {
                    val clientId = args[2] as String
                    val created = projectStatic(PROJECT_C, clientId).copy(name = args[1] as String)
                    projects += created
                    projectOrder = projectOrder.copy(
                        orderedClientIds = projectOrder.orderedClientIds + clientId,
                        version = projectOrder.version + 1,
                    )
                    created
                }
                "blocks" -> emptyList<Any>()
                "parking" -> parkingRecords[args[1] as String].orEmpty()
                "addParking" -> {
                    val projectId = args[1] as String
                    val draft = args[2] as ParkingDraft
                    val clientId = args[4] as String
                    val serverId = if (clientId == LOCAL_PARK) SERVER_PARK else SERVER_PARK_B
                    ProjectParking(
                        serverId,
                        projectId,
                        draft.location,
                        draft.nextAction,
                        draft.mustNotForget,
                        USER_A,
                        args[5] as String? ?: NOW,
                        args[5] as String? ?: NOW,
                        clientId,
                        rowVersion = 1,
                    ).also { parkingRecords.getOrPut(projectId, ::mutableListOf).add(it) }
                }
                "createLink" -> {
                    val operation = args[1] as ProjectItemMutation
                    createdLinks += operation
                    val link = ProjectResourceLink(
                        operation.targetId,
                        operation.projectId,
                        requireNotNull(operation.linkTitle),
                        requireNotNull(operation.linkUri),
                        NOW,
                        NOW,
                        rowVersion = 1,
                        lastOperationId = operation.operationId,
                    )
                    resourceLinks.getOrPut(operation.projectId, ::mutableListOf).add(link)
                    val current = linkOrders.getValue(operation.projectId)
                    linkOrders[operation.projectId] = current.copy(
                        orderedClientIds = current.orderedClientIds + operation.targetId,
                        version = current.version + 1,
                    )
                    ProjectItemAcknowledgement.Link(operation.operationId, link)
                }
                "deleteLink" -> {
                    val operation = args[1] as ProjectItemMutation
                    deletedLinks += operation
                    val links = resourceLinks.getValue(operation.projectId)
                    val index = links.indexOfFirst { it.id == operation.targetId }
                    val deleted = links[index].copy(
                        updatedAt = LATER,
                        rowVersion = links[index].rowVersion + 1,
                        deletedAt = LATER,
                        lastOperationId = operation.operationId,
                    )
                    links[index] = deleted
                    val current = linkOrders.getValue(operation.projectId)
                    linkOrders[operation.projectId] = current.copy(
                        orderedClientIds = current.orderedClientIds - operation.targetId,
                        version = current.version + 1,
                    )
                    ProjectItemAcknowledgement.Link(operation.operationId, deleted)
                }
                "changes" -> if (changes.isEmpty()) ChangePage(emptyList(), null, false) else changes.removeFirst()
                "projectOrder" -> projectOrder
                "linkOrder" -> linkOrders.getValue(args[1] as String)
                "noteOrder" -> noteOrders.getValue(args[1] as String)
                "readNote" -> noteItems.getValue(args[1] as String).single { it.id == args[2] as String }
                "parkingTombstones" -> {
                    tombstoneReads += 1
                    if (blockFirstTombstoneRead && tombstoneReads == 1) {
                        tombstoneReadEntered.countDown()
                        check(releaseTombstoneRead.await(5, TimeUnit.SECONDS))
                    } else if (failSubsequentTombstoneReads) {
                        throw ServiceException(503, ApiError(ApiErrorCode.UNKNOWN, "offline after event read"))
                    }
                    tombstones[args[1] as String].orEmpty()
                }
                "reorderProjects" -> {
                    val operation = args[1] as InteractionMutation
                    projectReorders += operation
                    if (projectReorders.count { it.operationId == operation.operationId } > maxProjectOrderCallsPerOperation) {
                        throw AssertionError("Project order operation replay exceeded its bounded request count")
                    }
                    if (blockFirstProjectReorder && projectReorders.size == 1) {
                        projectReorderEntered.countDown()
                        check(releaseProjectReorder.await(5, TimeUnit.SECONDS))
                    }
                    val receipt = projectOrderReceipts[operation.operationId]
                    if (receipt != null) {
                        if (receipt.first.copy(deliveryUncertain = false) != operation.copy(deliveryUncertain = false)) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "operation identity reused"))
                        }
                        receipt.second
                    } else {
                        if (conflictNextProjectOrder) {
                            conflictNextProjectOrder = false
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale order"))
                        }
                        if (operation.aggregateId != projectOrder.aggregateId || operation.baseVersion != projectOrder.version) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale order"))
                        }
                        projectOrder = AuthoritativeOrder(
                            operation.aggregateId,
                            null,
                            operation.orderedClientIds,
                            projectOrder.version + 1,
                            operation.operationId,
                        )
                        val acknowledgement = InteractionAcknowledgement.Order(
                            operation.operationId,
                            operation.aggregateId,
                            projectOrder.version,
                            null,
                            operation.orderedClientIds,
                        )
                        projectOrderReceipts[operation.operationId] = operation to acknowledgement
                        if (loseNextProjectOrderResponse) {
                            loseNextProjectOrderResponse = false
                            if (hideAcceptedProjectOperationFromAggregate) {
                                projectOrder = projectOrder.copy(operationId = REMOTE_ORDER)
                            }
                            throw ServiceException(503, ApiError(ApiErrorCode.UNKNOWN, "response lost"))
                        }
                        acknowledgement
                    }
                }
                "reorderLinks" -> {
                    val operation = args[1] as InteractionMutation
                    linkReorders += operation
                    if (blockFirstLinkReorder && linkReorders.size == 1) {
                        linkReorderEntered.countDown()
                        check(releaseLinkReorder.await(5, TimeUnit.SECONDS))
                    }
                    val receipt = linkOrderReceipts[operation.operationId]
                    if (receipt != null) {
                        if (receipt.first.copy(deliveryUncertain = false) != operation.copy(deliveryUncertain = false)) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "operation identity reused"))
                        }
                        receipt.second
                    } else {
                        val projectId = requireNotNull(operation.projectId)
                        if (
                            operation.aggregateId != linkOrders.getValue(projectId).aggregateId ||
                            operation.baseVersion != linkOrders.getValue(projectId).version
                        ) throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale link order"))
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
                        ).also { linkOrderReceipts[operation.operationId] = operation to it }
                    }
                }
                "reorderNotes" -> {
                    val operation = args[1] as InteractionMutation
                    noteReorders += operation
                    if (blockFirstNoteReorder && noteReorders.size == 1) {
                        noteReorderEntered.countDown()
                        check(releaseNoteReorder.await(5, TimeUnit.SECONDS))
                    }
                    val receipt = noteOrderReceipts[operation.operationId]
                    if (receipt != null) {
                        if (receipt.first.copy(deliveryUncertain = false) != operation.copy(deliveryUncertain = false)) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "operation identity reused"))
                        }
                        receipt.second
                    } else {
                        val projectId = requireNotNull(operation.projectId)
                        if (conflictNextNoteOrder) {
                            conflictNextNoteOrder = false
                            val current = noteOrders.getValue(projectId)
                            noteOrders[projectId] = current.copy(
                                orderedClientIds = current.orderedClientIds.reversed(),
                                version = current.version + 1,
                                operationId = REMOTE_NOTE_ORDER,
                            )
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale Note order"))
                        }
                        val current = noteOrders.getValue(projectId)
                        if (operation.aggregateId != current.aggregateId || operation.baseVersion != current.version) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale Note order"))
                        }
                        noteOrderMutationCount += 1
                        val next = current.copy(
                            orderedClientIds = operation.orderedClientIds,
                            version = current.version + 1,
                            operationId = operation.operationId,
                        )
                        noteOrders[projectId] = next
                        val acknowledgement = InteractionAcknowledgement.Order(
                            operation.operationId,
                            operation.aggregateId,
                            next.version,
                            projectId,
                            operation.orderedClientIds,
                            dev.blocks.android.project.InteractionMutationType.REORDER_NOTES,
                        )
                        noteOrderReceipts[operation.operationId] = operation to acknowledgement
                        if (loseNextNoteOrderResponse) {
                            loseNextNoteOrderResponse = false
                            if (hideAcceptedNoteOperationFromAggregate) {
                                noteOrders[projectId] = next.copy(operationId = REMOTE_NOTE_ORDER)
                            }
                            throw ServiceException(503, ApiError(ApiErrorCode.UNKNOWN, "response lost"))
                        }
                        acknowledgement
                    }
                }
                "deleteParking" -> {
                    val operation = args[1] as InteractionMutation
                    parkDeletes += operation
                    val receipt = parkDeleteReceipts[operation.operationId]
                    if (receipt != null) {
                        if (receipt.first.copy(deliveryUncertain = false) != operation.copy(deliveryUncertain = false)) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "operation identity reused"))
                        }
                        receipt.second
                    } else {
                        val authoritative = parkingRecords[requireNotNull(operation.projectId)]
                            .orEmpty().single { it.id == operation.targetId }
                        if (operation.baseVersion != authoritative.rowVersion) {
                            throw ServiceException(409, ApiError(ApiErrorCode.CONFLICT, "stale Park"))
                        }
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
                        ).also { parkDeleteReceipts[operation.operationId] = operation to it }
                    }
                }
                else -> error("Unexpected Service call ${method.name}")
            }
        } as ProjectService

        companion object {
            private fun projectStatic(id: String, clientId: String) = ProjectSummary(
                id, TENANT_A, id.takeLast(4), ProjectLifecycle.ACTIVE, 1, NOW, NOW,
                clientId = clientId,
                navigationKey = "00000000-0000-4000-8000-000000000099",
            )

            private fun parkingStatic() = ProjectParking(
                PARK_A, PROJECT_A, "authored location", "authored next action", "authored reminder",
                USER_A, NOW, NOW, PARK_A, rowVersion = 1,
            )

            private fun linkStatic(id: String) = ProjectResourceLink(
                id, PROJECT_A, id.takeLast(4), "https://example.com", NOW, NOW, rowVersion = 1,
            )

            private fun noteStatic(id: String) = ProjectNoteItem(
                id, PROJECT_A, id.takeLast(4), false, USER_A, NOW, NOW, rowVersion = 1,
            )
        }
    }

    private companion object {
        const val TENANT_A = "00000000-0000-4000-8000-00000000000a"
        const val TENANT_B = "00000000-0000-4000-8000-00000000000b"
        const val USER_A = "00000000-0000-4000-8000-00000000000c"
        const val PROJECT_A = "10000000-0000-4000-8000-000000000001"
        const val PROJECT_B = "10000000-0000-4000-8000-000000000002"
        const val PROJECT_C = "10000000-0000-4000-8000-000000000003"
        const val CLIENT_A = "11000000-0000-4000-8000-000000000001"
        const val CLIENT_B = "11000000-0000-4000-8000-000000000002"
        const val CLIENT_C = "11000000-0000-4000-8000-000000000003"
        const val LINK_A = "20000000-0000-4000-8000-000000000001"
        const val LINK_B = "20000000-0000-4000-8000-000000000002"
        const val LINK_C = "20000000-0000-4000-8000-000000000003"
        const val NOTE_A = "21000000-0000-4000-8000-000000000001"
        const val NOTE_B = "21000000-0000-4000-8000-000000000002"
        const val NOTE_C = "21000000-0000-4000-8000-000000000003"
        const val PARK_A = "30000000-0000-4000-8000-000000000001"
        const val PARK_B = "30000000-0000-4000-8000-000000000002"
        const val LOCAL_PARK = "30000000-0000-4000-8000-000000000011"
        const val LOCAL_PARK_B = "30000000-0000-4000-8000-000000000012"
        const val SERVER_PARK = "30000000-0000-4000-8000-000000000021"
        const val SERVER_PARK_B = "30000000-0000-4000-8000-000000000022"
        const val PROJECT_AGGREGATE = "40000000-0000-4000-8000-000000000001"
        const val LINK_AGGREGATE_A = "40000000-0000-4000-8000-000000000002"
        const val LINK_AGGREGATE_B = "40000000-0000-4000-8000-000000000003"
        const val LINK_AGGREGATE_C = "40000000-0000-4000-8000-000000000004"
        const val NOTE_AGGREGATE_A = "41000000-0000-4000-8000-000000000001"
        const val NOTE_AGGREGATE_B = "41000000-0000-4000-8000-000000000002"
        const val NOTE_AGGREGATE_C = "41000000-0000-4000-8000-000000000003"
        const val OP_PROJECT_ORDER = "50000000-0000-4000-8000-000000000001"
        const val OP_LINK_ORDER = "50000000-0000-4000-8000-000000000002"
        const val OP_DELETE_PARK = "50000000-0000-4000-8000-000000000003"
        const val REMOTE_DELETE = "50000000-0000-4000-8000-000000000004"
        const val OP_PROJECT_ORDER_B = "50000000-0000-4000-8000-000000000005"
        const val OP_ADD_PARK = "50000000-0000-4000-8000-000000000006"
        const val OP_ADD_PARK_B = "50000000-0000-4000-8000-000000000007"
        const val OP_DELETE_LOCAL_PARK = "50000000-0000-4000-8000-000000000008"
        const val REMOTE_ORDER = "50000000-0000-4000-8000-000000000009"
        const val OP_LINK_ORDER_B = "50000000-0000-4000-8000-00000000000a"
        const val OP_DELETE_PARK_B = "50000000-0000-4000-8000-00000000000b"
        const val OP_ARCHIVE = "50000000-0000-4000-8000-00000000000c"
        const val OP_REOPEN = "50000000-0000-4000-8000-00000000000d"
        const val OP_CREATE = "50000000-0000-4000-8000-00000000000e"
        const val OP_CREATE_LINK = "50000000-0000-4000-8000-00000000000f"
        const val OP_DELETE_LINK = "50000000-0000-4000-8000-000000000010"
        const val OP_NOTE_ORDER = "50000000-0000-4000-8000-000000000011"
        const val OP_NOTE_ORDER_B = "50000000-0000-4000-8000-000000000012"
        const val REMOTE_NOTE_ORDER = "50000000-0000-4000-8000-000000000013"
        const val FOREIGN_CLIENT = "f0000000-0000-4000-8000-000000000001"
        const val NOW = "2026-10-01T10:00:00Z"
        const val LATER = "2026-10-01T10:01:00Z"
        val SESSION_A = Session(TENANT_A, "token-a")
        val SESSION_B = Session(TENANT_B, "token-b")
    }
}
