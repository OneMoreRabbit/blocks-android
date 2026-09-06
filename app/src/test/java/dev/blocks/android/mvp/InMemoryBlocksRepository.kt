package dev.blocks.android.mvp

/** Test-only repository fake. Production always uses the Service API implementation. */
class InMemoryBlocksRepository(
    private val tenantForToken: (String) -> String = { "development-tenant" },
) : BlocksRepository {
    private val projectList = mutableListOf(Project("first-project", "First project"))
    private var reentry = Reentry(null, null)
    val pickupIdempotencyKeys = mutableListOf<String>()
    val parkingIdempotencyKeys = mutableListOf<String>()
    val closeContinuationIdempotencyKeys = mutableListOf<String>()
    val closeExistingIdempotencyKeys = mutableListOf<String>()
    val closeOutcomeIdempotencyKeys = mutableListOf<String>()
    var pickupFailure: RuntimeException? = null
    var parkingFailure: RuntimeException? = null
    var closeContinuationFailure: RuntimeException? = null
    var closeExistingFailure: RuntimeException? = null
    var closeOutcomeFailure: RuntimeException? = null
    var resumeFailure: RuntimeException? = null
    var currentOverride: Reentry? = null
    var currentCalls = 0

    override fun login(identityToken: String): Session {
        require(identityToken.isNotBlank())
        return Session(tenantForToken(identityToken), identityToken)
    }

    override fun projects(session: Session): List<Project> = projectList.toList()

    override fun createProject(session: Session, name: String): Project =
        Project("project-${projectList.size + 1}", name.trim()).also(projectList::add)

    override fun current(session: Session): Reentry {
        currentCalls += 1
        return currentOverride ?: reentry
    }

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

    override fun resume(session: Session, workSession: WorkSession): Reentry {
        resumeFailure?.let { throw it }
        return transition(
            workSession.copy(state = WorkSessionState.ACTIVE, rowVersion = workSession.rowVersion + 1),
            reentry.parking,
        )
    }

    override fun closeWithContinuation(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        closeContinuationIdempotencyKeys += idempotencyKey
        closeContinuationFailure?.let { throw it }
        return transition(
            workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
            record.copy(id = "parking-${workSession.id}", sequence = 1),
        )
    }

    override fun closeOnExistingContinuation(
        session: Session,
        workSession: WorkSession,
        parking: ParkingRecord,
        idempotencyKey: String,
    ): Reentry {
        closeExistingIdempotencyKeys += idempotencyKey
        closeExistingFailure?.let { throw it }
        return transition(
            workSession.copy(state = WorkSessionState.CLOSED, rowVersion = workSession.rowVersion + 1),
            parking,
        )
    }

    override fun closeWithOutcome(
        session: Session,
        workSession: WorkSession,
        outcomeTier: OutcomeTier,
        idempotencyKey: String,
    ): Reentry {
        closeOutcomeIdempotencyKeys += idempotencyKey
        closeOutcomeFailure?.let { throw it }
        return transition(null, null)
    }

    fun setReentry(value: Reentry) {
        reentry = value
    }

    private fun transition(workSession: WorkSession?, parking: ParkingRecord?): Reentry {
        reentry = Reentry(workSession, parking)
        return reentry
    }
}
