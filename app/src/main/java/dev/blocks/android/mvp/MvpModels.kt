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

/** Development implementation used until the accepted API 0.2 artifacts are delivered. */
class InMemoryBlocksRepository : BlocksRepository {
    private val projectList = mutableListOf(Project("first-project", "First project"))
    private val parking = mutableMapOf<String, ParkingRecord>()

    override fun login(identityToken: String): Session {
        require(identityToken.isNotBlank())
        return Session("development-tenant", identityToken)
    }

    override fun projects(session: Session): List<Project> = projectList.toList()

    override fun createProject(session: Session, name: String): Project =
        Project("project-${projectList.size + 1}", name.trim()).also(projectList::add)

    override fun latestParking(session: Session, projectId: String): ParkingRecord? = parking[projectId]

    override fun park(session: Session, projectId: String, record: ParkingRecord) {
        parking[projectId] = record
    }
}
