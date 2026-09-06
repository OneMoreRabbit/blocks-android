package dev.blocks.android.mvp

data class Session(val tenantId: String, val identityToken: String = "")
data class Project(val id: String, val name: String)
data class ParkingRecord(
    val location: String,
    val nextAction: String,
    val mustNotForget: String,
    val id: String? = null,
    val createdAt: String? = null,
    val sequence: Int? = null,
)

enum class WorkSessionState(val wireValue: String) {
    ACTIVE("active"),
    PARKED("parked"),
    CLOSED("closed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): WorkSessionState = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class WorkSession(
    val id: String,
    val projectId: String,
    val state: WorkSessionState,
    val coreOutcome: String,
    val rowVersion: Int,
    val fullOutcome: String? = null,
    val polishOutcome: String? = null,
    val boundaryAt: String? = null,
    val physicalBlockId: String? = null,
)

data class Reentry(
    val workSession: WorkSession?,
    val parking: ParkingRecord?,
)

data class PickupRequest(
    val projectId: String,
    val coreOutcome: String,
    val fullOutcome: String? = null,
    val polishOutcome: String? = null,
    val boundaryAt: String? = null,
)

sealed interface PickupResult {
    data class Started(val reentry: Reentry) : PickupResult
    data class ExistingSession(val reentry: Reentry) : PickupResult
}

enum class OutcomeTier(val wireValue: String) {
    CORE("core"),
    FULL("full"),
    POLISH("polish"),
}

enum class ParkingAction { PARK, PARK_AND_CLOSE }

data class ParkingDraft(
    val location: String = "",
    val nextAction: String = "",
    val mustNotForget: String = "",
    val idempotencyKey: String = "",
)

data class PickupDraft(
    val projectId: String = "",
    val coreOutcome: String = "",
    val fullOutcome: String = "",
    val polishOutcome: String = "",
    val idempotencyKey: String = "",
)

data class FormDrafts(
    val projectName: String = "",
    val pickup: PickupDraft = PickupDraft(),
    val parking: ParkingDraft = ParkingDraft(),
)

interface DraftStore {
    fun load(): FormDrafts
    fun save(drafts: FormDrafts)
}

object NoOpDraftStore : DraftStore {
    override fun load() = FormDrafts()
    override fun save(drafts: FormDrafts) = Unit
}

interface BlocksRepository {
    fun login(identityToken: String): Session
    fun projects(session: Session): List<Project>
    fun createProject(session: Session, name: String): Project
    fun current(session: Session): Reentry
    fun pickup(session: Session, request: PickupRequest, idempotencyKey: String): PickupResult
    fun park(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry
    fun resume(session: Session, workSession: WorkSession): Reentry
    fun closeWithContinuation(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry
    fun closeOnExistingContinuation(
        session: Session,
        workSession: WorkSession,
        parking: ParkingRecord,
        idempotencyKey: String,
    ): Reentry
    fun closeWithOutcome(
        session: Session,
        workSession: WorkSession,
        outcomeTier: OutcomeTier,
        idempotencyKey: String,
    ): Reentry
}
