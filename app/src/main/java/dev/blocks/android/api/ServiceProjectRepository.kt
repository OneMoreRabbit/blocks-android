package dev.blocks.android.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.blocks.android.api.generated.v07.model.AliasIn
import dev.blocks.android.api.generated.v07.model.AliasOut
import dev.blocks.android.api.generated.v07.model.BindIn
import dev.blocks.android.api.generated.v07.model.BindingOut
import dev.blocks.android.api.generated.v07.model.BlockOut
import dev.blocks.android.api.generated.v07.model.ChangesOut
import dev.blocks.android.api.generated.v07.model.DeleteIn
import dev.blocks.android.api.generated.v07.model.DeleteParkingIn
import dev.blocks.android.api.generated.v07.model.LinkAck
import dev.blocks.android.api.generated.v07.model.LinkCreateIn
import dev.blocks.android.api.generated.v07.model.LinkEditIn
import dev.blocks.android.api.generated.v07.model.LinkOut
import dev.blocks.android.api.generated.v07.model.LegacyBlockIn
import dev.blocks.android.api.generated.v07.model.LookupIn
import dev.blocks.android.api.generated.v07.model.LookupOut
import dev.blocks.android.api.generated.v07.model.NoteAck
import dev.blocks.android.api.generated.v07.model.NoteCreateIn
import dev.blocks.android.api.generated.v07.model.NoteEditIn
import dev.blocks.android.api.generated.v07.model.NoteOut
import dev.blocks.android.api.generated.v07.model.NotePinIn
import dev.blocks.android.api.generated.v07.model.NotesIn
import dev.blocks.android.api.generated.v07.model.NotesOut
import dev.blocks.android.api.generated.v07.model.OrderOut
import dev.blocks.android.api.generated.v07.model.ParkingDeleteOut
import dev.blocks.android.api.generated.v07.model.ParkingTombstoneOut
import dev.blocks.android.api.generated.v07.model.ProjectIn
import dev.blocks.android.api.generated.v07.model.ProjectOut
import dev.blocks.android.api.generated.v07.model.ProjectPageOut
import dev.blocks.android.api.generated.v07.model.ProjectParkIn
import dev.blocks.android.api.generated.v07.model.ProjectParkingOut
import dev.blocks.android.api.generated.v07.model.ProjectRename
import dev.blocks.android.api.generated.v07.model.RegisteredBlockOut
import dev.blocks.android.api.generated.v07.model.ReorderIn
import dev.blocks.android.api.generated.v07.model.ResolveKeyIn
import dev.blocks.android.api.generated.v07.model.ResolveKeyOut
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.BlockLookup
import dev.blocks.android.project.AuthoritativeOrder
import dev.blocks.android.project.InteractionAcknowledgement
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.InteractionMutationType
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectItemAcknowledgement
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.RegisteredBlock
import dev.blocks.android.tag.ProjectKey
import java.util.UUID

