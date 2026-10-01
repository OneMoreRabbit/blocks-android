package dev.blocks.android.state

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.NoteItemDraft
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.ProjectResourceLinkDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.DashboardOrderMode
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.InteractionMutationType
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectItemAcknowledgement
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectOrderState
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.cover.InMemoryProjectCoverMediaStore
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.project.noteItemDraftKey
import dev.blocks.android.project.resourceLinkDraftKey
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import dev.blocks.android.tag.TagActivationMedium
import java.security.KeyStore
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNormalizedSyncStateStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidSyncStateStore
        get() = AndroidSyncStateStore(context)

    @Before
    @After
    fun clear() {
        listOf(TENANT, FAILED_TENANT, OTHER_TENANT, BEFORE_MARKER_TENANT, AFTER_MARKER_TENANT).forEach {
            store.clearSyncState(it)
        }
    }

    @Test
    fun migratesReleasedEncryptedStateThenRetiresOnlyThatSnapshotAndKey() {
        val released = state(TENANT)
        val other = state(OTHER_TENANT, projectId = OTHER_PROJECT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(TENANT, released)
        LegacyEncryptedSyncStateStore(context).saveSyncState(OTHER_TENANT, other)

        val migrated = store.loadSyncState(TENANT)

        assertEquals(released, migrated)
        assertTrue(normalizedDatabase(TENANT).isFile)
        assertFalse(releasedDatabase(TENANT).exists())
        assertFalse(keyStore().containsAlias(releasedKeyAlias(TENANT)))
        assertTrue(releasedDatabase(OTHER_TENANT).isFile)
        assertTrue(keyStore().containsAlias(releasedKeyAlias(OTHER_TENANT)))
        assertEquals(other, LegacyEncryptedSyncStateStore(context).loadSyncState(OTHER_TENANT))
    }

    @Test
    fun failedCutoverLeavesReleasedSnapshotAndKeyRetryable() {
        val released = state(FAILED_TENANT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(FAILED_TENANT, released)
        val failing = AndroidSyncStateStore(
            context,
            migrationVerifier = { _, _ -> error("simulated cutover check") },
        )

        assertTrue(runCatching { failing.loadSyncState(FAILED_TENANT) }.isFailure)
        assertFalse(normalizedDatabase(FAILED_TENANT).exists())
        assertTrue(releasedDatabase(FAILED_TENANT).isFile)
        assertTrue(keyStore().containsAlias(releasedKeyAlias(FAILED_TENANT)))
        assertEquals(released, store.loadSyncState(FAILED_TENANT))
    }

    @Test
    fun processDeathAfterReplacementCommitRebuildsAndReverifiesFromReleasedSource() {
        val released = state(BEFORE_MARKER_TENANT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(BEFORE_MARKER_TENANT, released)
        val interrupted = AndroidSyncStateStore(context, migrationCheckpoint = { checkpoint ->
            if (checkpoint == MigrationCheckpoint.NORMALIZED_COMMITTED) throw SimulatedProcessDeath()
        })

        assertTrue(runCatching { interrupted.loadSyncState(BEFORE_MARKER_TENANT) }.isFailure)
        assertTrue(normalizedDatabase(BEFORE_MARKER_TENANT).isFile)
        assertFalse(hasAuthoritativeMarker(BEFORE_MARKER_TENANT))
        assertTrue(releasedDatabase(BEFORE_MARKER_TENANT).isFile)
        assertTrue(keyStore().containsAlias(releasedKeyAlias(BEFORE_MARKER_TENANT)))

        assertEquals(released, store.loadSyncState(BEFORE_MARKER_TENANT))
        assertTrue(hasAuthoritativeMarker(BEFORE_MARKER_TENANT))
        assertFalse(releasedDatabase(BEFORE_MARKER_TENANT).exists())
        assertFalse(keyStore().containsAlias(releasedKeyAlias(BEFORE_MARKER_TENANT)))
    }

    @Test
    fun processDeathAfterVerifiedMarkerCompletesReleasedStoreRetirementOnRestart() {
        val released = state(AFTER_MARKER_TENANT)
        LegacyEncryptedSyncStateStore(context).saveSyncState(AFTER_MARKER_TENANT, released)
        val interrupted = AndroidSyncStateStore(context, migrationCheckpoint = { checkpoint ->
            if (checkpoint == MigrationCheckpoint.VERIFIED_MARKER_COMMITTED) throw SimulatedProcessDeath()
        })

        assertTrue(runCatching { interrupted.loadSyncState(AFTER_MARKER_TENANT) }.isFailure)
        assertTrue(normalizedDatabase(AFTER_MARKER_TENANT).isFile)
        assertTrue(hasAuthoritativeMarker(AFTER_MARKER_TENANT))
        assertTrue(releasedDatabase(AFTER_MARKER_TENANT).isFile)

        assertEquals(released, store.loadSyncState(AFTER_MARKER_TENANT))
        assertFalse(releasedDatabase(AFTER_MARKER_TENANT).exists())
        assertFalse(keyStore().containsAlias(releasedKeyAlias(AFTER_MARKER_TENANT)))
    }

    @Test
    fun releasedSchemaNineDiscardsSingletonNotesAndDraftsBeforeFirstExplicitItemAcrossRestart() {
        val retainedParking = ProjectMutation(
            operationId = "retain-parking",
            type = ProjectMutationType.ADD_PARKING,
            targetId = PROJECT,
            idempotencyKey = "retain-parking",
            clientResourceId = "retain-parking",
            parking = ParkingDraft("Bench", "Continue assembly", "Keep the screws"),
            localCreatedAt = NOW,
        )
        val initial = state(TENANT).copy(
            projectCache = state(TENANT).projectCache.copy(
                pages = state(TENANT).projectCache.pages.mapIndexed { index, page ->
                    if (index == 0) page.copy(notes = page.notes.copy(body = "Pending singleton note"))
                    else page.copy(notes = page.notes.copy(body = ""))
                },
                noteItems = emptyList(),
                resourceLinks = emptyList(),
                itemOperations = emptyList(),
                retiredItemOperationIds = emptyList(),
                commands = listOf(retainedParking) + state(TENANT).projectCache.commands,
            ),
            projectDrafts = FormDrafts(projectNotes = mapOf(PROJECT to "Unsubmitted pending edit")),
        )
        val otherTenant = state(OTHER_TENANT, OTHER_PROJECT).copy(
            projectDrafts = FormDrafts(projectNotes = mapOf(OTHER_PROJECT to "Other tenant draft")),
        )
        store.saveSyncState(TENANT, initial)
        store.saveSyncState(OTHER_TENANT, otherTenant)
        SQLiteDatabase.openDatabase(normalizedDatabase(TENANT).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            AndroidSyncStateStore.PROJECT_ITEM_TABLES.forEach { db.execSQL("DROP TABLE $it") }
            AndroidSyncStateStore.INTERACTION_TABLES.forEach { db.execSQL("DROP TABLE $it") }
            db.execSQL(
                "UPDATE ${AndroidSyncStateStore.TABLE_METADATA} SET value = '9' WHERE record_id = ?",
                arrayOf(AndroidSyncStateStore.META_SCHEMA_VERSION),
            )
            db.version = 1
        }

        val migrated = store.loadSyncState(TENANT)
        assertTrue(migrated.projectCache.noteItems.isEmpty())
        assertTrue(migrated.projectCache.itemOperations.isEmpty())
        assertTrue(migrated.projectDrafts.projectNoteItems.isEmpty())
        assertTrue(migrated.projectDrafts.projectNotes.isEmpty())
        assertEquals(listOf(retainedParking), migrated.projectCache.commands)
        assertTrue(
            initial.projectCache.commands.single { it.type == ProjectMutationType.SAVE_NOTES }.operationId in
                migrated.projectCache.retiredOperationIds,
        )
        assertTrue(migrated.projectCache.projectsAwaitingNotesBaseline.isEmpty())
        assertEquals(otherTenant, store.loadSyncState(OTHER_TENANT))
        assertEquals(migrated, store.loadSyncState(TENANT))

        val service = java.lang.reflect.Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, _ -> error("Unexpected Service call: ${method.name}") } as ProjectService
        val session = Session(TENANT, "test-token")
        val repository = ProjectSyncRepository(
            service,
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        repository.addNoteItem(session, PROJECT, "First explicit 0.8 note", "create-item", NOTE_ID)
        val afterCreate = store.loadSyncState(TENANT).projectCache
        assertEquals(listOf(NOTE_ID), afterCreate.noteItems.map { it.id })
        assertEquals("First explicit 0.8 note", afterCreate.noteItems.single().body)
        assertEquals("create-item", afterCreate.itemOperations.single().operationId)
        assertEquals(afterCreate, store.loadSyncState(TENANT).projectCache)
    }

    @Test
    fun realDatabaseKeepsDependentItemClosedThenAtomicallyHandsOffAcknowledgedVersionAcrossRestart() {
        store.saveSyncState(TENANT, state(TENANT).withoutCommands())
        val service = java.lang.reflect.Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, _ -> error("Unexpected Service call: ${method.name}") } as ProjectService
        val session = Session(TENANT, "test-token")
        val noteId = "70000000-0000-4000-8000-000000000077"
        var repository = ProjectSyncRepository(
            service,
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        repository.addNoteItem(session, PROJECT, "Created", "create-item", noteId)
        repository.editNoteItem(session, noteId, "Edited", "edit-item")
        repository.recordProjectItemDeliveryUncertain(session, "create-item")

        var persisted = store.loadSyncState(TENANT).projectCache
        assertTrue(persisted.itemOperations.first().deliveryUncertain)
        assertEquals("create-item", persisted.itemOperations.last().dependsOnOperationId)
        assertEquals(null, persisted.itemOperations.last().ifMatch)

        repository = ProjectSyncRepository(
            service,
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(session)
        repository.acknowledgeProjectItem(
            session,
            ProjectItemAcknowledgement.Note(
                "create-item",
                ProjectNoteItem(
                    noteId,
                    PROJECT,
                    "Created",
                    false,
                    "00000000-0000-4000-8000-000000000077",
                    NOW,
                    NOW,
                    rowVersion = 7,
                ),
            ),
        )

        persisted = store.loadSyncState(TENANT).projectCache
        assertEquals(listOf("create-item"), persisted.retiredItemOperationIds)
        assertEquals("edit-item", persisted.itemOperations.single().operationId)
        assertEquals(null, persisted.itemOperations.single().dependsOnOperationId)
        assertEquals(7, persisted.itemOperations.single().ifMatch)
        assertEquals("Edited", persisted.noteItems.single { it.id == noteId }.body)
        assertEquals(7, persisted.noteItems.single { it.id == noteId }.rowVersion)
    }

    @Test
    fun realDatabaseRejectsVersionedItemWithoutIfMatchOrVersionProducingPrerequisite() {
        val base = state(TENANT).withoutCommands()
        val note = ProjectNoteItem(
            NOTE_ID,
            PROJECT,
            "Local edit",
            false,
            "local",
            NOW,
            NOW,
            rowVersion = 0,
        )
        val invalidEdit = ProjectItemMutation(
            operationId = "edit-without-version",
            type = ProjectItemMutationType.EDIT_NOTE,
            targetId = NOTE_ID,
            projectId = PROJECT,
            noteBody = "Local edit",
            createdAt = NOW,
        )
        val invalid = base.copy(
            projectCache = base.projectCache.copy(
                noteItems = listOf(note),
                itemOperations = listOf(invalidEdit),
            ),
        )

        assertTrue(runCatching { store.saveSyncState(TENANT, invalid) }.isFailure)

        val create = ProjectItemMutation(
            operationId = "create-prerequisite",
            type = ProjectItemMutationType.CREATE_NOTE,
            targetId = NOTE_ID,
            projectId = PROJECT,
            clientResourceId = NOTE_ID,
            noteBody = "Initial body",
            createdAt = NOW,
        )
        val valid = invalid.copy(
            projectCache = invalid.projectCache.copy(
                itemOperations = listOf(create, invalidEdit.copy(dependsOnOperationId = create.operationId)),
            ),
        )
        store.saveSyncState(TENANT, valid)
        assertEquals(valid, store.loadSyncState(TENANT))
    }

    @Test
    fun realDatabaseRetainsApi06ProvenanceAndNonconformingLegacyLinkForReconciliation() {
        val initial = state(TENANT).withoutCommands().withExistingItems()
        val retained = initial.copy(
            projectCache = initial.projectCache.copy(
                noteItems = initial.projectCache.noteItems.map {
                    it.copy(lastOperationId = "00000000-0000-4000-8000-0000000000a1")
                },
                resourceLinks = initial.projectCache.resourceLinks.map {
                    it.copy(
                        uri = "https://example.com/café",
                        lastOperationId = "00000000-0000-4000-8000-0000000000b1",
                        requiresReconciliation = true,
                    )
                },
            ),
        )

        store.saveSyncState(TENANT, retained)
        val restored = AndroidSyncStateStore(context).loadSyncState(TENANT).projectCache

        assertEquals("00000000-0000-4000-8000-0000000000a1", restored.noteItems.single().lastOperationId)
        assertEquals("00000000-0000-4000-8000-0000000000b1", restored.resourceLinks.single().lastOperationId)
        assertEquals("https://example.com/café", restored.resourceLinks.single().uri)
        assertTrue(restored.resourceLinks.single().requiresReconciliation)
    }

    @Test
    fun notesSubmitTouchesOnlyNotesAndQueueRowsInOneTransaction() {
        val initial = state(TENANT).copy(projectCache = state(TENANT).projectCache.copy(commands = emptyList()))
        store.saveSyncState(TENANT, initial)
        installAuditTriggers(TENANT)
        val command = ProjectMutation(
            operationId = "notes-operation",
            type = ProjectMutationType.SAVE_NOTES,
            targetId = PROJECT,
            ifMatch = 2,
            notesBody = "Changed notes",
            localCreatedAt = NOW,
        )
        val updated = initial.copy(
            projectCache = initial.projectCache.copy(
                pages = initial.projectCache.pages.map { page ->
                    if (page.project.id == PROJECT) {
                        page.copy(notes = page.notes.copy(body = "Changed notes", updatedAt = NOW))
                    } else {
                        page
                    }
                },
                commands = listOf(command),
            ),
        )

        store.saveProjectMutation(TENANT, initial, updated, ProjectMutationType.SAVE_NOTES)

        assertEquals(updated, store.loadSyncState(TENANT))
        assertEquals(
            setOf(AndroidSyncStateStore.TABLE_PROJECT_NOTES, AndroidSyncStateStore.TABLE_PROJECT_COMMANDS),
            auditedTables(TENANT),
        )
    }

    @Test
    fun createTouchesOnlyItsStructuralIdentityAndQueueRows() {
        val initial = state(TENANT).withoutCommands()
        store.saveSyncState(TENANT, initial)
        installAuditTriggers(TENANT)
        val updated = initial.withCreatedProject()

        store.saveProjectMutation(TENANT, initial, updated, ProjectMutationType.CREATE_PROJECT)

        assertEquals(updated, store.loadSyncState(TENANT))
        assertEquals(
            setOf(
                AndroidSyncStateStore.TABLE_METADATA,
                AndroidSyncStateStore.TABLE_ACTIVE_PROJECTS,
                AndroidSyncStateStore.TABLE_PROJECT_PAGES,
                AndroidSyncStateStore.TABLE_PROJECT_NOTES,
                AndroidSyncStateStore.TABLE_PROJECT_LINKS,
                AndroidSyncStateStore.TABLE_PROVISIONAL_PROJECTS,
                AndroidSyncStateStore.TABLE_PARKING_LOADED_PROJECTS,
                AndroidSyncStateStore.TABLE_PROJECT_COMMANDS,
            ),
            auditedTables(TENANT),
        )
    }

    @Test
    fun parkTouchesOnlyItsAppendAndQueueRows() {
        val initial = state(TENANT).withoutCommands()
        store.saveSyncState(TENANT, initial)
        installAuditTriggers(TENANT)
        val updated = initial.withAddedParking()

        store.saveProjectMutation(TENANT, initial, updated, ProjectMutationType.ADD_PARKING)

        assertEquals(updated, store.loadSyncState(TENANT))
        assertEquals(
            setOf(AndroidSyncStateStore.TABLE_PARKING_RECORDS, AndroidSyncStateStore.TABLE_PROJECT_COMMANDS),
            auditedTables(TENANT),
        )
    }

    @Test
    fun createFailureCommitsNeitherDomainNorQueueRows() {
        assertMutationRollsBack(ProjectMutationType.CREATE_PROJECT) { it.withCreatedProject() }
    }

    @Test
    fun parkFailureCommitsNeitherDomainNorQueueRows() {
        assertMutationRollsBack(ProjectMutationType.ADD_PARKING) { it.withAddedParking() }
    }

    @Test
    fun everyProjectItemActionTouchesOnlyItsDomainRowAndOrderedOperationRow() {
        ProjectItemMutationType.entries.forEachIndexed { index, type ->
            store.clearSyncState(TENANT)
            val initial = state(TENANT).withoutCommands().withExistingItems().withItemDraft(type)
            store.saveSyncState(TENANT, initial)
            installAuditTriggers(TENANT)
            val updated = initial.withItemMutation(type, index)

            store.saveProjectItemMutation(TENANT, initial, updated, type)

            assertEquals(updated, store.loadSyncState(TENANT))
            assertEquals(
                "Unexpected write set for $type",
                buildSet {
                    add(
                    if (type.name.endsWith("NOTE")) {
                        AndroidSyncStateStore.TABLE_PROJECT_NOTE_ITEMS
                    } else {
                        AndroidSyncStateStore.TABLE_PROJECT_RESOURCE_LINKS
                    })
                    add(AndroidSyncStateStore.TABLE_PROJECT_ITEM_OPERATIONS)
                    if (type in setOf(
                            ProjectItemMutationType.CREATE_NOTE,
                            ProjectItemMutationType.EDIT_NOTE,
                            ProjectItemMutationType.CREATE_LINK,
                            ProjectItemMutationType.EDIT_LINK,
                        )
                    ) add(AndroidSyncStateStore.TABLE_PROJECT_DRAFTS)
                },
                auditedTables(TENANT),
            )
        }
    }

    @Test
    fun everyProjectItemActionRollsBackItsDomainRowAndOperationTogether() {
        ProjectItemMutationType.entries.forEachIndexed { index, type ->
            store.clearSyncState(TENANT)
            val initial = state(TENANT).withoutCommands().withExistingItems().withItemDraft(type)
            store.saveSyncState(TENANT, initial)
            installAuditTriggers(TENANT)
            val failing = AndroidSyncStateStore(context, beforeItemMutationCommit = { attempted ->
                if (attempted == type) error("simulated item commit failure")
            })

            assertTrue(
                runCatching {
                    failing.saveProjectItemMutation(TENANT, initial, initial.withItemMutation(type, index), type)
                }.isFailure,
            )
            assertEquals(initial, store.loadSyncState(TENANT))
            assertTrue(auditedTables(TENANT).isEmpty())
        }
    }

    @Test
    fun ordinaryRowsAreSandboxPlaintextWhileLegacyTagTokenRemainsProtected() {
        val state = state(TENANT)
        store.saveSyncState(TENANT, state)

        val bytes = normalizedDatabase(TENANT).readBytes()
        assertTrue(bytes.containsBytes("Workshop shelves".toByteArray()))
        assertTrue(bytes.containsBytes("Bring the charger".toByteArray()))
        assertFalse(bytes.containsBytes(TAG_TOKEN.encoded.toByteArray()))
        assertTrue(keyStore().containsAlias(secretKeyAlias(TENANT)))
    }

    @Test
    fun tenantPartitionsRemainIndependentAndClearIsScoped() {
        val first = state(TENANT)
        val second = state(OTHER_TENANT, projectId = OTHER_PROJECT)
        store.saveSyncState(TENANT, first)
        store.saveSyncState(OTHER_TENANT, second)

        store.clearSyncState(TENANT)

        assertEquals(TenantSyncState(), store.loadSyncState(TENANT))
        assertEquals(second, store.loadSyncState(OTHER_TENANT))
        assertFalse(normalizedDatabase(TENANT).exists())
        assertTrue(normalizedDatabase(OTHER_TENANT).exists())
    }

    @Test
    fun realDatabaseMigratesSchemaTenThenSeedsInteractionAggregatesOnActivation() {
        val legacy = state(TENANT).withoutCommands().copy(
            projectCache = state(TENANT).withoutCommands().projectCache.copy(
                resourceLinks = listOf(
                    ProjectResourceLink(LINK_ID, PROJECT, "Reference", "https://example.com", NOW, NOW),
                ),
            ),
        )
        store.saveSyncState(TENANT, legacy)
        SQLiteDatabase.openDatabase(normalizedDatabase(TENANT).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            AndroidSyncStateStore.INTERACTION_TABLES.forEach { db.execSQL("DROP TABLE $it") }
            db.execSQL(
                "UPDATE ${AndroidSyncStateStore.TABLE_METADATA} SET value = '10' WHERE record_id = ?",
                arrayOf(AndroidSyncStateStore.META_SCHEMA_VERSION),
            )
            db.version = 2
        }

        val migrated = AndroidSyncStateStore(context).loadSyncState(TENANT)
        assertEquals(ProjectOrderState(), migrated.projectCache.projectOrder)
        assertTrue(migrated.projectCache.linkOrders.isEmpty())

        val service = java.lang.reflect.Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, _ -> error("Unexpected Service call: ${method.name}") } as ProjectService
        val repository = ProjectSyncRepository(
            service,
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            AndroidSyncStateStore(context),
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        repository.activate(Session(TENANT, "test-token"))

        val seeded = AndroidSyncStateStore(context).loadSyncState(TENANT)
        assertEquals(listOf(PROJECT), seeded.projectCache.projectOrder.orderedProjectIds)
        assertEquals(listOf(LINK_ID), seeded.projectCache.linkOrders.single().orderedLinkIds)
        assertEquals(seeded, AndroidSyncStateStore(context).loadSyncState(TENANT))
    }

    @Test
    fun realDatabasePersistsInteractionStateAndRollsBackTheWholeMutationOnFailure() {
        val base = state(TENANT).withoutCommands().copy(
            projectCache = state(TENANT).withoutCommands().projectCache.copy(
                resourceLinks = listOf(
                    ProjectResourceLink(LINK_ID, PROJECT, "Reference", "https://example.com", NOW, NOW),
                ),
            ),
        )
        store.saveSyncState(TENANT, base)
        val parkingId = "$PROJECT-parking"
        val operation = InteractionMutation(
            operationId = "delete-parking-09",
            type = InteractionMutationType.DELETE_PARKING,
            aggregateId = "parking:$parkingId",
            projectId = PROJECT,
            targetId = parkingId,
            baseVersion = 0,
            createdAt = NOW,
        )
        val updated = base.copy(
            projectUiPreferences = ProjectUiPreferences(dashboardOrderMode = DashboardOrderMode.CUSTOM),
            projectCache = base.projectCache.copy(
                projectOrder = ProjectOrderState(listOf(PROJECT), 1, NOW),
                linkOrders = listOf(LinkOrderState(PROJECT, listOf(LINK_ID), 1, NOW)),
                parkingTombstones = listOf(ParkingTombstone(parkingId, PROJECT, 1, NOW, operation.operationId)),
                interactionOperations = listOf(operation),
            ),
        )

        store.saveSyncState(TENANT, base, updated)
        assertEquals(updated, AndroidSyncStateStore(context).loadSyncState(TENANT))

        store.saveSyncState(TENANT, base)
        val service = java.lang.reflect.Proxy.newProxyInstance(
            ProjectService::class.java.classLoader,
            arrayOf(ProjectService::class.java),
        ) { _, method, _ -> error("Unexpected Service call: ${method.name}") } as ProjectService
        val repository = ProjectSyncRepository(
            service,
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            now = { NOW },
            coverMediaStore = InMemoryProjectCoverMediaStore(),
        )
        val session = Session(TENANT, "test-token")
        repository.activate(session)
        val beforeFailedDelete = store.loadSyncState(TENANT)
        SQLiteDatabase.openDatabase(normalizedDatabase(TENANT).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TRIGGER fail_interaction_insert BEFORE INSERT ON " +
                    "${AndroidSyncStateStore.TABLE_INTERACTION_OPERATIONS} " +
                    "BEGIN SELECT RAISE(ABORT, 'simulated interaction failure'); END",
            )
        }
        assertTrue(runCatching { repository.deleteParking(session, parkingId, "failed-delete") }.isFailure)
        assertEquals(beforeFailedDelete, AndroidSyncStateStore(context).loadSyncState(TENANT))
    }

    @Test
    fun realDatabasePersistsProjectOrderUsingWireClientIdentitiesWithoutReplacingLocalProjectIds() {
        val firstClientId = "11000000-0000-4000-8000-000000000001"
        val secondProjectId = "12000000-0000-4000-8000-000000000002"
        val secondClientId = "11000000-0000-4000-8000-000000000002"
        val first = state(TENANT).withoutCommands().projectCache.activeProjects.single().copy(
            clientId = firstClientId,
        )
        val second = first.copy(id = secondProjectId, clientId = secondClientId, name = "Second")
        val base = state(TENANT).withoutCommands().copy(
            projectCache = state(TENANT).withoutCommands().projectCache.copy(
                activeProjects = listOf(first, second),
                pages = listOf(
                    ProjectPage(first, ProjectNotes(first.id, "", 1, NOW), null),
                    ProjectPage(second, ProjectNotes(second.id, "", 1, NOW), null),
                ),
                projectOrder = ProjectOrderState(
                    orderedProjectIds = listOf(first.id, second.id),
                    version = 1,
                    updatedAt = NOW,
                    aggregateId = "40000000-0000-4000-8000-000000000001",
                ),
            ),
        )
        store.saveSyncState(TENANT, base)
        val persistedBase = AndroidSyncStateStore(context).loadSyncState(TENANT)
        val operation = InteractionMutation(
            operationId = "50000000-0000-4000-8000-000000000001",
            type = InteractionMutationType.REORDER_PROJECTS,
            aggregateId = "40000000-0000-4000-8000-000000000001",
            orderedClientIds = listOf(secondClientId, firstClientId),
            baseVersion = 1,
            createdAt = NOW,
        )
        val updated = persistedBase.copy(
            projectCache = persistedBase.projectCache.copy(
                projectOrder = persistedBase.projectCache.projectOrder.copy(
                    orderedProjectIds = listOf(second.id, first.id),
                    version = 2,
                ),
                interactionOperations = listOf(operation),
            ),
        )

        store.saveInteractionMutation(TENANT, persistedBase, updated)

        assertEquals(updated, AndroidSyncStateStore(context).loadSyncState(TENANT))
    }

    private fun state(tenantId: String, projectId: String = PROJECT): TenantSyncState {
        val project = ProjectSummary(
            id = projectId,
            tenantId = tenantId,
            name = "Workshop shelves",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 3,
            createdAt = NOW,
            updatedAt = NOW,
            clientId = projectId,
        )
        val parking = ProjectParking(
            id = "$projectId-parking",
            projectId = projectId,
            location = "Garage bench",
            nextAction = "Fit the bracket",
            mustNotForget = "Bring the charger",
            authoredByUserId = "local-user",
            createdAt = NOW,
        )
        val block = LocalBlockRecord(
            clientBlockId = "$projectId-block",
            token = TAG_TOKEN,
            label = "Workshop block",
            state = LocalBlockState.ACTIVE,
            intendedProjectId = projectId,
            boundProjectId = projectId,
            registrationOperationId = "$projectId-register",
            initialBindingOperationId = "$projectId-bind",
            createdAt = NOW,
            verifiedAt = NOW,
            activationMedium = TagActivationMedium.NFC,
        )
        val archivedId = "3${projectId.drop(1)}"
        val archived = project.copy(
            id = archivedId,
            name = "Archived supplies",
            lifecycle = ProjectLifecycle.ARCHIVED,
            archivedAt = NOW,
            clientId = archivedId,
        )
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = listOf(project),
                activeProjectsLoaded = true,
                archivedProjects = listOf(archived),
                archivedProjectsLoaded = true,
                pages = listOf(
                    ProjectPage(project, ProjectNotes(projectId, "Measure twice", 2, NOW), parking),
                    ProjectPage(archived, ProjectNotes(archivedId, "Stored safely", 1, NOW), null),
                ),
                parkingRecords = listOf(parking),
                parkingLoadedProjectIds = listOf(projectId),
                localBlocks = listOf(block),
                localBlockOperations = listOf(
                    LocalBlockOperation(
                        operationId = "$projectId-register",
                        type = LocalBlockOperationType.REGISTER_BLOCK,
                        clientBlockId = block.clientBlockId,
                        token = TAG_TOKEN,
                        label = block.label,
                        createdAt = NOW,
                    ),
                    LocalBlockOperation(
                        operationId = "$projectId-bind",
                        type = LocalBlockOperationType.BIND_BLOCK,
                        clientBlockId = block.clientBlockId,
                        projectId = projectId,
                        dependsOnOperationId = "$projectId-register",
                        createdAt = NOW,
                    ),
                ),
                projectLinks = listOf(
                    ProjectLinkIdentity(projectId, ProjectKey.parse(projectId)!!),
                    ProjectLinkIdentity(archivedId, ProjectKey.parse(archivedId)!!),
                ),
                projectCovers = listOf(
                    ProjectCover(
                        coverId = "123e4567-e89b-42d3-a456-426614174071",
                        tenantId = tenantId,
                        projectId = projectId,
                        mediaType = "image/jpeg",
                        displayContentRef = "display.enc",
                        displayWidth = 1200,
                        displayHeight = 800,
                        displayByteSize = 2048,
                        displayDigest = "a".repeat(64),
                        thumbnailContentRef = "thumbnail.enc",
                        thumbnailWidth = 320,
                        thumbnailHeight = 213,
                        thumbnailByteSize = 512,
                        thumbnailDigest = "b".repeat(64),
                        contentDigest = "c".repeat(64),
                        revision = 1,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                ),
                commands = listOf(
                    ProjectMutation(
                        operationId = "$projectId-pending-notes",
                        type = ProjectMutationType.SAVE_NOTES,
                        targetId = projectId,
                        ifMatch = 2,
                        notesBody = "Pending note",
                        localCreatedAt = NOW,
                    ),
                ),
                provisionalProjectIds = listOf(projectId),
                projectIdMappings = listOf(ProjectIdentityMapping("4${projectId.drop(1)}", projectId)),
            ),
            projectDrafts = FormDrafts(),
            projectDraftsMigrated = true,
            changeCursor = "71",
        )
    }

    private fun TenantSyncState.withoutCommands() = copy(
        projectCache = projectCache.copy(commands = emptyList()),
    )

    private fun TenantSyncState.withCreatedProject(): TenantSyncState {
        val project = ProjectSummary(
            id = CREATED_PROJECT,
            tenantId = TENANT,
            name = "New workbench",
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 0,
            createdAt = NOW,
            updatedAt = NOW,
            clientId = CREATED_PROJECT,
        )
        val page = ProjectPage(project, ProjectNotes(CREATED_PROJECT, "", 0, NOW), null)
        val command = ProjectMutation(
            operationId = "create-operation",
            type = ProjectMutationType.CREATE_PROJECT,
            targetId = CREATED_PROJECT,
            idempotencyKey = "create-operation",
            clientResourceId = CREATED_PROJECT,
            projectName = project.name,
            localCreatedAt = NOW,
        )
        return copy(
            projectCache = projectCache.copy(
                activeProjects = projectCache.activeProjects + project,
                pages = projectCache.pages + page,
                parkingLoadedProjectIds = projectCache.parkingLoadedProjectIds + CREATED_PROJECT,
                projectLinks = projectCache.projectLinks +
                    ProjectLinkIdentity(CREATED_PROJECT, ProjectKey.parse(CREATED_PROJECT)!!),
                commands = projectCache.commands + command,
                provisionalProjectIds = projectCache.provisionalProjectIds + CREATED_PROJECT,
            ),
        )
    }

    private fun TenantSyncState.withAddedParking(): TenantSyncState {
        val parking = ProjectParking(
            id = CREATED_PARKING,
            projectId = PROJECT,
            location = "Cutting bench",
            nextAction = "Mark the joint",
            mustNotForget = "Use the square",
            authoredByUserId = "local",
            createdAt = NOW,
            occurredAt = NOW,
            clientId = CREATED_PARKING,
        )
        val command = ProjectMutation(
            operationId = "parking-operation",
            type = ProjectMutationType.ADD_PARKING,
            targetId = PROJECT,
            projectId = PROJECT,
            idempotencyKey = "parking-operation",
            clientResourceId = CREATED_PARKING,
            parking = ParkingDraft(parking.location, parking.nextAction, parking.mustNotForget),
            localCreatedAt = NOW,
        )
        return copy(
            projectCache = projectCache.copy(
                pages = projectCache.pages.map { page ->
                    if (page.project.id == PROJECT) page.copy(newestParking = parking) else page
                },
                parkingRecords = projectCache.parkingRecords + parking,
                commands = projectCache.commands + command,
            ),
        )
    }

    private fun TenantSyncState.withExistingItems(): TenantSyncState = copy(
        projectCache = projectCache.copy(
            noteItems = listOf(
                ProjectNoteItem(NOTE_ID, PROJECT, "Existing note", false, "local", NOW, NOW, rowVersion = 3),
            ),
            resourceLinks = listOf(
                ProjectResourceLink(
                    LINK_ID,
                    PROJECT,
                    "Existing link",
                    "https://example.com",
                    NOW,
                    NOW,
                    rowVersion = 4,
                ),
            ),
            itemOperations = emptyList(),
        ),
    )

    private fun TenantSyncState.withItemDraft(type: ProjectItemMutationType): TenantSyncState = when (type) {
        ProjectItemMutationType.CREATE_NOTE -> copy(projectDrafts = projectDrafts.copy(
            projectNoteItems = mapOf(noteItemDraftKey(PROJECT) to NoteItemDraft("Updated note")),
        ))
        ProjectItemMutationType.EDIT_NOTE -> copy(projectDrafts = projectDrafts.copy(
            projectNoteItems = mapOf(noteItemDraftKey(PROJECT, NOTE_ID) to NoteItemDraft("Updated note")),
        ))
        ProjectItemMutationType.CREATE_LINK -> copy(projectDrafts = projectDrafts.copy(
            projectResourceLinks = mapOf(
                resourceLinkDraftKey(PROJECT) to ProjectResourceLinkDraft("Updated link", "https://example.com/updated"),
            ),
        ))
        ProjectItemMutationType.EDIT_LINK -> copy(projectDrafts = projectDrafts.copy(
            projectResourceLinks = mapOf(
                resourceLinkDraftKey(PROJECT, LINK_ID) to
                    ProjectResourceLinkDraft("Updated link", "https://example.com/updated"),
            ),
        ))
        else -> this
    }

    private fun TenantSyncState.withItemMutation(type: ProjectItemMutationType, index: Int): TenantSyncState {
        val target = when (type) {
            ProjectItemMutationType.CREATE_NOTE -> "50000000-0000-4000-8000-00000000000$index"
            ProjectItemMutationType.CREATE_LINK -> "60000000-0000-4000-8000-00000000000$index"
            ProjectItemMutationType.EDIT_NOTE,
            ProjectItemMutationType.PIN_NOTE,
            ProjectItemMutationType.DELETE_NOTE,
            -> NOTE_ID
            else -> LINK_ID
        }
        val operation = ProjectItemMutation(
            operationId = "item-operation-$index",
            type = type,
            targetId = target,
            projectId = PROJECT,
            clientResourceId = target.takeIf { type == ProjectItemMutationType.CREATE_NOTE || type == ProjectItemMutationType.CREATE_LINK },
            ifMatch = when (type) {
                ProjectItemMutationType.EDIT_NOTE,
                ProjectItemMutationType.PIN_NOTE,
                ProjectItemMutationType.DELETE_NOTE,
                -> 3
                ProjectItemMutationType.EDIT_LINK,
                ProjectItemMutationType.DELETE_LINK,
                -> 4
                else -> null
            },
            noteBody = "Updated note".takeIf { type == ProjectItemMutationType.CREATE_NOTE || type == ProjectItemMutationType.EDIT_NOTE },
            pinned = true.takeIf { type == ProjectItemMutationType.PIN_NOTE },
            linkTitle = "Updated link".takeIf { type == ProjectItemMutationType.CREATE_LINK || type == ProjectItemMutationType.EDIT_LINK },
            linkUri = "https://example.com/updated".takeIf {
                type == ProjectItemMutationType.CREATE_LINK || type == ProjectItemMutationType.EDIT_LINK
            },
            createdAt = NOW,
        )
        val notes = when (type) {
            ProjectItemMutationType.CREATE_NOTE -> projectCache.noteItems +
                ProjectNoteItem(target, PROJECT, "Updated note", false, "local", NOW, NOW)
            ProjectItemMutationType.EDIT_NOTE -> projectCache.noteItems.map {
                if (it.id == target) it.copy(body = "Updated note") else it
            }
            ProjectItemMutationType.PIN_NOTE -> projectCache.noteItems.map {
                if (it.id == target) it.copy(pinned = true) else it
            }
            ProjectItemMutationType.DELETE_NOTE -> projectCache.noteItems.map {
                if (it.id == target) it.copy(deletedAt = NOW) else it
            }
            else -> projectCache.noteItems
        }
        val links = when (type) {
            ProjectItemMutationType.CREATE_LINK -> projectCache.resourceLinks +
                ProjectResourceLink(target, PROJECT, "Updated link", "https://example.com/updated", NOW, NOW)
            ProjectItemMutationType.EDIT_LINK -> projectCache.resourceLinks.map {
                if (it.id == target) it.copy(title = "Updated link", uri = "https://example.com/updated") else it
            }
            ProjectItemMutationType.DELETE_LINK -> projectCache.resourceLinks.map {
                if (it.id == target) it.copy(deletedAt = NOW) else it
            }
            else -> projectCache.resourceLinks
        }
        val clearedDrafts = when (type) {
            ProjectItemMutationType.CREATE_NOTE,
            ProjectItemMutationType.EDIT_NOTE,
            -> projectDrafts.copy(projectNoteItems = emptyMap())
            ProjectItemMutationType.CREATE_LINK,
            ProjectItemMutationType.EDIT_LINK,
            -> projectDrafts.copy(projectResourceLinks = emptyMap())
            else -> projectDrafts
        }
        return copy(
            projectCache = projectCache.copy(
                noteItems = notes,
                resourceLinks = links,
                itemOperations = listOf(operation),
            ),
            projectDrafts = clearedDrafts,
        )
    }

    private fun assertMutationRollsBack(
        type: ProjectMutationType,
        update: (TenantSyncState) -> TenantSyncState,
    ) {
        val initial = state(TENANT).withoutCommands()
        store.saveSyncState(TENANT, initial)
        installAuditTriggers(TENANT)
        val failing = AndroidSyncStateStore(context, beforeMutationCommit = { attempted ->
            if (attempted == type) error("simulated commit failure")
        })

        assertTrue(runCatching { failing.saveProjectMutation(TENANT, initial, update(initial), type) }.isFailure)
        assertEquals(initial, store.loadSyncState(TENANT))
        assertTrue(auditedTables(TENANT).isEmpty())
    }

    private fun installAuditTriggers(tenantId: String) {
        SQLiteDatabase.openDatabase(normalizedDatabase(tenantId).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE write_audit (table_name TEXT NOT NULL)")
            (listOf(AndroidSyncStateStore.TABLE_METADATA) + AndroidSyncStateStore.STATE_TABLES)
                .forEachIndexed { index, table ->
                listOf("INSERT", "UPDATE", "DELETE").forEachIndexed { operationIndex, operation ->
                    db.execSQL(
                        "CREATE TRIGGER audit_${index}_$operationIndex AFTER $operation ON $table " +
                            "BEGIN INSERT INTO write_audit(table_name) VALUES ('$table'); END",
                    )
                }
            }
        }
    }

    private fun auditedTables(tenantId: String): Set<String> =
        SQLiteDatabase.openDatabase(normalizedDatabase(tenantId).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT DISTINCT table_name FROM write_audit", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun hasAuthoritativeMarker(tenantId: String): Boolean =
        SQLiteDatabase.openDatabase(normalizedDatabase(tenantId).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT value FROM ${AndroidSyncStateStore.TABLE_METADATA} WHERE record_id = ?",
                arrayOf(AndroidSyncStateStore.META_AUTHORITATIVE),
            ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "true" }
        }

    private fun normalizedDatabase(tenantId: String) = context.getDatabasePath(
        "${AndroidSyncStateStore.DATABASE_PREFIX}${digest(tenantId)}.db",
    )
    private fun releasedDatabase(tenantId: String) = context.getDatabasePath("blocks_sync_${digest(tenantId)}.db")
    private fun releasedKeyAlias(tenantId: String) = "blocks-sync-db-v1-${digest(tenantId)}"
    private fun secretKeyAlias(tenantId: String) = "${AndroidSyncStateStore.SECRET_KEY_PREFIX}${digest(tenantId)}"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun ByteArray.containsBytes(target: ByteArray): Boolean =
        indices.any { start -> start + target.size <= size && copyOfRange(start, start + target.size).contentEquals(target) }

    private companion object {
        const val TENANT = "00000000-0000-0000-0000-000000000071"
        const val FAILED_TENANT = "00000000-0000-0000-0000-000000000072"
        const val OTHER_TENANT = "00000000-0000-0000-0000-000000000073"
        const val BEFORE_MARKER_TENANT = "00000000-0000-0000-0000-000000000074"
        const val AFTER_MARKER_TENANT = "00000000-0000-0000-0000-000000000075"
        const val PROJECT = "123e4567-e89b-12d3-a456-426614174071"
        const val OTHER_PROJECT = "223e4567-e89b-12d3-a456-426614174073"
        const val CREATED_PROJECT = "323e4567-e89b-12d3-a456-426614174074"
        const val CREATED_PARKING = "423e4567-e89b-12d3-a456-426614174075"
        const val NOTE_ID = "523e4567-e89b-42d3-a456-426614174075"
        const val LINK_ID = "623e4567-e89b-42d3-a456-426614174075"
        const val NOW = "2026-09-15T12:00:00Z"
        val TAG_TOKEN = OpaqueTagToken.fromBytes(ByteArray(32) { (it + 41).toByte() })
    }

    private class SimulatedProcessDeath : Error("simulated process death")
}
