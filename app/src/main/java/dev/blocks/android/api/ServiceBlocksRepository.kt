package dev.blocks.android.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.blocks.android.api.generated.model.CloseOnExistingContinuation
import dev.blocks.android.api.generated.model.CloseOut
import dev.blocks.android.api.generated.model.CloseWithContinuation
import dev.blocks.android.api.generated.model.CloseWithOutcome
import dev.blocks.android.api.generated.model.ChangesOut
import dev.blocks.android.api.generated.model.ParkIn
import dev.blocks.android.api.generated.model.ParkOut
import dev.blocks.android.api.generated.model.ParkingOut
import dev.blocks.android.api.generated.model.PickupByProject
import dev.blocks.android.api.generated.model.ProjectIn
import dev.blocks.android.api.generated.model.ProjectOut
import dev.blocks.android.api.generated.model.ReentryOut
import dev.blocks.android.api.generated.model.SessionOut
import dev.blocks.android.api.generated.model.TransitionOut
import dev.blocks.android.api.generated.model.WorkSessionOut
import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TransitionConflictException
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import java.util.UUID

class ServiceBlocksRepository(
    private val client: BlocksTransport,
    private val json: Gson = Gson(),
) : BlocksRepository {
    override fun login(identityToken: String): Session {
        val response = json.fromJson(client.exchangeSession(identityToken).successBody(), SessionOut::class.java)
        return Session(
            tenantId = response.personalTenantId.toString(),
            identityToken = identityToken,
        )
    }

    override fun projects(session: Session): List<Project> =
        json.fromJson<List<ProjectOut>>(
            client.listProjects(session.identityToken, session.tenantId).successBody(),
            PROJECT_LIST_TYPE,
        ).map { toProject(it, session.tenantId) }

    override fun createProject(session: Session, name: String): Project {
        val request = json.toJson(ProjectIn(name))
        return toProject(
            json.fromJson(
                client.createProject(session.identityToken, session.tenantId, request).successBody(),
                ProjectOut::class.java,
            ),
            session.tenantId,
        )
    }

    override fun current(session: Session): Reentry = json.fromJson(
        client.current(session.identityToken, session.tenantId).successBody(),
        ReentryOut::class.java,
    ).toReentry()

    override fun pickup(
        session: Session,
        request: PickupRequest,
        idempotencyKey: String,
    ): PickupResult {
        val body = json.toJson(
            PickupByProject(
                coreOutcome = request.coreOutcome,
                projectId = UUID.fromString(request.projectId),
                boundaryAt = request.boundaryAt,
                fullOutcome = request.fullOutcome,
                polishOutcome = request.polishOutcome,
            ),
        )
        return when (val response = client.pickup(session.identityToken, session.tenantId, body, idempotencyKey)) {
            is ApiResponse.Success -> {
                val workSession = json.fromJson(response.body, WorkSessionOut::class.java).toWorkSession()
                PickupResult.Started(Reentry(workSession, null))
            }
            is ApiResponse.Failure -> {
                if (response.status == 409 && response.error.code == ApiErrorCode.CONFLICT) {
                    PickupResult.ExistingSession(current(session))
                } else {
                    throw response.toException()
                }
            }
        }
    }

    override fun park(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        val response = json.fromJson(
            client.park(
                session.identityToken,
                session.tenantId,
                workSession.id,
                workSession.rowVersion,
                json.toJson(record.toParkIn()),
                idempotencyKey,
            ).transitionBody(),
            ParkOut::class.java,
        )
        return Reentry(
            workSession = workSession.withTransition(response.session),
            parking = response.parking.toParkingRecord(),
        )
    }

    override fun resume(session: Session, workSession: WorkSession): Reentry {
        val transition = json.fromJson(
            client.resume(
                session.identityToken,
                session.tenantId,
                workSession.id,
                workSession.rowVersion,
            ).transitionBody(),
            TransitionOut::class.java,
        )
        workSession.withTransition(transition)
        return current(session)
    }

    override fun closeWithContinuation(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        val response = close(
            session,
            workSession,
            json.toJson(
                CloseWithContinuation(
                    mustNotForget = record.mustNotForget,
                    nextPhysicalAction = record.nextAction,
                    whereIAm = record.location,
                ),
            ),
            idempotencyKey,
        )
        return Reentry(
            workSession = workSession.withClose(response),
            parking = record.copy(id = response.parkingRecordId?.toString()),
        )
    }

    override fun closeOnExistingContinuation(
        session: Session,
        workSession: WorkSession,
        parking: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        val response = close(
            session,
            workSession,
            json.toJson(
                CloseOnExistingContinuation(CloseOnExistingContinuation.Preserve.EXISTING_CONTINUATION),
            ),
            idempotencyKey,
        )
        return Reentry(workSession.withClose(response), parking)
    }

    override fun closeWithOutcome(
        session: Session,
        workSession: WorkSession,
        outcomeTier: OutcomeTier,
        idempotencyKey: String,
    ): Reentry {
        close(
            session,
            workSession,
            json.toJson(CloseWithOutcome(outcomeTier.wireValue)),
            idempotencyKey,
        )
        return current(session)
    }

    override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
        val response = json.fromJson(
            client.changes(session.identityToken, session.tenantId, cursor, limit).successBody(),
            ChangesOut::class.java,
        )
        return ChangePage(
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

    private fun toProject(project: ProjectOut, expectedTenantId: String): Project {
        check(project.tenantId.toString() == expectedTenantId) { "Service returned a project from another tenant" }
        return Project(
            id = project.id.toString(),
            name = project.name,
        )
    }

    private fun ApiResponse.successBody(): String = when (this) {
        is ApiResponse.Success -> body
        is ApiResponse.Failure -> throw toException()
    }

    private fun ApiResponse.Failure.toException() = ServiceException(status, error)

    private fun ApiResponse.transitionBody(): String = when (this) {
        is ApiResponse.Success -> body
        is ApiResponse.Failure -> {
            if (status == 409 && error.code == ApiErrorCode.CONFLICT) {
                throw TransitionConflictException(error.message)
            }
            throw toException()
        }
    }

    private fun close(
        session: Session,
        workSession: WorkSession,
        body: String,
        idempotencyKey: String,
    ): CloseOut = json.fromJson(
        client.close(
            session.identityToken,
            session.tenantId,
            workSession.id,
            workSession.rowVersion,
            body,
            idempotencyKey,
        ).transitionBody(),
        CloseOut::class.java,
    )

    private fun ReentryOut.toReentry() = Reentry(
        workSession = session?.toWorkSession(),
        parking = parking?.toParkingRecord(),
    )

    private fun WorkSessionOut.toWorkSession() = WorkSession(
        id = id.toString(),
        projectId = projectId.toString(),
        state = WorkSessionState.fromWire(state),
        coreOutcome = coreOutcome,
        rowVersion = rowVersion,
        fullOutcome = fullOutcome,
        polishOutcome = polishOutcome,
        boundaryAt = boundaryAt,
        physicalBlockId = physicalBlockId?.toString(),
    )

    private fun ParkingOut.toParkingRecord() = ParkingRecord(
        location = whereIAm,
        nextAction = nextPhysicalAction,
        mustNotForget = mustNotForget,
        id = id.toString(),
        createdAt = createdAt,
        sequence = sequence,
    )

    private fun ParkingRecord.toParkIn() = ParkIn(
        mustNotForget = mustNotForget,
        nextPhysicalAction = nextAction,
        whereIAm = location,
    )

    private fun WorkSession.withTransition(transition: TransitionOut): WorkSession {
        check(transition.id.toString() == id) { "Service transitioned an unexpected session" }
        return copy(
            state = WorkSessionState.fromWire(transition.state),
            rowVersion = transition.rowVersion,
        )
    }

    private fun WorkSession.withClose(close: CloseOut): WorkSession {
        check(close.id.toString() == id) { "Service closed an unexpected session" }
        return copy(
            state = WorkSessionState.fromWire(close.state),
            rowVersion = close.rowVersion,
        )
    }

    private companion object {
        val PROJECT_LIST_TYPE = object : TypeToken<List<ProjectOut>>() {}.type
    }
}

class ServiceException(
    val status: Int,
    val error: ApiError,
) : RuntimeException(error.message)
