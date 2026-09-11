package dev.blocks.android.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.blocks.android.api.generated.v04.model.BindIn
import dev.blocks.android.api.generated.v04.model.BindingOut
import dev.blocks.android.api.generated.v04.model.BlockIn
import dev.blocks.android.api.generated.v04.model.BlockOut
import dev.blocks.android.api.generated.v04.model.ChangesOut
import dev.blocks.android.api.generated.v04.model.LookupIn
import dev.blocks.android.api.generated.v04.model.LookupOut
import dev.blocks.android.api.generated.v04.model.NotesIn
import dev.blocks.android.api.generated.v04.model.NotesOut
import dev.blocks.android.api.generated.v04.model.ProjectIn
import dev.blocks.android.api.generated.v04.model.ProjectOut
import dev.blocks.android.api.generated.v04.model.ProjectPageOut
import dev.blocks.android.api.generated.v04.model.ProjectParkIn
import dev.blocks.android.api.generated.v04.model.ProjectParkingOut
import dev.blocks.android.api.generated.v04.model.ProjectRename
import dev.blocks.android.api.generated.v04.model.RegisteredBlockOut
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.BlockLookup
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.RegisteredBlock
import java.util.UUID

/** Maps generated API 0.4 transport models into Android-owned project-domain models. */
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
                    json.toJson(BlockIn(label)),
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
        val PROJECT_LIST_TYPE = object : TypeToken<List<ProjectOut>>() {}.type
    }
}
