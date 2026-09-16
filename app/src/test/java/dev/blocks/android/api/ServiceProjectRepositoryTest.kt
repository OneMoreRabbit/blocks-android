package dev.blocks.android.api

import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.sync.ProjectSyncRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceProjectRepositoryTest {
    @Test
    fun mapsProjectNotesParkingBlockAndChangeAdaptersFromGeneratedModels() {
        val transport = FakeProjectTransport()
        val repository = ServiceProjectRepository(transport)

        val projects = repository.listProjects(SESSION, ProjectLifecycle.ACTIVE)
        val page = repository.projectPage(SESSION, PROJECT_ID)
        val notes = repository.notes(SESSION, PROJECT_ID)
        val parking = repository.parking(SESSION, PROJECT_ID)
        val blocks = repository.blocks(SESSION)
        val lookup = repository.lookupBlock(SESSION, "opaque-token")
        val changes = repository.changes(SESSION, "40", 100)

        assertEquals(listOf("Project"), projects.map { it.name })
        assertEquals("Saved notes", page.notes.body)
        assertEquals("Here", page.newestParking?.location)
        assertEquals(4, notes.rowVersion)
        assertEquals(listOf("Next"), parking.map { it.nextAction })
        assertEquals(PROJECT_ID, blocks.single().boundProjectId)
        assertEquals(PROJECT_ID, lookup.projectId)
        assertEquals("41", changes.nextCursor)
        assertEquals("project.notes_saved", changes.events.single().eventType)
        assertEquals("active", transport.requestedStatus)
    }

    @Test
    fun mapsEveryMutationWithoutLeakingTransportTypesAcrossTheAdapter() {
        val transport = FakeProjectTransport()
        val repository = ServiceProjectRepository(transport)

        assertEquals("Created", repository.createProject(SESSION, "Created", CLIENT_ID, "create-key").name)
        assertEquals("Renamed", repository.renameProject(SESSION, PROJECT_ID, 3, "Renamed").name)
        assertEquals(ProjectLifecycle.ARCHIVED, repository.archiveProject(SESSION, PROJECT_ID, 4).lifecycle)
        assertEquals(ProjectLifecycle.ACTIVE, repository.reopenProject(SESSION, PROJECT_ID, 5).lifecycle)
        assertEquals("Edited", repository.saveNotes(SESSION, PROJECT_ID, 4, "Edited").body)
        assertEquals(
            "Remember",
            repository.addParking(
                SESSION,
                PROJECT_ID,
                ParkingDraft("Here", "Next", "Remember"),
                supersedesId = null,
                clientId = CLIENT_ID,
                occurredAt = NOW,
                idempotencyKey = "park-key",
            ).mustNotForget,
        )
        assertEquals(PROJECT_ID, repository.bindBlock(SESSION, BLOCK_ID, PROJECT_ID, "bind-key").projectId)
        repository.unbindBlock(SESSION, BLOCK_ID)

        assertEquals("create-key", transport.createKey)
        assertEquals("Created", JSONObject(transport.createBody).getString("name"))
        assertEquals(CLIENT_ID, JSONObject(transport.createBody).getString("client_id"))
        assertEquals(3, transport.renameVersion)
        assertEquals("Renamed", JSONObject(transport.renameBody).getString("name"))
        assertEquals(4, transport.notesVersion)
        assertEquals("Edited", JSONObject(transport.notesBody).getString("body"))
        assertEquals("park-key", transport.parkingKey)
        assertEquals(
            setOf("where_i_am", "next_physical_action", "must_not_forget", "client_id", "occurred_at"),
            JSONObject(transport.parkingBody).keys().asSequence().toSet(),
        )
        assertEquals(CLIENT_ID, JSONObject(transport.parkingBody).getString("client_id"))
        assertEquals(NOW, JSONObject(transport.parkingBody).getString("occurred_at"))
        assertTrue(transport.unbound)
    }

    @Test
    fun directRegistrationReturnsTheOneTimeTokenAndHasNoGenericQueueSurface() {
        val transport = FakeProjectTransport()
        val repository = ServiceProjectRepository(transport)

        val registered = repository.registerBlock(SESSION, "Desk", "block-key")

        assertEquals("tag-token", registered.tagToken)
        assertEquals(BLOCK_ID, registered.block.id)
        assertEquals("block-key", transport.registrationKey)
        assertEquals("Desk", JSONObject(transport.registrationBody).getString("label"))
        assertEquals(1, transport.registrationCalls)
        assertFalse(
            ProjectSyncRepository::class.java.methods.any { method ->
                method.name == "queueRegisterBlock"
            },
        )
    }

    @Test
    fun recognizesAnOldRouteProjectShapeAsDeferredApiIncompatibility() {
        val repository = ServiceProjectRepository(FakeProjectTransport(oldCreateResponse = true))

        val error = runCatching {
            repository.createProject(SESSION, "Offline-compatible", CLIENT_ID, "stable-create-key")
        }.exceptionOrNull()

        assertTrue(error is IncompatibleServiceApiException)
    }

    @Test
    fun readOnlyCompatibilityProbeRecognizesTheOldProjectListBeforeAnyMutation() {
        val repository = ServiceProjectRepository(FakeProjectTransport(oldListResponse = true))

        val error = runCatching {
            repository.listProjects(SESSION, ProjectLifecycle.ACTIVE)
        }.exceptionOrNull()

        assertTrue(error is IncompatibleServiceApiException)
    }

    @Test(expected = IncompatibleServiceApiException::class)
    fun rejectsAProjectPageFromAnotherTenant() {
        val repository = ServiceProjectRepository(FakeProjectTransport(pageTenant = OTHER_TENANT))
        repository.projectPage(SESSION, PROJECT_ID)
    }

    @Test
    fun mapsApi06ReceiptProvenanceAndNavigationAliasAsDistinctIdentities() {
        val transport = FakeProjectTransport()
        val repository = ServiceProjectRepository(transport)
        val operation = ProjectItemMutation(
            operationId = OPERATION_A,
            type = ProjectItemMutationType.CREATE_NOTE,
            targetId = ITEM_ID,
            projectId = PROJECT_ID,
            clientResourceId = ITEM_ID,
            noteBody = "Offline note",
            createdAt = NOW,
        )
        val key = requireNotNull(ProjectKey.parse(NAVIGATION_KEY))

        val acknowledgement = repository.createNote(SESSION, operation)
        val attached = repository.attachNavigationKey(SESSION, PROJECT_ID, key)
        val resolved = repository.resolveNavigationKey(SESSION, key)

        assertEquals(OPERATION_A, acknowledgement.operationId)
        assertEquals(OPERATION_B, acknowledgement.item.lastOperationId)
        assertEquals(2, acknowledgement.item.rowVersion)
        assertEquals(NAVIGATION_KEY, attached.encoded)
        assertEquals(PROJECT_ID, resolved)
        assertEquals(OPERATION_A, JSONObject(transport.createNoteBody).getString("operation_id"))
        assertEquals(ITEM_ID, JSONObject(transport.createNoteBody).getString("id"))
        assertEquals(NAVIGATION_KEY, JSONObject(transport.aliasBody).getString("project_key"))
        assertEquals(NAVIGATION_KEY, JSONObject(transport.resolveBody).getString("project_key"))
    }

    private class FakeProjectTransport(
        private val pageTenant: String = TENANT_ID,
        private val oldCreateResponse: Boolean = false,
        private val oldListResponse: Boolean = false,
    ) : ProjectTransport {
        var requestedStatus: String? = null
        var createKey: String? = null
        var createBody: String = ""
        var renameVersion: Int? = null
        var renameBody: String = ""
        var notesVersion: Int? = null
        var notesBody: String = ""
        var parkingKey: String? = null
        var parkingBody: String = ""
        var registrationKey: String? = null
        var registrationBody: String = ""
        var registrationCalls = 0
        var unbound = false
        var createNoteBody = ""
        var aliasBody = ""
        var resolveBody = ""

        override fun listProjects(identityToken: String, tenantId: String, status: String): ApiResponse {
            requestedStatus = status
            if (oldListResponse) return success("""[{"id":"$PROJECT_ID","name":"Old project"}]""")
            return success("[${projectJson(status = status)}]")
        }

        override fun createProject(
            identityToken: String,
            tenantId: String,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            createBody = body
            createKey = idempotencyKey
            return if (oldCreateResponse) {
                success("""{"id":"$PROJECT_ID","name":"${JSONObject(body).getString("name")}"}""")
            } else {
                success(
                    projectJson(
                        name = JSONObject(body).getString("name"),
                        clientId = JSONObject(body).optString("client_id").ifEmpty { null },
                    ),
                )
            }
        }

        override fun projectPage(identityToken: String, tenantId: String, projectId: String) = success(
            """{"project":${projectJson(tenant = pageTenant)},"notes":${notesJson()},"newest_parking":${parkingJson()}}""",
        )

        override fun renameProject(
            identityToken: String,
            tenantId: String,
            projectId: String,
            rowVersion: Int,
            body: String,
        ): ApiResponse {
            renameVersion = rowVersion
            renameBody = body
            return success(projectJson(name = JSONObject(body).getString("name"), rowVersion = rowVersion + 1))
        }

        override fun archiveProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int) =
            success(projectJson(status = "archived", rowVersion = rowVersion + 1, archivedAt = NOW))

        override fun reopenProject(identityToken: String, tenantId: String, projectId: String, rowVersion: Int) =
            success(projectJson(rowVersion = rowVersion + 1))

        override fun notes(identityToken: String, tenantId: String, projectId: String) = success(notesJson())

        override fun saveNotes(
            identityToken: String,
            tenantId: String,
            projectId: String,
            rowVersion: Int,
            body: String,
        ): ApiResponse {
            notesVersion = rowVersion
            notesBody = body
            return success(notesJson(JSONObject(body).getString("body"), rowVersion + 1))
        }

        override fun addParking(
            identityToken: String,
            tenantId: String,
            projectId: String,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            parkingBody = body
            parkingKey = idempotencyKey
            return success(parkingJson())
        }

        override fun parking(identityToken: String, tenantId: String, projectId: String) =
            success("[${parkingJson()}]")

        override fun listBlocks(identityToken: String, tenantId: String) = success(
            """[{"id":"$BLOCK_ID","label":"Desk","state":"bound","bound_project_id":"$PROJECT_ID"}]""",
        )

        override fun registerBlock(
            identityToken: String,
            tenantId: String,
            body: String,
            idempotencyKey: String,
        ): ApiResponse {
            registrationCalls += 1
            registrationKey = idempotencyKey
            registrationBody = body
            return success(
                """{"id":"$BLOCK_ID","label":"Desk","state":"registered","tag_token":"tag-token","bound_project_id":null}""",
            )
        }

        override fun bindBlock(
            identityToken: String,
            tenantId: String,
            blockId: String,
            body: String,
            idempotencyKey: String,
        ) = success(
            """{"id":"$BINDING_ID","block_id":"$BLOCK_ID","project_id":"$PROJECT_ID"}""",
        )

        override fun unbindBlock(identityToken: String, tenantId: String, blockId: String): ApiResponse {
            unbound = true
            return success("")
        }

        override fun lookupBlock(identityToken: String, tenantId: String, body: String) = success(
            """{"block_id":"$BLOCK_ID","label":"Desk","project_id":"$PROJECT_ID"}""",
        )

        override fun changes(identityToken: String, tenantId: String, cursor: String?, limit: Int) = success(
            """{"events":[{"eventId":"41","eventType":"project.notes_saved","eventVersion":"0.1","occurredAt":"$NOW","payload":{"projectId":"$PROJECT_ID","rowVersion":4},"tenantId":"$TENANT_ID"}],"next_since":"41","has_more":false}""",
        )

        override fun createNote(identityToken: String, tenantId: String, projectId: String, body: String): ApiResponse {
            createNoteBody = body
            return success(noteAckJson())
        }

        override fun attachNavigationAlias(
            identityToken: String,
            tenantId: String,
            projectId: String,
            body: String,
        ): ApiResponse {
            aliasBody = body
            return success(
                """{"tenant_id":"$TENANT_ID","project_id":"$PROJECT_ID","project_key":"$NAVIGATION_KEY","created_at":"$NOW"}""",
            )
        }

        override fun resolveNavigationAlias(identityToken: String, tenantId: String, body: String): ApiResponse {
            resolveBody = body
            return success("""{"project_id":"$PROJECT_ID"}""")
        }

        private fun success(body: String) = ApiResponse.Success(200, body)
    }

    companion object {
        private const val TENANT_ID = "00000000-0000-0000-0000-000000000002"
        private const val OTHER_TENANT = "00000000-0000-0000-0000-000000000099"
        private const val PROJECT_ID = "00000000-0000-0000-0000-000000000003"
        private const val BLOCK_ID = "00000000-0000-0000-0000-000000000004"
        private const val BINDING_ID = "00000000-0000-0000-0000-000000000005"
        private const val USER_ID = "00000000-0000-0000-0000-000000000006"
        private const val PARKING_ID = "00000000-0000-0000-0000-000000000007"
        private const val NOW = "2026-09-07T10:00:00Z"
        private val SESSION = Session(TENANT_ID, "identity-token")

        private fun projectJson(
            name: String = "Project",
            status: String = "active",
            rowVersion: Int = 3,
            tenant: String = TENANT_ID,
            archivedAt: String? = null,
            clientId: String? = null,
        ) = """
            {
              "archived_at":${archivedAt?.let { "\"$it\"" } ?: "null"},
              "created_at":"$NOW",
              "id":"$PROJECT_ID",
              "name":"$name",
              "row_version":$rowVersion,
              "status":"$status",
              "tenant_id":"$tenant",
              "updated_at":"$NOW"
              ,"client_id":${clientId?.let { "\"$it\"" } ?: "null"}
            }
        """.trimIndent()

        private fun notesJson(body: String = "Saved notes", rowVersion: Int = 4) =
            """{"project_id":"$PROJECT_ID","body":"$body","row_version":$rowVersion,"updated_at":"$NOW"}"""

        private fun parkingJson() =
            """{"id":"$PARKING_ID","project_id":"$PROJECT_ID","where_i_am":"Here","next_physical_action":"Next","must_not_forget":"Remember","authored_by_user_id":"$USER_ID","device_id":null,"supersedes_id":null,"created_at":"$NOW","occurred_at":"$NOW","client_id":"$CLIENT_ID"}"""

        private fun noteAckJson() =
            """{"authored_by_user_id":"$USER_ID","body":"Later state","created_at":"$NOW","deleted_at":null,"id":"$ITEM_ID","last_operation_id":"$OPERATION_B","operation_id":"$OPERATION_A","pinned":true,"project_id":"$PROJECT_ID","row_version":2,"tenant_id":"$TENANT_ID","updated_at":"$NOW"}"""

        private const val CLIENT_ID = "00000000-0000-0000-0000-000000000098"
        private const val ITEM_ID = "00000000-0000-4000-8000-000000000009"
        private const val OPERATION_A = "00000000-0000-4000-8000-00000000000a"
        private const val OPERATION_B = "00000000-0000-4000-8000-00000000000b"
        private const val NAVIGATION_KEY = "00000000-0000-4000-8000-00000000000c"
    }
}
