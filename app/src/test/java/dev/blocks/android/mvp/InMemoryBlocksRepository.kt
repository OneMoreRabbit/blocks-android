package dev.blocks.android.mvp

/** Test-only repository fake. Production always uses the Service API implementation. */
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
