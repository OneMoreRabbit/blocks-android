package dev.blocks.android.sync

import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.BackgroundSyncResult
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.newOperationIdentity
import java.io.IOException
import java.math.BigInteger

/** Durable API 0.3 cache/queue coordinator intended for background dispatch. */
class ProjectSyncRepository(
    private val service: ProjectService,
    private val legacyReplayer: LegacyQueueReplay,
    private val draftStore: DraftStore,
    private val syncStore: SyncStateStore,
) : ProjectWorkspace {
    private var activeTenantId: String? = null

    @Synchronized
    override fun activate(session: Session) {
        activeTenantId?.takeIf { it != session.tenantId }?.let { departedTenant ->
            activeTenantId = null
            purge(departedTenant)
        }
        activeTenantId = session.tenantId
        syncStore.loadSyncState(session.tenantId)
    }

    @Synchronized
    override fun snapshot(session: Session): BackgroundSyncResult = activeState(session).toResult()

    @Synchronized
    fun activeProjects(session: Session): List<ProjectSummary> {
        val state = activeState(session)
        return try {
            service.listProjects(session, ProjectLifecycle.ACTIVE).also { projects ->
                save(
                    session,
                    state.copy(
                        projectCache = state.projectCache.copy(
                            activeProjects = projects,
                            activeProjectsLoaded = true,
                        ),
                    ),
                )
            }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (error.isRetryable() && state.projectCache.activeProjectsLoaded) {
                state.projectCache.activeProjects
            } else {
                throw error
            }
        }
    }

    @Synchronized
    override fun archivedProjects(session: Session): List<ProjectSummary> {
        val state = activeState(session)
        return try {
            service.listProjects(session, ProjectLifecycle.ARCHIVED).also { projects ->
                save(
                    session,
                    state.copy(
                        projectCache = state.projectCache.copy(
                            archivedProjects = projects,
                            archivedProjectsLoaded = true,
                        ),
                    ),
                )
            }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (error.isRetryable() && state.projectCache.archivedProjectsLoaded) {
                state.projectCache.archivedProjects
            } else {
                throw error
            }
        }
    }

    @Synchronized
    override fun projectPage(session: Session, projectId: String): ProjectPage {
        val state = activeState(session)
        return try {
            service.projectPage(session, projectId).also { page ->
                save(session, state.withPage(page))
            }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            state.projectCache.pages.firstOrNull { it.project.id == projectId }
                ?.takeIf { error.isRetryable() }
                ?: throw error
        }
    }

    @Synchronized
    fun blocks(session: Session): List<PhysicalBlock> {
        val state = activeState(session)
        return try {
            service.blocks(session).also { blocks ->
                save(
                    session,
                    state.copy(projectCache = state.projectCache.copy(blocks = blocks, blocksLoaded = true)),
                )
            }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (error.isRetryable() && state.projectCache.blocksLoaded) state.projectCache.blocks else throw error
        }
    }

    override fun queueCreateProject(
        session: Session,
        name: String,
        operationId: String,
    ): BackgroundSyncResult {
        require(name.isNotBlank())
        val drafts = draftStore.load(session.tenantId)
        draftStore.save(session.tenantId, drafts.copy(projectName = name))
        return enqueue(
            session,
            ProjectMutation(
                operationId = operationId,
                type = ProjectMutationType.CREATE_PROJECT,
                targetId = operationId,
                idempotencyKey = operationId,
                projectName = name,
            ),
        )
    }

    override fun queueRenameProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        name: String,
        operationId: String,
    ): BackgroundSyncResult {
        require(name.isNotBlank())
        val drafts = draftStore.load(session.tenantId)
        draftStore.save(
            session.tenantId,
            drafts.copy(projectRenames = drafts.projectRenames + (projectId to name)),
        )
        return enqueue(
            session,
            ProjectMutation(
                operationId = operationId,
                type = ProjectMutationType.RENAME_PROJECT,
                targetId = projectId,
                ifMatch = rowVersion,
                projectName = name,
            ),
        )
    }

    override fun queueArchiveProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String,
    ) = enqueue(
        session,
        ProjectMutation(operationId, ProjectMutationType.ARCHIVE_PROJECT, projectId, ifMatch = rowVersion),
    )

    override fun queueReopenProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String,
    ) = enqueue(
        session,
        ProjectMutation(operationId, ProjectMutationType.REOPEN_PROJECT, projectId, ifMatch = rowVersion),
    )

    override fun queueSaveNotes(
        session: Session,
        projectId: String,
        rowVersion: Int,
        body: String,
        operationId: String,
    ): BackgroundSyncResult {
        val drafts = draftStore.load(session.tenantId)
        draftStore.save(session.tenantId, drafts.copy(projectNotes = drafts.projectNotes + (projectId to body)))
        return enqueue(
            session,
            ProjectMutation(
                operationId = operationId,
                type = ProjectMutationType.SAVE_NOTES,
                targetId = projectId,
                ifMatch = rowVersion,
                notesBody = body,
            ),
        )
    }

    override fun queueParking(
        session: Session,
        projectId: String,
        parking: ParkingDraft,
        supersedesId: String?,
        operationId: String,
    ): BackgroundSyncResult {
        require(parking.location.isNotBlank() && parking.nextAction.isNotBlank() && parking.mustNotForget.isNotBlank())
        val drafts = draftStore.load(session.tenantId)
        draftStore.save(
            session.tenantId,
            drafts.copy(projectParking = drafts.projectParking + (projectId to parking)),
        )
        return enqueue(
            session,
            ProjectMutation(
                operationId = operationId,
                type = ProjectMutationType.ADD_PARKING,
                targetId = projectId,
                projectId = projectId,
                idempotencyKey = operationId,
                parking = parking,
                supersedesId = supersedesId,
            ),
        )
    }

    // Block registration is intentionally absent: its one-time tag token requires a
    // dedicated NFC write/ack state machine and is unsafe in this generic result queue.
    fun queueBindBlock(
        session: Session,
        blockId: String,
        projectId: String,
        operationId: String = newOperationIdentity(),
    ) = enqueue(
        session,
        ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.BIND_BLOCK,
            targetId = blockId,
            projectId = projectId,
            idempotencyKey = operationId,
        ),
    )

    fun queueUnbindBlock(
        session: Session,
        blockId: String,
        operationId: String = newOperationIdentity(),
    ) = enqueue(
        session,
        ProjectMutation(operationId, ProjectMutationType.UNBIND_BLOCK, blockId),
    )

    @Synchronized
    override fun backgroundSync(session: Session): BackgroundSyncResult {
        checkActive(session)
        var state = syncStore.loadSyncState(session.tenantId)
        try {
            state = legacyReplayer.replay(session, state)
            state = drainProjectQueue(session, state)
            state = synchronizeChangesBestEffort(session, state)
            state = legacyReplayer.replay(session, state)
            state = refreshInvalidatedBestEffort(session, state)
            save(session, state)
            return state.toResult()
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            throw error
        }
    }

    @Synchronized
    override fun clearLocalState(session: Session) {
        if (activeTenantId == session.tenantId) activeTenantId = null
        purge(session.tenantId)
    }

    private fun enqueue(session: Session, command: ProjectMutation): BackgroundSyncResult {
        synchronized(this) {
            val state = activeState(session)
            require(command.operationId !in state.projectCache.retiredOperationIds) {
                "A retired project operation identity cannot be reused"
            }
            val existing = state.projectCache.commands.firstOrNull { it.operationId == command.operationId }
            if (existing == null) {
                save(
                    session,
                    state.copy(
                        projectCache = state.projectCache.copy(commands = state.projectCache.commands + command),
                    ),
                )
            } else {
                require(existing.normalized() == command.normalized()) {
                    "A project operation identity cannot describe two requests"
                }
            }
        }
        return backgroundSync(session)
    }

    private fun drainProjectQueue(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        while (true) {
            val command = state.projectCache.commands.firstOrNull() ?: return state
            if (command.status == ProjectMutationStatus.REQUIRES_RECONCILIATION) return state
            try {
                val outcome = sendProjectCommand(session, command)
                state = applyOutcome(state, outcome)
                state = retireProjectCommand(session, state, command)
            } catch (error: Exception) {
                if (error is ServiceException && error.status == 403) throw error
                if (error is ServiceException && error.status == 401) {
                    save(session, state)
                    throw error
                }
                if (error.isRetryable()) {
                    state = state.updateCommand(command.operationId) { it.copy(deliveryUncertain = true) }
                    save(session, state)
                    return state
                }
                state = refreshConflictTargetBestEffort(session, state, command)
                state = state.updateCommand(command.operationId) {
                    it.copy(
                        status = ProjectMutationStatus.REQUIRES_RECONCILIATION,
                        failureMessage = error.message,
                    )
                }
                save(session, state)
                return state
            }
        }
    }

    private fun sendProjectCommand(session: Session, command: ProjectMutation): ProjectOutcome = when (command.type) {
        ProjectMutationType.CREATE_PROJECT -> ProjectOutcome.Project(
            service.createProject(session, requireNotNull(command.projectName), requireNotNull(command.idempotencyKey)),
        )
        ProjectMutationType.RENAME_PROJECT -> ProjectOutcome.Project(
            service.renameProject(
                session,
                command.targetId,
                requireNotNull(command.ifMatch),
                requireNotNull(command.projectName),
            ),
        )
        ProjectMutationType.ARCHIVE_PROJECT -> ProjectOutcome.Project(
            service.archiveProject(session, command.targetId, requireNotNull(command.ifMatch)),
        )
        ProjectMutationType.REOPEN_PROJECT -> ProjectOutcome.Project(
            service.reopenProject(session, command.targetId, requireNotNull(command.ifMatch)),
        )
        ProjectMutationType.SAVE_NOTES -> ProjectOutcome.Notes(
            service.saveNotes(
                session,
                command.targetId,
                requireNotNull(command.ifMatch),
                requireNotNull(command.notesBody),
            ),
        )
        ProjectMutationType.ADD_PARKING -> ProjectOutcome.Parking(
            service.addParking(
                session,
                command.targetId,
                requireNotNull(command.parking),
                command.supersedesId,
                requireNotNull(command.idempotencyKey),
            ),
        )
        ProjectMutationType.BIND_BLOCK -> ProjectOutcome.Binding(
            service.bindBlock(
                session,
                command.targetId,
                requireNotNull(command.projectId),
                requireNotNull(command.idempotencyKey),
            ),
        )
        ProjectMutationType.UNBIND_BLOCK -> {
            service.unbindBlock(session, command.targetId)
            ProjectOutcome.Unbound(command.targetId)
        }
    }

    private fun synchronizeChangesBestEffort(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        return try {
            do {
                val requestedCursor = state.changeCursor
                val page = service.changes(session, requestedCursor, CHANGE_PAGE_SIZE)
                page.validateOrderAfter(requestedCursor)
                var updated = state
                page.events.forEach { event -> updated = observeEvent(updated, event) }
                val nextCursor = page.nextCursor ?: requestedCursor
                if (page.hasMore || page.events.isNotEmpty()) {
                    require(nextCursor != null && nextCursor != requestedCursor) {
                        "Change feed did not advance its cursor"
                    }
                }
                state = updated.copy(changeCursor = nextCursor)
                save(session, state)
            } while (page.hasMore)
            state
        } catch (error: Exception) {
            if (error is ServiceException && error.status == 403) throw error
            if (!error.isRetryable()) throw error
            syncStore.loadSyncState(session.tenantId)
        }
    }

    private fun observeEvent(state: TenantSyncState, event: ChangeEvent): TenantSyncState {
        var updated = legacyReplayer.observe(state, event)
        val projectId = event.payload["projectId"]?.toString()
        val cache = updated.projectCache
        val projectModelCache = when {
            event.eventType.startsWith("session.") -> cache
            event.eventType in PROJECT_LIST_EVENTS -> cache.copy(
                activeProjectsLoaded = false,
                archivedProjectsLoaded = false,
                stalePageIds = (cache.stalePageIds + listOfNotNull(projectId)).distinct(),
            )
            event.eventType in PROJECT_PAGE_EVENTS && projectId != null -> cache.copy(
                stalePageIds = (cache.stalePageIds + projectId).distinct(),
            )
            event.eventType in BLOCK_EVENTS -> cache.copy(blocksLoaded = false)
            else -> cache
        }
        if (projectModelCache != cache) updated = updated.copy(projectCache = projectModelCache)
        return updated
    }

    private fun refreshInvalidatedBestEffort(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.activeProjectsLoaded) it else it.copy(
                projectCache = it.projectCache.copy(
                    activeProjects = service.listProjects(session, ProjectLifecycle.ACTIVE),
                    activeProjectsLoaded = true,
                ),
            )
        }
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.archivedProjectsLoaded) it else it.copy(
                projectCache = it.projectCache.copy(
                    archivedProjects = service.listProjects(session, ProjectLifecycle.ARCHIVED),
                    archivedProjectsLoaded = true,
                ),
            )
        }
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.blocksLoaded) it else it.copy(
                projectCache = it.projectCache.copy(blocks = service.blocks(session), blocksLoaded = true),
            )
        }
        state.projectCache.stalePageIds.toList().forEach { projectId ->
            state = bestEffortRefresh(session, state) { it.withPage(service.projectPage(session, projectId)) }
        }
        save(session, state)
        return state
    }

    private fun bestEffortRefresh(
        session: Session,
        state: TenantSyncState,
        refresh: (TenantSyncState) -> TenantSyncState,
    ): TenantSyncState = try {
        refresh(state).also { save(session, it) }
    } catch (error: Exception) {
        if (error is ServiceException && error.status == 403) throw error
        if (!error.isRetryable()) throw error
        state
    }

    private fun refreshConflictTargetBestEffort(
        session: Session,
        state: TenantSyncState,
        command: ProjectMutation,
    ): TenantSyncState {
        val projectId = command.projectId ?: command.targetId
        if (command.type == ProjectMutationType.CREATE_PROJECT) {
            return state
        }
        return runCatching { state.withPage(service.projectPage(session, projectId)) }.getOrDefault(state)
    }

    private fun applyOutcome(state: TenantSyncState, outcome: ProjectOutcome): TenantSyncState = when (outcome) {
        is ProjectOutcome.Project -> state.withProject(outcome.value)
        is ProjectOutcome.Notes -> state.updatePage(outcome.value.projectId) { it.copy(notes = outcome.value) }
        is ProjectOutcome.Parking -> state.updatePage(outcome.value.projectId) {
            it.copy(newestParking = outcome.value)
        }
        is ProjectOutcome.Binding -> state.copy(
            projectCache = state.projectCache.copy(
                blocks = state.projectCache.blocks.map {
                    if (it.id == outcome.value.blockId) it.copy(boundProjectId = outcome.value.projectId) else it
                },
            ),
        )
        is ProjectOutcome.Unbound -> state.copy(
            projectCache = state.projectCache.copy(
                blocks = state.projectCache.blocks.map {
                    if (it.id == outcome.blockId) it.copy(boundProjectId = null) else it
                },
            ),
        )
    }

    private fun retireProjectCommand(
        session: Session,
        state: TenantSyncState,
        command: ProjectMutation,
    ): TenantSyncState {
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                commands = state.projectCache.commands.filterNot { it.operationId == command.operationId },
                retiredOperationIds = (state.projectCache.retiredOperationIds + command.operationId).distinct(),
            ),
        )
        save(session, updated)
        val drafts = draftStore.load(session.tenantId)
        val cleaned = when (command.type) {
            ProjectMutationType.CREATE_PROJECT -> drafts.copy(
                projectName = if (drafts.projectName == command.projectName) "" else drafts.projectName,
            )
            ProjectMutationType.RENAME_PROJECT -> drafts.copy(
                projectRenames = if (drafts.projectRenames[command.targetId] == command.projectName) {
                    drafts.projectRenames - command.targetId
                } else {
                    drafts.projectRenames
                },
            )
            ProjectMutationType.SAVE_NOTES -> drafts.copy(
                projectNotes = if (drafts.projectNotes[command.targetId] == command.notesBody) {
                    drafts.projectNotes - command.targetId
                } else {
                    drafts.projectNotes
                },
            )
            ProjectMutationType.ADD_PARKING -> drafts.copy(
                projectParking = if (drafts.projectParking[command.targetId] == command.parking) {
                    drafts.projectParking - command.targetId
                } else {
                    drafts.projectParking
                },
            )
            else -> drafts
        }
        if (cleaned != drafts) draftStore.save(session.tenantId, cleaned)
        return updated
    }

    private fun TenantSyncState.withProject(project: ProjectSummary): TenantSyncState {
        val cache = projectCache
        val active = cache.activeProjects.filterNot { it.id == project.id }
        val archived = cache.archivedProjects.filterNot { it.id == project.id }
        return copy(
            projectCache = cache.copy(
                activeProjects = if (project.lifecycle == ProjectLifecycle.ACTIVE) active + project else active,
                archivedProjects = if (project.lifecycle == ProjectLifecycle.ARCHIVED) archived + project else archived,
                pages = cache.pages.map {
                    if (it.project.id == project.id) it.copy(project = project) else it
                },
            ),
        )
    }

    private fun TenantSyncState.withPage(page: ProjectPage): TenantSyncState = copy(
        projectCache = projectCache.copy(
            pages = projectCache.pages.upsert(page) { it.project.id },
            stalePageIds = projectCache.stalePageIds - page.project.id,
        ),
    ).withProject(page.project)

    private fun TenantSyncState.updatePage(projectId: String, update: (ProjectPage) -> ProjectPage): TenantSyncState =
        copy(
            projectCache = projectCache.copy(
                pages = projectCache.pages.map { if (it.project.id == projectId) update(it) else it },
            ),
        )

    private fun TenantSyncState.updateCommand(
        operationId: String,
        update: (ProjectMutation) -> ProjectMutation,
    ) = copy(
        projectCache = projectCache.copy(
            commands = projectCache.commands.map { if (it.operationId == operationId) update(it) else it },
        ),
    )

    private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> = filterNot { id(it) == id(value) } + value

    private fun ProjectMutation.normalized() = copy(
        status = ProjectMutationStatus.PENDING,
        deliveryUncertain = false,
        failureMessage = null,
    )

    private fun ChangePage.validateOrderAfter(cursor: String?) {
        var previous = cursor?.let(::BigInteger) ?: BigInteger.valueOf(-1)
        events.forEach { event ->
            val current = runCatching { BigInteger(event.eventId) }
                .getOrElse { throw IllegalStateException("Change event id is not numeric") }
            require(current > previous) { "Change feed events are not strictly ordered" }
            previous = current
        }
        nextCursor?.let { require(BigInteger(it) >= previous) { "Change feed cursor precedes its events" } }
    }

    private fun TenantSyncState.toResult(): BackgroundSyncResult {
        val reconciliation = projectCache.commands.any {
            it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
        } || commands.any { it.status.name == ProjectMutationStatus.REQUIRES_RECONCILIATION.name }
        return BackgroundSyncResult(
            cache = projectCache,
            cursor = changeCursor,
            pendingLegacyOperations = commands.size,
            pendingProjectOperations = projectCache.commands.size,
            reconciliationRequired = reconciliation,
        )
    }

    private fun checkActive(session: Session) {
        check(activeTenantId == session.tenantId) { "No project state is active for this tenant" }
    }

    private fun activeState(session: Session): TenantSyncState {
        checkActive(session)
        return syncStore.loadSyncState(session.tenantId)
    }

    private fun save(session: Session, state: TenantSyncState) = syncStore.saveSyncState(session.tenantId, state)

    private fun purge(tenantId: String) {
        val syncFailure = runCatching { syncStore.clearSyncState(tenantId) }.exceptionOrNull()
        val draftFailure = runCatching { draftStore.clear(tenantId) }.exceptionOrNull()
        (syncFailure ?: draftFailure)?.let { failure ->
            listOfNotNull(syncFailure, draftFailure)
                .filterNot { it === failure }
                .forEach(failure::addSuppressed)
            throw IllegalStateException("Could not clear local tenant project data", failure)
        }
    }

    private fun Exception.handleMembershipRevocation(session: Session) {
        if (this !is ServiceException || status != 403) return
        if (activeTenantId == session.tenantId) activeTenantId = null
        val revoked = MembershipRevokedException(this)
        runCatching { purge(session.tenantId) }.exceptionOrNull()?.let(revoked::addSuppressed)
        throw revoked
    }

    private fun Exception.isRetryable() = this is IOException || this is ServiceException && status >= 500

    private sealed interface ProjectOutcome {
        data class Project(val value: ProjectSummary) : ProjectOutcome
        data class Notes(val value: ProjectNotes) : ProjectOutcome
        data class Parking(val value: ProjectParking) : ProjectOutcome
        data class Binding(val value: BlockBinding) : ProjectOutcome
        data class Unbound(val blockId: String) : ProjectOutcome
    }

    private companion object {
        const val CHANGE_PAGE_SIZE = 100
        val PROJECT_LIST_EVENTS = setOf(
            "project.created",
            "project.renamed",
            "project.archived",
            "project.reopened",
        )
        val PROJECT_PAGE_EVENTS = setOf("project.notes_saved", "project.parked")
        val BLOCK_EVENTS = setOf("block.bound", "block.unbound")
    }
}