/** Maps generated API 0.7 transport models into Android-owned project-domain models. */
class ServiceProjectRepository(
    private val client: ProjectTransport,
    private val json: Gson = Gson(),
) : ProjectService {
    override fun listProjects(session: Session, lifecycle: ProjectLifecycle): List<ProjectSummary> =
        decodeContract {
            json.fromJson<List<ProjectOut>>(
                client.listProjects(
                    session.identityToken,
                    session.tenantId,
                    lifecycle.wireValue,
                ).successBody(),
                PROJECT_LIST_TYPE,
            ).map { project ->
                project.toProject(session).also {
                    check(it.lifecycle == lifecycle) { "Service returned a project from another lifecycle" }
                }
            }
        }

    override fun createProject(
        session: Session,
        name: String,
        clientId: String?,
        idempotencyKey: String,
    ): ProjectSummary =
        decodeContract {
            json.fromJson(
                client.createProject(
                    session.identityToken,
                    session.tenantId,
                    json.toJson(ProjectIn(name = name, clientId = clientId?.let(UUID::fromString))),
                    idempotencyKey,
                ).successBody(),
                ProjectOut::class.java,
            ).toProject(session)
        }

    override fun projectPage(session: Session, projectId: String): ProjectPage = decodeContract {
        json.fromJson(
            client.projectPage(session.identityToken, session.tenantId, projectId).successBody(),
            ProjectPageOut::class.java,
        ).toProjectPage(session, projectId)
    }

    override fun renameProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        name: String,
    ): ProjectSummary = decodeContract {
        json.fromJson(
            client.renameProject(
                session.identityToken,
                session.tenantId,
                projectId,
                rowVersion,
                json.toJson(ProjectRename(name)),
            ).successBody(),
            ProjectOut::class.java,
        ).toProject(session).also { check(it.id == projectId) }
    }

    override fun archiveProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary =
        projectAction(session, projectId, rowVersion, archive = true)

    override fun reopenProject(session: Session, projectId: String, rowVersion: Int): ProjectSummary =
        projectAction(session, projectId, rowVersion, archive = false)

    override fun notes(session: Session, projectId: String): ProjectNotes = decodeContract {
        json.fromJson(
            client.notes(session.identityToken, session.tenantId, projectId).successBody(),
            NotesOut::class.java,
        ).toNotes(projectId)
    }

    override fun saveNotes(
        session: Session,
        projectId: String,
        rowVersion: Int,
        body: String,
    ): ProjectNotes = decodeContract {
        json.fromJson(
            client.saveNotes(
                session.identityToken,
                session.tenantId,
                projectId,
                rowVersion,
                json.toJson(NotesIn(body)),
            ).successBody(),
            NotesOut::class.java,
        ).toNotes(projectId)
    }

    override fun addParking(
        session: Session,
        projectId: String,
        parking: ParkingDraft,
        supersedesId: String?,
        clientId: String?,
        occurredAt: String?,
        idempotencyKey: String,
    ): ProjectParking = decodeContract {
        json.fromJson(
            client.addParking(
                session.identityToken,
                session.tenantId,
                projectId,
                json.toJson(
                    ProjectParkIn(
                        mustNotForget = parking.mustNotForget,
                        nextPhysicalAction = parking.nextAction,
                        whereIAm = parking.location,
                        clientId = clientId?.let(UUID::fromString),
                        occurredAt = occurredAt,
                        supersedesId = supersedesId?.let(UUID::fromString),
                    ),
                ),
                idempotencyKey,
            ).successBody(),
            ProjectParkingOut::class.java,
        ).toParking(projectId)
    }

    override fun parking(session: Session, projectId: String): List<ProjectParking> = decodeContract {
        json.fromJson<List<ProjectParkingOut>>(
            client.parking(session.identityToken, session.tenantId, projectId).successBody(),
            PARKING_LIST_TYPE,
        ).map { it.toParking(projectId) }
    }

    override fun blocks(session: Session): List<PhysicalBlock> = decodeContract {
        json.fromJson<List<BlockOut>>(
            client.listBlocks(session.identityToken, session.tenantId).successBody(),
            BLOCK_LIST_TYPE,
        ).map { it.toBlock() }
    }

    override fun registerBlock(session: Session, label: String, idempotencyKey: String): RegisteredBlock =
        decodeContract {
            json.fromJson(
                client.registerBlock(
                    session.identityToken,
                    session.tenantId,
                    json.toJson(LegacyBlockIn(label)),
                    idempotencyKey,
                ).successBody(),
                RegisteredBlockOut::class.java,
            ).toRegisteredBlock()
        }

    override fun bindBlock(
        session: Session,
        blockId: String,
        projectId: String,
        idempotencyKey: String,
    ): BlockBinding = decodeContract {
        json.fromJson(
            client.bindBlock(
                session.identityToken,
                session.tenantId,
                blockId,
                json.toJson(BindIn(UUID.fromString(projectId))),
                idempotencyKey,
            ).successBody(),
            BindingOut::class.java,
        ).let { binding ->
            check(binding.blockId.toString() == blockId && binding.projectId.toString() == projectId) {
                "Service returned an unexpected block binding"
            }
            BlockBinding(binding.id.toString(), blockId, projectId)
        }
    }

    override fun unbindBlock(session: Session, blockId: String) {
        client.unbindBlock(session.identityToken, session.tenantId, blockId).successBody()
    }

    override fun lookupBlock(session: Session, tagToken: String): BlockLookup = decodeContract {
        json.fromJson(
            client.lookupBlock(
                session.identityToken,
                session.tenantId,
                json.toJson(LookupIn(tagToken)),
            ).successBody(),
            LookupOut::class.java,
        ).let { BlockLookup(it.blockId.toString(), it.label, it.projectId?.toString()) }
    }

    override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
        return decodeContract {
            val response = json.fromJson(
                client.changes(session.identityToken, session.tenantId, cursor, limit).successBody(),
                ChangesOut::class.java,
            )
            ChangePage(
                events = response.events.map { event ->
                    check(event.tenantId.toString() == session.tenantId) {
                        "Service returned a change from another tenant"
                    }
                    ChangeEvent(
                        eventId = event.eventId,
                        eventType = event.eventType,
                        eventVersion = event.eventVersion,
                        tenantId = event.tenantId.toString(),
                        payload = event.payload.mapValues { it.value },
                    )
                },
                nextCursor = response.nextSince,
                hasMore = response.hasMore,
            )
        }
    }

    override fun createNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note =
        decodeContract {
            json.fromJson(
                client.createNote(
                    session.identityToken,
                    session.tenantId,
                    operation.projectId,
                    json.toJson(
                        NoteCreateIn(
                            body = requireNotNull(operation.noteBody),
                            id = UUID.fromString(operation.targetId),
                            operationId = UUID.fromString(operation.operationId),
                        ),
                    ),
                ).successBody(),
                NoteAck::class.java,
            ).toAcknowledgement(session, operation)
        }

    override fun editNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note =
        noteMutation(session, operation) { body ->
            client.editNote(
                session.identityToken,
                session.tenantId,
                operation.projectId,
                operation.targetId,
                requireNotNull(operation.ifMatch),
                body,
            )
        }

    override fun pinNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note =
        noteMutation(session, operation) { body ->
            client.pinNote(
                session.identityToken,
                session.tenantId,
                operation.projectId,
                operation.targetId,
                requireNotNull(operation.ifMatch),
                body,
            )
        }

    override fun deleteNote(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Note =
        noteMutation(session, operation) { body ->
            client.deleteNote(
                session.identityToken,
                session.tenantId,
                operation.projectId,
                operation.targetId,
                requireNotNull(operation.ifMatch),
                body,
            )
        }

    override fun readNote(session: Session, projectId: String, noteId: String): ProjectNoteItem = decodeContract {
        json.fromJson(
            client.readNote(session.identityToken, session.tenantId, projectId, noteId).successBody(),
            NoteOut::class.java,
        ).toNote(session, projectId, noteId)
    }

    override fun createLink(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Link =
        decodeContract {
            json.fromJson(
                client.createLink(
                    session.identityToken,
                    session.tenantId,
                    operation.projectId,
                    json.toJson(
                        LinkCreateIn(
                            id = UUID.fromString(operation.targetId),
                            operationId = UUID.fromString(operation.operationId),
                            title = requireNotNull(operation.linkTitle),
                            uri = requireNotNull(operation.linkUri),
                        ),
                    ),
                ).successBody(),
                LinkAck::class.java,
            ).toAcknowledgement(session, operation)
        }

    override fun editLink(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Link =
        linkMutation(session, operation) { body ->
            client.editLink(
                session.identityToken,
                session.tenantId,
                operation.projectId,
                operation.targetId,
                requireNotNull(operation.ifMatch),
                body,
            )
        }

    override fun deleteLink(session: Session, operation: ProjectItemMutation): ProjectItemAcknowledgement.Link =
        linkMutation(session, operation) { body ->
            client.deleteLink(
                session.identityToken,
                session.tenantId,
                operation.projectId,
                operation.targetId,
                requireNotNull(operation.ifMatch),
                body,
            )
        }

    override fun readLink(session: Session, projectId: String, linkId: String): ProjectResourceLink = decodeContract {
        json.fromJson(
            client.readLink(session.identityToken, session.tenantId, projectId, linkId).successBody(),
            LinkOut::class.java,
        ).toLink(session, projectId, linkId)
    }

    override fun attachNavigationKey(session: Session, projectId: String, key: ProjectKey): ProjectKey =
        decodeContract {
            json.fromJson(
                client.attachNavigationAlias(
                    session.identityToken,
                    session.tenantId,
                    projectId,
                    json.toJson(AliasIn(UUID.fromString(key.encoded))),
                ).successBody(),
                AliasOut::class.java,
            ).let { alias ->
                check(alias.projectId.toString() == projectId && alias.projectKey.toString() == key.encoded) {
                    "Service returned an unexpected navigation alias"
                }
                key
            }
        }

    override fun resolveNavigationKey(session: Session, key: ProjectKey): String? = decodeContract {
        try {
            json.fromJson(
                client.resolveNavigationAlias(
                    session.identityToken,
                    session.tenantId,
                    json.toJson(ResolveKeyIn(UUID.fromString(key.encoded))),
                ).successBody(),
                ResolveKeyOut::class.java,
            ).projectId.toString()
        } catch (error: ServiceException) {
            if (error.status == 404) null else throw error
        }
    }

    override fun projectOrder(session: Session): AuthoritativeOrder = decodeContract {
        json.fromJson(
            client.projectOrder(session.identityToken, session.tenantId).successBody(),
            OrderOut::class.java,
        ).toAuthoritativeOrder(null)
    }

    override fun reorderProjects(
        session: Session,
        operation: InteractionMutation,
    ): InteractionAcknowledgement.Order = reorder(session, operation) { body ->
        client.reorderProjects(session.identityToken, session.tenantId, body)
    }

    override fun linkOrder(session: Session, projectId: String): AuthoritativeOrder = decodeContract {
        json.fromJson(
            client.linkOrder(session.identityToken, session.tenantId, projectId).successBody(),
            OrderOut::class.java,
        ).toAuthoritativeOrder(projectId)
    }

    override fun reorderLinks(
        session: Session,
        operation: InteractionMutation,
    ): InteractionAcknowledgement.Order = reorder(session, operation) { body ->
        client.reorderLinks(
            session.identityToken,
            session.tenantId,
            requireNotNull(operation.projectId),
            body,
        )
    }

    override fun parkingTombstones(session: Session, projectId: String): List<ParkingTombstone> = decodeContract {
        json.fromJson<List<ParkingTombstoneOut>>(
            client.parkingTombstones(session.identityToken, session.tenantId, projectId).successBody(),
            PARKING_TOMBSTONE_LIST_TYPE,
        ).map { tombstone ->
            check(tombstone.projectId.toString() == projectId) {
                "Service returned a Park tombstone from another Project"
            }
            ParkingTombstone(
                parkingId = tombstone.targetId.toString(),
                projectId = tombstone.projectId.toString(),
                version = tombstone.version,
                deletedAt = tombstone.deletedAt,
                lastOperationId = tombstone.operationId.toString(),
            )
        }
    }

    override fun deleteParking(
        session: Session,
        operation: InteractionMutation,
    ): InteractionAcknowledgement.Parking = decodeContract {
        require(operation.type == InteractionMutationType.DELETE_PARKING)
        val targetId = requireNotNull(operation.targetId)
        val projectId = requireNotNull(operation.projectId)
        val response = json.fromJson(
            client.deleteParking(
                session.identityToken,
                session.tenantId,
                projectId,
                targetId,
                json.toJson(
                    DeleteParkingIn(
                        aggregateId = UUID.fromString(operation.aggregateId),
                        baseVersion = operation.baseVersion,
                        operationId = UUID.fromString(operation.operationId),
                        targetId = UUID.fromString(targetId),
                    ),
                ),
            ).successBody(),
            ParkingDeleteOut::class.java,
        )
        check(response.operationId.toString() == operation.operationId &&
            response.aggregateId.toString() == operation.aggregateId &&
            response.projectId.toString() == projectId &&
            response.targetId.toString() == targetId) {
            "Service acknowledged a different Park deletion operation"
        }
        InteractionAcknowledgement.Parking(
            operationId = response.operationId.toString(),
            aggregateId = response.aggregateId.toString(),
            version = response.version,
            projectId = response.projectId.toString(),
            targetId = response.targetId.toString(),
            deletedAt = response.deletedAt,
            changed = response.changed,
        )
    }

    private fun reorder(
        session: Session,
        operation: InteractionMutation,
        send: (String) -> ApiResponse,
    ): InteractionAcknowledgement.Order = decodeContract {
        require(operation.type in setOf(
            InteractionMutationType.REORDER_PROJECTS,
            InteractionMutationType.REORDER_LINKS,
        ))
        val response = json.fromJson(
            send(
                json.toJson(
                    ReorderIn(
                        aggregateId = UUID.fromString(operation.aggregateId),
                        baseVersion = operation.baseVersion,
                        operationId = UUID.fromString(operation.operationId),
                        orderedClientIds = operation.orderedClientIds.map(UUID::fromString),
                    ),
                ),
            ).successBody(),
            OrderOut::class.java,
        )
        check(response.operationId?.toString() == operation.operationId &&
            response.aggregateId.toString() == operation.aggregateId &&
            response.projectId?.toString() == operation.projectId) {
            "Service acknowledged a different order operation"
        }
        InteractionAcknowledgement.Order(
            operationId = requireNotNull(response.operationId).toString(),
            aggregateId = response.aggregateId.toString(),
            version = response.version,
            projectId = response.projectId?.toString(),
            orderedClientIds = response.orderedClientIds.map(UUID::toString),
        )
    }

    private fun OrderOut.toAuthoritativeOrder(expectedProjectId: String?): AuthoritativeOrder {
        check(projectId?.toString() == expectedProjectId) { "Service returned an unexpected order aggregate" }
        return AuthoritativeOrder(
            aggregateId = aggregateId.toString(),
            projectId = projectId?.toString(),
            orderedClientIds = orderedClientIds.map(UUID::toString),
            version = version,
            operationId = operationId?.toString(),
        )
    }

    private fun noteMutation(
        session: Session,
        operation: ProjectItemMutation,
        send: (String) -> ApiResponse,
    ): ProjectItemAcknowledgement.Note = decodeContract {
        val request = when (operation.type) {
            dev.blocks.android.project.ProjectItemMutationType.EDIT_NOTE ->
                NoteEditIn(requireNotNull(operation.noteBody), UUID.fromString(operation.operationId))
            dev.blocks.android.project.ProjectItemMutationType.PIN_NOTE ->
                NotePinIn(UUID.fromString(operation.operationId), requireNotNull(operation.pinned))
            dev.blocks.android.project.ProjectItemMutationType.DELETE_NOTE ->
                DeleteIn(UUID.fromString(operation.operationId))
            else -> error("Unsupported note mutation ${operation.type}")
        }
        json.fromJson(send(json.toJson(request)).successBody(), NoteAck::class.java)
            .toAcknowledgement(session, operation)
    }

    private fun linkMutation(
        session: Session,
        operation: ProjectItemMutation,
        send: (String) -> ApiResponse,
    ): ProjectItemAcknowledgement.Link = decodeContract {
        val request = when (operation.type) {
            dev.blocks.android.project.ProjectItemMutationType.EDIT_LINK -> LinkEditIn(
                UUID.fromString(operation.operationId),
                requireNotNull(operation.linkTitle),
                requireNotNull(operation.linkUri),
            )
            dev.blocks.android.project.ProjectItemMutationType.DELETE_LINK ->
                DeleteIn(UUID.fromString(operation.operationId))
            else -> error("Unsupported link mutation ${operation.type}")
        }
        json.fromJson(send(json.toJson(request)).successBody(), LinkAck::class.java)
            .toAcknowledgement(session, operation)
    }

    private fun projectAction(
        session: Session,
        projectId: String,
        rowVersion: Int,
        archive: Boolean,
    ): ProjectSummary {
        val response = if (archive) {
            client.archiveProject(session.identityToken, session.tenantId, projectId, rowVersion)
        } else {
            client.reopenProject(session.identityToken, session.tenantId, projectId, rowVersion)
        }
        return decodeContract {
            json.fromJson(response.successBody(), ProjectOut::class.java)
                .toProject(session)
                .also { check(it.id == projectId) }
        }
    }

    private fun ProjectPageOut.toProjectPage(session: Session, expectedProjectId: String): ProjectPage {
        val mappedProject = project.toProject(session)
        check(mappedProject.id == expectedProjectId) { "Service returned an unexpected project page" }
        return ProjectPage(
            project = mappedProject,
            notes = notes.toNotes(expectedProjectId),
            newestParking = newestParking?.toParking(expectedProjectId),
        )
    }

    private fun ProjectOut.toProject(session: Session): ProjectSummary {
        check(tenantId.toString() == session.tenantId) { "Service returned a project from another tenant" }
        return ProjectSummary(
            id = id.toString(),
            tenantId = tenantId.toString(),
            name = name,
            lifecycle = ProjectLifecycle.valueOf(status.name),
            rowVersion = rowVersion,
            createdAt = createdAt,
            updatedAt = updatedAt,
            archivedAt = archivedAt,
            clientId = clientId?.toString(),
            navigationKey = navigationKey?.toString(),
        )
    }

    private fun NoteOut.toNote(
        session: Session,
        expectedProjectId: String,
        expectedItemId: String,
    ): ProjectNoteItem {
        check(tenantId.toString() == session.tenantId) { "Service returned a note from another tenant" }
        check(projectId.toString() == expectedProjectId && id.toString() == expectedItemId) {
            "Service returned an unexpected note identity"
        }
        return ProjectNoteItem(
            id = id.toString(),
            projectId = projectId.toString(),
            body = body,
            pinned = pinned,
            authoredByUserId = authoredByUserId?.toString(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            rowVersion = rowVersion,
            deletedAt = deletedAt,
            lastOperationId = lastOperationId.toString(),
        )
    }

    private fun NoteAck.toAcknowledgement(
        session: Session,
        expected: ProjectItemMutation,
    ): ProjectItemAcknowledgement.Note {
        check(tenantId.toString() == session.tenantId) { "Service acknowledged a note from another tenant" }
        check(projectId.toString() == expected.projectId && id.toString() == expected.targetId) {
            "Service acknowledged an unexpected note identity"
        }
        check(operationId.toString() == expected.operationId) {
            "Service acknowledged a different note operation"
        }
        return ProjectItemAcknowledgement.Note(
            operationId = operationId.toString(),
            item = ProjectNoteItem(
                id = id.toString(),
                projectId = projectId.toString(),
                body = body,
                pinned = pinned,
                authoredByUserId = authoredByUserId?.toString(),
                createdAt = createdAt,
                updatedAt = updatedAt,
                rowVersion = rowVersion,
                deletedAt = deletedAt,
                lastOperationId = lastOperationId.toString(),
            ),
        )
    }

    private fun LinkOut.toLink(
        session: Session,
        expectedProjectId: String,
        expectedItemId: String,
    ): ProjectResourceLink {
        check(tenantId.toString() == session.tenantId) { "Service returned a link from another tenant" }
        check(projectId.toString() == expectedProjectId && id.toString() == expectedItemId) {
            "Service returned an unexpected link identity"
        }
        return ProjectResourceLink(
            id = id.toString(),
            projectId = projectId.toString(),
            title = title,
            uri = uri,
            createdAt = createdAt,
            updatedAt = updatedAt,
            rowVersion = rowVersion,
            deletedAt = deletedAt,
            lastOperationId = lastOperationId.toString(),
            requiresReconciliation = !dev.blocks.android.project.ProjectResourceUri.canLaunch(uri),
        )
    }

    private fun LinkAck.toAcknowledgement(
        session: Session,
        expected: ProjectItemMutation,
    ): ProjectItemAcknowledgement.Link {
        check(tenantId.toString() == session.tenantId) { "Service acknowledged a link from another tenant" }
        check(projectId.toString() == expected.projectId && id.toString() == expected.targetId) {
            "Service acknowledged an unexpected link identity"
        }
        check(operationId.toString() == expected.operationId) {
            "Service acknowledged a different link operation"
        }
        return ProjectItemAcknowledgement.Link(
            operationId = operationId.toString(),
            item = ProjectResourceLink(
                id = id.toString(),
                projectId = projectId.toString(),
                title = title,
                uri = uri,
                createdAt = createdAt,
                updatedAt = updatedAt,
                rowVersion = rowVersion,
                deletedAt = deletedAt,
                lastOperationId = lastOperationId.toString(),
                requiresReconciliation = !dev.blocks.android.project.ProjectResourceUri.canLaunch(uri),
            ),
        )
    }

    private fun NotesOut.toNotes(expectedProjectId: String): ProjectNotes {
        check(projectId.toString() == expectedProjectId) { "Service returned notes for another project" }
        return ProjectNotes(projectId.toString(), body, rowVersion, updatedAt)
    }

    private fun ProjectParkingOut.toParking(expectedProjectId: String): ProjectParking {
        check(projectId.toString() == expectedProjectId) { "Service returned parking for another project" }
        return ProjectParking(
            id = id.toString(),
            projectId = projectId.toString(),
            location = whereIAm,
            nextAction = nextPhysicalAction,
            mustNotForget = mustNotForget,
            authoredByUserId = authoredByUserId.toString(),
            createdAt = createdAt,
            occurredAt = occurredAt,
            clientId = clientId?.toString(),
            deviceId = deviceId?.toString(),
            supersedesId = supersedesId?.toString(),
            rowVersion = rowVersion ?: 1,
            deletedAt = deletedAt,
            lastOperationId = lastOperationId?.toString(),
        )
    }

    private fun BlockOut.toBlock() = PhysicalBlock(id.toString(), label, state, boundProjectId?.toString())

    private fun RegisteredBlockOut.toRegisteredBlock() = RegisteredBlock(
        block = PhysicalBlock(id.toString(), label, state, boundProjectId?.toString()),
        tagToken = tagToken,
    )

    private fun ApiResponse.successBody(): String = when (this) {
        is ApiResponse.Success -> body
        is ApiResponse.Failure -> throw ServiceException(status, error)
    }

    private inline fun <T> decodeContract(decode: () -> T): T = try {
        decode()
    } catch (error: ServiceException) {
        throw error
    } catch (error: RuntimeException) {
        throw IncompatibleServiceApiException(error)
    }

    private companion object {
        val BLOCK_LIST_TYPE = object : TypeToken<List<BlockOut>>() {}.type
        val PARKING_LIST_TYPE = object : TypeToken<List<ProjectParkingOut>>() {}.type
        val PARKING_TOMBSTONE_LIST_TYPE = object : TypeToken<List<ParkingTombstoneOut>>() {}.type
        val PROJECT_LIST_TYPE = object : TypeToken<List<ProjectOut>>() {}.type
    }
}
