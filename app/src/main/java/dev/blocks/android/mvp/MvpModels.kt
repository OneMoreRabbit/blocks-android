package dev.blocks.android.mvp

data class Session(val tenantId: String, val identityToken: String = "")
data class Project(val id: String, val name: String)
data class ParkingRecord(val location: String, val nextAction: String, val mustNotForget: String)
data class ParkingDraft(
    val location: String = "",
    val nextAction: String = "",
    val mustNotForget: String = "",
)

data class FormDrafts(
    val projectName: String = "",
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
    fun latestParking(session: Session, projectId: String): ParkingRecord?
    fun park(session: Session, projectId: String, record: ParkingRecord)
}
