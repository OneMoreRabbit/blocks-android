package dev.blocks.android.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.blocks.android.api.generated.model.ProjectIn
import dev.blocks.android.api.generated.model.ProjectOut
import dev.blocks.android.api.generated.model.SessionOut
import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.Session

class ServiceBlocksRepository(
    private val client: BlocksTransport,
    private val json: Gson = Gson(),
) : BlocksRepository {
    private val parking = mutableMapOf<String, ParkingRecord>()

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
        ).map(::toProject)

    override fun createProject(session: Session, name: String): Project {
        val request = json.toJson(ProjectIn(name))
        return toProject(
            json.fromJson(
                client.createProject(session.identityToken, session.tenantId, request).successBody(),
                ProjectOut::class.java,
            ),
        )
    }

    override fun latestParking(session: Session, projectId: String): ParkingRecord? = parking[projectId]

    override fun park(session: Session, projectId: String, record: ParkingRecord) {
        parking[projectId] = record
    }

    private fun toProject(project: ProjectOut) = Project(
        id = project.id.toString(),
        name = project.name,
    )

    private fun ApiResponse.successBody(): String = when (this) {
        is ApiResponse.Success -> body
        is ApiResponse.Failure -> throw ServiceException(error.message)
    }

    private companion object {
        val PROJECT_LIST_TYPE = object : TypeToken<List<ProjectOut>>() {}.type
    }
}

class ServiceException(message: String) : RuntimeException(message)
