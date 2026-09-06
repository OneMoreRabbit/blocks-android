package dev.blocks.android.api

import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.Session
import org.json.JSONArray
import org.json.JSONObject

class ServiceBlocksRepository(private val client: BlocksTransport) : BlocksRepository {
    private val parking = mutableMapOf<String, ParkingRecord>()

    override fun login(identityToken: String): Session {
        val body = client.exchangeSession(identityToken).successBody()
        val json = JSONObject(body)
        return Session(
            tenantId = json.getString("personal_tenant_id"),
            identityToken = identityToken,
        )
    }

    override fun projects(session: Session): List<Project> =
        parseProjects(client.listProjects(session.identityToken, session.tenantId).successBody())

    override fun createProject(session: Session, name: String): Project {
        val request = JSONObject().put("name", name).toString()
        return parseProject(
            JSONObject(client.createProject(session.identityToken, session.tenantId, request).successBody()),
        )
    }

    override fun latestParking(session: Session, projectId: String): ParkingRecord? = parking[projectId]

    override fun park(session: Session, projectId: String, record: ParkingRecord) {
        parking[projectId] = record
    }

    private fun parseProjects(body: String): List<Project> {
        val array = JSONArray(body)
        return List(array.length()) { index -> parseProject(array.getJSONObject(index)) }
    }

    private fun parseProject(json: JSONObject) = Project(
        id = json.getString("id"),
        name = json.getString("name"),
    )

    private fun ApiResponse.successBody(): String = when (this) {
        is ApiResponse.Success -> body
        is ApiResponse.Failure -> throw ServiceException(error.message)
    }
}

class ServiceException(message: String) : RuntimeException(message)
