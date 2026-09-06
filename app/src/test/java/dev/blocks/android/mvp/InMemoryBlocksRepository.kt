package dev.blocks.android.mvp

/** Test-only repository fake. Production always uses the Service API implementation. */
class InMemoryBlocksRepository : BlocksRepository {
    private val projectList = mutableListOf(Project("first-project", "First project"))
    private var reentry = Reentry(null, null)
    val pickupIdempotencyKeys = mutableListOf<String>()
    val parkingIdempotencyKeys = mutableListOf<String>()
    var pickupFailure: RuntimeException? = null
    var parkingFailure: RuntimeException? = null

    override fun login(identityToken: String): Session {
        require(identityToken.isNotBlank())
        return Session("development-tenant", identityToken)
    }

    override fun projects(session: Session): List<Project> = projectList.toList()

    override fun createProject(session: Session, name: String): Project =
        Project("project-${projectList.size + 1}", name.trim()).also(projectList::add)

    override fun current(session: Session): Reentry = reentry

    override fun pickup(session: Session, request: PickupRequest, idempotencyKey: String): PickupResult {
        pickupIdempotencyKeys += idempotencyKey
        pickupFailure?.let { throw it }
        val existing = reentry.workSession?.takeIf { it.state == WorkSessionState.ACTIVE }
        if (existing != null) return PickupResult.ExistingSession(reentry)
        val workSession = WorkSession(
            id = "session-${request.projectId}",
            projectId = request.projectId,
            state = WorkSessionState.ACTIVE,
            coreOutcome = request.coreOutcome,
            rowVersion = 1,
            fullOutcome = request.fullOutcome,
            polishOutcome = request.polishOutcome,
            boundaryAt = request.boundaryAt,
        )
        reentry = Reentry(workSession, null)
        return PickupResult.Started(reentry)
    }

    override fun park(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        parkingIdempotencyKeys += idempotencyKey
        parkingFailure?.let { throw it }
        return transition(
            workSession.copy(state = WorkSessionState.PARKED, rowVersion = workSession.rowVersion + 1),
            record.copy(id = "parking-${workSession.id}", sequence = 1),
        )
    }

    override fun resume(session: Session, workSession: WorkSession): Reentry = transition(
        workSession.copy(state = WorkSessionState.ACTIVE, rowVersion = workSession.rowVersion + 1),
        reentry.parking,
    )

    override fun closeWithContinuation(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry = transition(
        workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
        record.copy(id = "parking-${workSession.id}", sequence = 1),
    )

    override fun closeOnExistingContinuation(
        session: Session,
        workSession: WorkSession,
        parking: ParkingRecord,
        idempotencyKey: String,
    ): Reentry = transition(
        workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
        parking,
    )

    override fun closeWithOutcome(
        session: Session,
        workSession: WorkSession,
        outcomeTier: OutcomeTier,
        idempotencyKey: String,
    ): Reentry = transition(null, null)

    fun setReentry(value: Reentry) {
        reentry = value
    }

    private fun transition(workSession: WorkSession?, parking: ParkingRecord?): Reentry {
        reentry = Reentry(workSession, parking)
        return reentry
    }
}
