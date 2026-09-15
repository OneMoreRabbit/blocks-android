package dev.blocks.android.sync

import dev.blocks.android.api.IncompatibleServiceApiException
import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.MembershipRevokedException
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.BackgroundSyncResult
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectConflict
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ParkingHistoryPage
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.ensureProjectLinks
import dev.blocks.android.project.newOperationIdentity
import dev.blocks.android.tag.LocalBlockOperation
import dev.blocks.android.tag.LocalBlockOperationType
import dev.blocks.android.tag.LocalBlockRecord
import dev.blocks.android.tag.LocalBlockState
import dev.blocks.android.tag.LocalTagResolution
import dev.blocks.android.tag.DirectTagWritePlan
import dev.blocks.android.tag.OpaqueTagToken
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectKeyFactory
import dev.blocks.android.tag.ProjectLink
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagProgrammingFailure
import java.io.IOException
import java.math.BigInteger
import java.time.Instant

/**
 * Encrypted local-first workspace. UI-facing calls commit a complete local projection,
 * its draft identity, and the durable command before returning. Only [backgroundSync] performs
 * transport, so every ordinary action remains available with an empty cache and no API.
 */
class ProjectSyncRepository(
    private val service: ProjectService,
    private val legacyReplayer: LegacyQueueReplay,
    private val legacyDraftStore: DraftStore,
    private val syncStore: SyncStateStore,
    private val now: () -> String = { Instant.now().toString() },
    private val projectKeyFactory: ProjectKeyFactory = ProjectKeyFactory(),
) : ProjectWorkspace {
    @Volatile
    private var activeTenantId: String? = null
    private val backgroundLock = Any()
    private val backgroundSaveContext = ThreadLocal<BackgroundSaveContext?>()

    @Synchronized
    override fun activate(session: Session) {
        activeTenantId = session.tenantId
        migrateProjectDrafts(session)
        val state = activeState(session)
        val withProjectLinks = state.withProjectLinks()
        if (withProjectLinks != state) save(session, withProjectLinks)
    }

    @Synchronized
    override fun deactivate(session: Session) {
        if (activeTenantId == session.tenantId) activeTenantId = null
    }

    @Synchronized
    override fun snapshot(session: Session): BackgroundSyncResult = activeState(session).toResult()

    @Synchronized
    override fun projectDrafts(session: Session): FormDrafts = activeState(session).projectDrafts

    @Synchronized
    override fun updateProjectDrafts(
        session: Session,
        update: (FormDrafts) -> FormDrafts,
    ): FormDrafts {
        val state = activeState(session)
        val drafts = update(state.projectDrafts)
        save(session, state.copy(projectDrafts = drafts))
        return drafts
    }

    @Synchronized
    override fun uiPreferences(session: Session): ProjectUiPreferences = activeState(session).projectUiPreferences

    @Synchronized
    override fun updateUiPreferences(
        session: Session,
        update: (ProjectUiPreferences) -> ProjectUiPreferences,
    ): ProjectUiPreferences {
        val state = activeState(session)
        val preferences = update(state.projectUiPreferences)
        save(session, state.copy(projectUiPreferences = preferences))
        return preferences
    }

    /** Retained for tests and non-presentation callers; reads are local and never gate rendering. */
    @Synchronized
    fun activeProjects(session: Session): List<ProjectSummary> = activeState(session).projectCache.activeProjects

    @Synchronized
    override fun archivedProjects(session: Session): List<ProjectSummary> =
        activeState(session).projectCache.archivedProjects

    @Synchronized
    override fun projectPage(session: Session, projectId: String): ProjectPage {
        val state = activeState(session)
        val resolvedId = state.projectCache.projectIdMappings
            .firstOrNull { it.provisionalId == projectId }?.serverId ?: projectId
        return state.projectCache.pages.firstOrNull { it.project.id == resolvedId }
            ?: throw IllegalStateException("This project is not available in saved local data.")
    }

    @Synchronized
    override fun projectCover(session: Session, projectId: String): ProjectCover? {
        val state = activeState(session)
        val resolvedId = state.projectCache.resolveProjectId(projectId)
        return state.projectCache.projectCovers.firstOrNull { it.projectId == resolvedId }
    }

    @Synchronized
    override fun saveProjectCover(session: Session, cover: ProjectCover): BackgroundSyncResult {
        val state = activeState(session)
        val resolvedId = state.projectCache.resolveProjectId(cover.projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedId }) {
            "The selected project is not available in saved local data"
        }
        require(cover.displayWidth > 0 && cover.displayHeight > 0) { "The display cover is invalid" }
        require(cover.thumbnailWidth > 0 && cover.thumbnailHeight > 0) { "The cover thumbnail is invalid" }
        require(cover.displayJpegBase64.isNotBlank() && cover.thumbnailJpegBase64.isNotBlank()) {
            "The project cover has no display content"
        }
        val committed = cover.copy(projectId = resolvedId)
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                projectCovers = state.projectCache.projectCovers
                    .filterNot { it.projectId == resolvedId } + committed,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun removeProjectCover(session: Session, projectId: String): BackgroundSyncResult {
        val state = activeState(session)
        val resolvedId = state.projectCache.resolveProjectId(projectId)
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                projectCovers = state.projectCache.projectCovers.filterNot { it.projectId == resolvedId },
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun parkingHistory(
        session: Session,
        projectId: String,
        offset: Int,
        limit: Int,
    ): ParkingHistoryPage {
        require(offset >= 0) { "Parking-history offset cannot be negative" }
        require(limit > 0) { "Parking-history limit must be positive" }
        val state = activeState(session)
        val resolvedId = state.projectCache.projectIdMappings
            .firstOrNull { it.provisionalId == projectId }?.serverId ?: projectId
        val records = state.projectCache.parkingRecords.newestFirstFor(resolvedId)
        val visible = records.drop(offset).take(limit)
        return ParkingHistoryPage(visible, offset + visible.size < records.size)
    }

    @Synchronized
    override fun localBlocks(session: Session): List<LocalBlockRecord> = activeState(session).projectCache.localBlocks

    @Synchronized
    override fun projectLink(session: Session, projectId: String): ProjectLink {
        val state = activeState(session)
        val resolvedProjectId = state.projectCache.resolveProjectId(projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedProjectId }) {
            "The selected project is not available in saved local data"
        }
        val identity = requireNotNull(
            state.projectCache.projectLinks.firstOrNull { it.projectId == resolvedProjectId },
        ) { "The selected project has no local navigation key" }
        return ProjectLink.create(identity.key)
    }

    @Synchronized
    override fun resolveProjectLinkLocally(session: Session, key: ProjectKey): String? {
        val state = activeState(session)
        val projectId = state.projectCache.projectLinks.firstOrNull { it.key == key }?.projectId ?: return null
        return projectId.takeIf { candidate -> state.projectCache.pages.any { it.project.id == candidate } }
    }

    @Synchronized
    override fun prepareDirectTagWrite(
        session: Session,
        projectId: String,
        label: String,
        clientBlockId: String,
        token: OpaqueTagToken,
        registrationOperationId: String,
        bindingOperationId: String,
    ): DirectTagWritePlan {
        require(label.isNotBlank()) { "The project name cannot be blank" }
        val state = activeState(session)
        val resolvedProjectId = state.projectCache.resolveProjectId(projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedProjectId }) {
            "The selected project is not available in saved local data"
        }
        require(state.projectCache.localBlocks.none { it.clientBlockId == clientBlockId }) {
            "The local block identity already exists"
        }
        require(state.projectCache.localBlocks.none { it.token == token }) {
            "The local tag token already exists"
        }
        return DirectTagWritePlan(
            LocalBlockRecord(
                clientBlockId = clientBlockId,
                token = token,
                label = label.take(MAX_BLOCK_LABEL),
                state = LocalBlockState.AWAITING_WRITE,
                intendedProjectId = resolvedProjectId,
                registrationOperationId = registrationOperationId,
                initialBindingOperationId = bindingOperationId,
                createdAt = now(),
            ),
        )
    }

    @Synchronized
    override fun commitVerifiedDirectTagWrite(
        session: Session,
        plan: DirectTagWritePlan,
        replacedToken: OpaqueTagToken?,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val block = plan.block
        require(block.state == LocalBlockState.AWAITING_WRITE) { "The direct tag plan is invalid" }
        require(state.projectCache.localBlocks.none { it.clientBlockId == block.clientBlockId }) {
            "The direct tag plan was already committed"
        }
        require(state.projectCache.localBlocks.none { it.token == block.token }) {
            "The verified tag token already exists"
        }
        val projectId = state.projectCache.resolveProjectId(requireNotNull(block.intendedProjectId))
        require(state.projectCache.pages.any { it.project.id == projectId }) {
            "The selected project is not available in saved local data"
        }

        var blocks = state.projectCache.localBlocks
        var operations = state.projectCache.localBlockOperations
        val replaced = replacedToken?.let { token -> blocks.firstOrNull { it.token == token } }
        if (replaced != null) {
            if (replaced.state in PENDING_TAG_STATES && operations.none { it.clientBlockId == replaced.clientBlockId }) {
                blocks = blocks.filterNot { it.clientBlockId == replaced.clientBlockId }
            } else if (replaced.state == LocalBlockState.ACTIVE && replaced.boundProjectId != null) {
                val unbindId = newOperationIdentity()
                operations = operations + LocalBlockOperation(
                    operationId = unbindId,
                    type = LocalBlockOperationType.UNBIND_BLOCK,
                    clientBlockId = replaced.clientBlockId,
                    dependsOnOperationId = operations.lastOrNull {
                        it.clientBlockId == replaced.clientBlockId
                    }?.operationId,
                    createdAt = now(),
                )
                blocks = blocks.replaceBlock(
                    replaced.copy(
                        state = LocalBlockState.UNBOUND,
                        intendedProjectId = null,
                        boundProjectId = null,
                    ),
                )
            }
        }

        val registrationId = requireNotNull(block.registrationOperationId)
        val bindingId = requireNotNull(block.initialBindingOperationId)
        val verifiedAt = now()
        val active = block.copy(
            state = LocalBlockState.ACTIVE,
            boundProjectId = projectId,
            verifiedAt = verifiedAt,
            activationMedium = TagActivationMedium.NFC,
        )
        operations = operations + LocalBlockOperation(
            operationId = registrationId,
            type = LocalBlockOperationType.REGISTER_BLOCK,
            clientBlockId = block.clientBlockId,
            token = block.token,
            label = block.label,
            createdAt = block.createdAt,
        ) + LocalBlockOperation(
            operationId = bindingId,
            type = LocalBlockOperationType.BIND_BLOCK,
            clientBlockId = block.clientBlockId,
            projectId = projectId,
            dependsOnOperationId = registrationId,
            createdAt = verifiedAt,
        )
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = blocks + active,
                localBlockOperations = operations,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun commitVerifiedDirectTagClear(
        session: Session,
        erasedToken: OpaqueTagToken?,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val block = erasedToken?.let { token -> state.projectCache.localBlocks.firstOrNull { it.token == token } }
            ?: return state.toResult()
        if (block.state in PENDING_TAG_STATES &&
            state.projectCache.localBlockOperations.none { it.clientBlockId == block.clientBlockId }
        ) {
            val updated = state.copy(
                projectCache = state.projectCache.copy(
                    localBlocks = state.projectCache.localBlocks.filterNot {
                        it.clientBlockId == block.clientBlockId
                    },
                ),
            )
            save(session, updated)
            return updated.toResult()
        }
        if (block.state != LocalBlockState.ACTIVE || block.boundProjectId == null) return state.toResult()
        val operation = LocalBlockOperation(
            operationId = operationId,
            type = LocalBlockOperationType.UNBIND_BLOCK,
            clientBlockId = block.clientBlockId,
            dependsOnOperationId = state.projectCache.localBlockOperations.lastOrNull {
                it.clientBlockId == block.clientBlockId
            }?.operationId,
            createdAt = now(),
        )
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = state.projectCache.localBlocks.replaceBlock(
                    block.copy(state = LocalBlockState.UNBOUND, intendedProjectId = null, boundProjectId = null),
                ),
                localBlockOperations = state.projectCache.localBlockOperations + operation,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun beginTagProgramming(
        session: Session,
        projectId: String,
        label: String,
        clientBlockId: String,
        token: OpaqueTagToken,
        registrationOperationId: String,
        bindingOperationId: String,
    ): LocalBlockRecord {
        require(label.isNotBlank()) { "Enter a block label" }
        require(label.length <= MAX_BLOCK_LABEL) { "Block labels can contain at most 200 characters" }
        val state = activeState(session)
        val resolvedProjectId = state.projectCache.resolveProjectId(projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedProjectId }) {
            "The selected project is not available in saved local data"
        }
        require(state.projectCache.localBlocks.none { it.clientBlockId == clientBlockId }) {
            "The local block identity already exists"
        }
        require(state.projectCache.localBlocks.none { it.token == token }) {
            "The local tag token already exists"
        }
        val block = LocalBlockRecord(
            clientBlockId = clientBlockId,
            token = token,
            label = label,
            state = LocalBlockState.AWAITING_WRITE,
            intendedProjectId = resolvedProjectId,
            registrationOperationId = registrationOperationId,
            initialBindingOperationId = bindingOperationId,
            createdAt = now(),
        )
        save(
            session,
            state.copy(
                projectCache = state.projectCache.copy(localBlocks = state.projectCache.localBlocks + block),
            ),
        )
        return block
    }

    @Synchronized
    override fun markTagWriteAttempt(session: Session, clientBlockId: String): LocalBlockRecord =
        updateLocalBlock(session, clientBlockId) { block ->
            require(block.state in PENDING_TAG_STATES) { "This local block is not awaiting programming" }
            block.copy(state = LocalBlockState.WRITE_UNCERTAIN, failure = TagProgrammingFailure.IO_UNCERTAIN)
        }

    @Synchronized
    override fun recordTagProgrammingFailure(
        session: Session,
        clientBlockId: String,
        failure: TagProgrammingFailure,
    ): LocalBlockRecord = updateLocalBlock(session, clientBlockId) { block ->
        require(block.state in PENDING_TAG_STATES) { "This local block is not awaiting programming" }
        block.copy(
            state = if (failure in UNCERTAIN_TAG_FAILURES) {
                LocalBlockState.WRITE_UNCERTAIN
            } else {
                LocalBlockState.AWAITING_WRITE
            },
            failure = failure,
        )
    }

    @Synchronized
    override fun discardPendingTagProgramming(
        session: Session,
        clientBlockId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val block = state.projectCache.localBlocks.firstOrNull { it.clientBlockId == clientBlockId }
            ?: return state.toResult()
        require(block.state in PENDING_TAG_STATES) { "Only pending tag programming can be discarded" }
        require(state.projectCache.localBlockOperations.none { it.clientBlockId == clientBlockId }) {
            "A local block with synchronization intent cannot be discarded"
        }
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = state.projectCache.localBlocks.filterNot { it.clientBlockId == clientBlockId },
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun authorizeTagReplacement(
        session: Session,
        clientBlockId: String,
        existingToken: OpaqueTagToken,
    ): LocalBlockRecord = updateLocalBlock(session, clientBlockId) { block ->
        require(block.state in PENDING_TAG_STATES) { "This local block is not awaiting programming" }
        block.copy(replacementTokenFingerprint = existingToken.fingerprint(), failure = null)
    }

    @Synchronized
    override fun completeTagProgramming(
        session: Session,
        clientBlockId: String,
        verifiedToken: OpaqueTagToken,
        medium: TagActivationMedium,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val block = state.projectCache.localBlocks.firstOrNull { it.clientBlockId == clientBlockId }
            ?: throw IllegalArgumentException("The pending local block does not exist")
        require(block.token == verifiedToken) { "The verified tag does not match the pending local block" }
        if (block.state == LocalBlockState.ACTIVE) return state.toResult()
        require(block.state in PENDING_TAG_STATES) { "This local block is not awaiting programming" }
        val projectId = requireNotNull(block.intendedProjectId) { "The pending block has no intended project" }
        val registrationId = requireNotNull(block.registrationOperationId) {
            "The pending block has no registration identity"
        }
        val bindingId = requireNotNull(block.initialBindingOperationId) {
            "The pending block has no binding identity"
        }
        val registration = LocalBlockOperation(
            operationId = registrationId,
            type = LocalBlockOperationType.REGISTER_BLOCK,
            clientBlockId = clientBlockId,
            token = block.token,
            label = block.label,
            createdAt = block.createdAt,
        )
        val binding = LocalBlockOperation(
            operationId = bindingId,
            type = LocalBlockOperationType.BIND_BLOCK,
            clientBlockId = clientBlockId,
            projectId = projectId,
            dependsOnOperationId = registrationId,
            createdAt = now(),
        )
        val active = block.copy(
            state = LocalBlockState.ACTIVE,
            boundProjectId = projectId,
            verifiedAt = now(),
            activationMedium = medium,
            replacementTokenFingerprint = null,
            failure = null,
        )
        val operations = (state.projectCache.localBlockOperations + registration + binding)
            .distinctBy { it.operationId }
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = state.projectCache.localBlocks.replaceBlock(active),
                localBlockOperations = operations,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun resolveTagLocally(session: Session, token: OpaqueTagToken): LocalTagResolution {
        val block = activeState(session).projectCache.localBlocks.firstOrNull { it.token == token }
            ?: return LocalTagResolution.Unknown(token)
        return block.toResolution()
    }

    override fun resolveTagThroughService(session: Session, token: OpaqueTagToken): LocalTagResolution {
        synchronized(this) {
            val local = resolveTagLocally(session, token)
            if (local !is LocalTagResolution.Unknown) return local
            require(session.identityToken.isNotBlank()) { "Reconnect before looking up an unknown tag" }
        }
        val (lookup, page) = try {
            val found = service.lookupBlock(session, token.encoded)
            found to found.projectId?.let { service.projectPage(session, it) }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            throw error
        }
        return synchronized(this) {
            val current = activeState(session)
            current.projectCache.localBlocks.firstOrNull { it.token == token }?.toResolution() ?: run {
                val imported = LocalBlockRecord(
                    clientBlockId = newOperationIdentity(),
                    token = token,
                    label = lookup.label,
                    state = if (lookup.projectId == null) LocalBlockState.UNBOUND else LocalBlockState.ACTIVE,
                    intendedProjectId = lookup.projectId,
                    boundProjectId = lookup.projectId,
                    createdAt = now(),
                    verifiedAt = now(),
                    serverBlockId = lookup.blockId,
                )
                var updated = current.copy(
                    projectCache = current.projectCache.copy(
                        localBlocks = current.projectCache.localBlocks + imported,
                    ),
                )
                page?.let { updated = updated.withPage(it) }
                save(session, updated)
                imported.toResolution()
            }
        }
    }

    @Synchronized
    override fun assignLocalBlock(
        session: Session,
        clientBlockId: String,
        projectId: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val resolvedProjectId = state.projectCache.resolveProjectId(projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedProjectId }) {
            "The selected project is not available in saved local data"
        }
        val block = state.projectCache.localBlocks.firstOrNull { it.clientBlockId == clientBlockId }
            ?: throw IllegalArgumentException("The local block does not exist")
        require(block.state in setOf(LocalBlockState.ACTIVE, LocalBlockState.UNBOUND)) {
            "The local block has not been programmed"
        }
        if (block.boundProjectId == resolvedProjectId && block.state == LocalBlockState.ACTIVE) return state.toResult()
        val dependency = state.projectCache.localBlockOperations.lastOrNull {
            it.clientBlockId == clientBlockId
        }?.operationId
        val operation = LocalBlockOperation(
            operationId = operationId,
            type = LocalBlockOperationType.BIND_BLOCK,
            clientBlockId = clientBlockId,
            projectId = resolvedProjectId,
            dependsOnOperationId = dependency,
            createdAt = now(),
        )
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = state.projectCache.localBlocks.replaceBlock(
                    block.copy(
                        state = LocalBlockState.ACTIVE,
                        intendedProjectId = resolvedProjectId,
                        boundProjectId = resolvedProjectId,
                        failure = null,
                    ),
                ),
                localBlockOperations = state.projectCache.localBlockOperations + operation,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun unbindLocalBlock(
        session: Session,
        clientBlockId: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val block = state.projectCache.localBlocks.firstOrNull { it.clientBlockId == clientBlockId }
            ?: throw IllegalArgumentException("The local block does not exist")
        require(block.state == LocalBlockState.ACTIVE && block.boundProjectId != null) {
            "The local block is not assigned"
        }
        val dependency = state.projectCache.localBlockOperations.lastOrNull {
            it.clientBlockId == clientBlockId
        }?.operationId
        val operation = LocalBlockOperation(
            operationId = operationId,
            type = LocalBlockOperationType.UNBIND_BLOCK,
            clientBlockId = clientBlockId,
            dependsOnOperationId = dependency,
            createdAt = now(),
        )
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                localBlocks = state.projectCache.localBlocks.replaceBlock(
                    block.copy(state = LocalBlockState.UNBOUND, intendedProjectId = null, boundProjectId = null),
                ),
                localBlockOperations = state.projectCache.localBlockOperations + operation,
            ),
        )
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    fun blocks(session: Session): List<PhysicalBlock> = activeState(session).projectCache.blocks

    @Synchronized
    override fun queueCreateProject(
        session: Session,
        name: String,
        operationId: String,
        clientId: String,
    ): BackgroundSyncResult {
        require(name.isNotBlank())
        val createdAt = now()
        val project = ProjectSummary(
            id = clientId,
            tenantId = session.tenantId,
            name = name,
            lifecycle = ProjectLifecycle.ACTIVE,
            rowVersion = 0,
            createdAt = createdAt,
            updatedAt = createdAt,
            clientId = clientId,
        )
        val page = ProjectPage(
            project = project,
            notes = ProjectNotes(clientId, "", 0, createdAt),
            newestParking = null,
        )
        val command = ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.CREATE_PROJECT,
            targetId = clientId,
            idempotencyKey = operationId,
            clientResourceId = clientId,
            projectName = name,
            localCreatedAt = createdAt,
        )
        return commitLocal(session, command) { state ->
            state.copy(
                projectCache = state.projectCache.copy(
                    activeProjects = state.projectCache.activeProjects.upsert(project) { it.id },
                    activeProjectsLoaded = true,
                    pages = state.projectCache.pages.upsert(page) { it.project.id },
                    provisionalProjectIds = (state.projectCache.provisionalProjectIds + clientId).distinct(),
                    parkingLoadedProjectIds = (state.projectCache.parkingLoadedProjectIds + clientId).distinct(),
                ),
                projectDrafts = state.projectDrafts.cleanAfter(command),
            )
        }
    }

    @Synchronized
    override fun queueRenameProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        name: String,
        operationId: String,
    ): BackgroundSyncResult {
        require(name.isNotBlank())
        val state = activeState(session)
        val dependency = state.projectCache.latestProjectDependency(projectId)
        val command = ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.RENAME_PROJECT,
            targetId = projectId,
            ifMatch = rowVersion.takeIf { dependency == null },
            projectName = name,
            dependsOnOperationId = dependency,
            localCreatedAt = now(),
        )
        return commitLocal(session, command) { current ->
            current.updateProject(projectId) {
                it.copy(name = name, updatedAt = now())
            }.copy(
                projectDrafts = current.projectDrafts.cleanAfter(command),
            ).withProjectedLists()
        }
    }

    @Synchronized
    override fun queueArchiveProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String,
    ): BackgroundSyncResult = queueLifecycle(
        session,
        projectId,
        rowVersion,
        operationId,
        ProjectMutationType.ARCHIVE_PROJECT,
        ProjectLifecycle.ARCHIVED,
    )

    @Synchronized
    override fun queueReopenProject(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String,
    ): BackgroundSyncResult = queueLifecycle(
        session,
        projectId,
        rowVersion,
        operationId,
        ProjectMutationType.REOPEN_PROJECT,
        ProjectLifecycle.ACTIVE,
    )

    @Synchronized
    override fun queueSaveNotes(
        session: Session,
        projectId: String,
        rowVersion: Int,
        body: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val dependency = state.projectCache.latestProjectDependency(projectId)
        val command = ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.SAVE_NOTES,
            targetId = projectId,
            ifMatch = rowVersion.takeIf {
                dependency == null && projectId !in state.projectCache.projectsAwaitingNotesBaseline
            },
            notesBody = body,
            dependsOnOperationId = dependency,
            localCreatedAt = now(),
        )
        return commitLocal(session, command) { current ->
            current.updatePage(projectId) { page ->
                page.copy(notes = page.notes.copy(body = body, updatedAt = now()))
            }.copy(
                projectDrafts = current.projectDrafts.cleanAfter(command),
            )
        }
    }

    @Synchronized
    override fun queueParking(
        session: Session,
        projectId: String,
        parking: ParkingDraft,
        supersedesId: String?,
        operationId: String,
        clientId: String,
    ): BackgroundSyncResult {
        require(parking.location.isNotBlank() && parking.nextAction.isNotBlank() && parking.mustNotForget.isNotBlank())
        val state = activeState(session)
        val dependency = state.projectCache.latestProjectDependency(projectId)
        val createdAt = now()
        val localParking = ProjectParking(
            id = clientId,
            projectId = projectId,
            location = parking.location,
            nextAction = parking.nextAction,
            mustNotForget = parking.mustNotForget,
            authoredByUserId = "local",
            createdAt = createdAt,
            occurredAt = createdAt,
            clientId = clientId,
            supersedesId = supersedesId,
        )
        val command = ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.ADD_PARKING,
            targetId = projectId,
            projectId = projectId,
            idempotencyKey = operationId,
            clientResourceId = clientId,
            parking = parking,
            supersedesId = supersedesId,
            dependsOnOperationId = dependency,
            localCreatedAt = createdAt,
        )
        return commitLocal(session, command) { current ->
            current.withParkingRecord(localParking).copy(
                projectDrafts = current.projectDrafts.cleanAfter(command),
            )
        }
    }

    @Synchronized
    override fun discardConflict(session: Session, operationId: String): BackgroundSyncResult {
        val state = activeState(session)
        val command = state.projectCache.commands.firstOrNull { it.operationId == operationId }
            ?: throw IllegalArgumentException("The saved change is no longer pending")
        require(command.status == ProjectMutationStatus.REQUIRES_RECONCILIATION)
        val authoritative = command.authoritativePage?.let { state.withPage(it) } ?: state
        val updated = retireProjectCommand(authoritative, command).reapplyPendingProjection()
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun retryConflict(
        session: Session,
        operationId: String,
        newOperationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val command = state.projectCache.commands.firstOrNull { it.operationId == operationId }
            ?: throw IllegalArgumentException("The saved change is no longer pending")
        require(command.status == ProjectMutationStatus.REQUIRES_RECONCILIATION)
        require(newOperationId != operationId && newOperationId !in state.projectCache.retiredOperationIds)
        val authoritative = requireNotNull(command.authoritativePage) {
            "The authoritative project version must be loaded before retry"
        }
        val replacement = command.copy(
            operationId = newOperationId,
            idempotencyKey = command.idempotencyKey?.let { newOperationId },
            ifMatch = when (command.type) {
                ProjectMutationType.SAVE_NOTES -> authoritative.notes.rowVersion
                ProjectMutationType.RENAME_PROJECT,
                ProjectMutationType.ARCHIVE_PROJECT,
                ProjectMutationType.REOPEN_PROJECT,
                -> authoritative.project.rowVersion
                else -> command.ifMatch
            },
            dependsOnOperationId = null,
            status = ProjectMutationStatus.PENDING,
            deliveryUncertain = false,
            failureMessage = null,
            authoritativeStateConfirmed = false,
            authoritativePage = null,
        )
        val drafts = state.projectDrafts.copy(
            pendingMutations = state.projectDrafts.pendingMutations.mapValues { (_, mutation) ->
                if (mutation.idempotencyKey == operationId) mutation.copy(idempotencyKey = newOperationId) else mutation
            },
        )
        val updated = state.copy(
            projectCache = state.projectCache.copy(
                commands = state.projectCache.commands.map { if (it.operationId == operationId) replacement else it },
                retiredOperationIds = (state.projectCache.retiredOperationIds + operationId).distinct(),
            ),
            projectDrafts = drafts,
        ).withPage(authoritative).reapplyPendingProjection()
        save(session, updated)
        return updated.toResult()
    }

    @Synchronized
    override fun discardPendingOperation(session: Session, operationId: String): BackgroundSyncResult {
        val state = activeState(session)
        val command = state.projectCache.commands.firstOrNull { it.operationId == operationId }
            ?: return state.toResult()
        val updated = retireProjectCommand(state, command).reapplyPendingProjection()
        save(session, updated)
        return updated.toResult()
    }

    // API 0.4 registration remains direct-only and is not used by the local-first tag operation queue.
    @Synchronized
    fun queueBindBlock(
        session: Session,
        blockId: String,
        projectId: String,
        operationId: String = newOperationIdentity(),
    ) = commitLocal(
        session,
        ProjectMutation(
            operationId = operationId,
            type = ProjectMutationType.BIND_BLOCK,
            targetId = blockId,
            projectId = projectId,
            idempotencyKey = operationId,
            localCreatedAt = now(),
        ),
    ) { it }

    @Synchronized
    fun queueUnbindBlock(
        session: Session,
        blockId: String,
        operationId: String = newOperationIdentity(),
    ) = commitLocal(
        session,
        ProjectMutation(
            operationId,
            ProjectMutationType.UNBIND_BLOCK,
            blockId,
            localCreatedAt = now(),
        ),
    ) { it }

    override fun backgroundSync(session: Session): BackgroundSyncResult = synchronized(backgroundLock) {
        var state = synchronized(this) { activeState(session) }
        backgroundSaveContext.set(BackgroundSaveContext(state))
        try {
            state = legacyReplayer.replay(session, state)
            if (state.projectCache.commands.isNotEmpty()) {
                state = verifyApi04BeforeMutation(session, state) ?: return state.toResult()
            }
            state = drainProjectQueue(session, state)
            state = synchronizeChangesBestEffort(session, state)
            state = legacyReplayer.replay(session, state)
            state = refreshInvalidatedBestEffort(session, state)
            state = save(session, state)
            return state.toResult()
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (error.isDeferredSync()) {
                return synchronized(this) { activeState(session) }.toResult()
            }
            throw error
        } finally {
            backgroundSaveContext.remove()
        }
    }

    /** A read-only 0.4 shape probe prevents a legacy 18-route Service from receiving mutations. */
    private fun verifyApi04BeforeMutation(
        session: Session,
        state: TenantSyncState,
    ): TenantSyncState? = try {
        val updated = state.mergeRemoteProjects(
            service.listProjects(session, ProjectLifecycle.ACTIVE),
            ProjectLifecycle.ACTIVE,
        )
        save(session, updated)
    } catch (error: Exception) {
        error.handleMembershipRevocation(session)
        if (!error.isDeferredSync()) throw error
        null
    }

    @Synchronized
    override fun clearLocalState(session: Session) {
        if (activeTenantId == session.tenantId) activeTenantId = null
        purge(session.tenantId)
    }

    private fun queueLifecycle(
        session: Session,
        projectId: String,
        rowVersion: Int,
        operationId: String,
        type: ProjectMutationType,
        lifecycle: ProjectLifecycle,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val dependency = state.projectCache.latestProjectDependency(projectId)
        return commitLocal(
            session,
            ProjectMutation(
                operationId = operationId,
                type = type,
                targetId = projectId,
                ifMatch = rowVersion.takeIf { dependency == null },
                dependsOnOperationId = dependency,
                localCreatedAt = now(),
            ),
        ) { current ->
            current.updateProject(projectId) { project ->
                project.copy(
                    lifecycle = lifecycle,
                    updatedAt = now(),
                    archivedAt = if (lifecycle == ProjectLifecycle.ARCHIVED) now() else null,
                )
            }.withProjectedLists()
        }
    }

    private fun commitLocal(
        session: Session,
        command: ProjectMutation,
        projection: (TenantSyncState) -> TenantSyncState,
    ): BackgroundSyncResult {
        val state = activeState(session)
        require(command.operationId !in state.projectCache.retiredOperationIds) {
            "A retired project operation identity cannot be reused"
        }
        val existing = state.projectCache.commands.firstOrNull { it.operationId == command.operationId }
        if (existing != null) {
            require(existing.normalized() == command.normalized()) {
                "A project operation identity cannot describe two requests"
            }
            return state.toResult()
        }
        val projected = projection(state)
        val updated = projected.copy(
            projectCache = projected.projectCache.copy(commands = projected.projectCache.commands + command),
        )
        save(session, updated)
        return updated.toResult()
    }

    private fun drainProjectQueue(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        while (true) {
            var command = state.projectCache.commands.firstOrNull() ?: return state
            if (command.dependsOnOperationId != null && state.projectCache.commands.any {
                    it.operationId == command.dependsOnOperationId
                }
            ) return state

            if (command.deliveryUncertain || command.status == ProjectMutationStatus.REQUIRES_RECONCILIATION) {
                val refreshed = refreshConflictTargetBestEffort(session, state, command)
                state = refreshed.state.updateCommand(command.operationId) {
                    it.copy(
                        authoritativeStateConfirmed = refreshed.confirmed,
                        authoritativePage = refreshed.page,
                    )
                }
                state = save(session, state)
                command = state.projectCache.commands.first { it.operationId == command.operationId }
                if (command.isSatisfiedByAuthoritativeState()) {
                    state = retireProjectCommand(state, command).reapplyPendingProjection()
                    state = save(session, state)
                    continue
                }
            }
            if (command.status == ProjectMutationStatus.REQUIRES_RECONCILIATION) return state

            val prepared = prepareCommand(session, state, command) ?: return syncStore.loadSyncState(session.tenantId)
            state = prepared.first
            command = prepared.second
            try {
                val outcome = sendProjectCommand(session, command)
                state = applyOutcome(state, command, outcome)
                state = retireProjectCommand(state, command).reapplyPendingProjection()
                state = save(session, state)
            } catch (error: Exception) {
                if (error is ServiceException && error.status in setOf(401, 403)) throw error
                if (error.isRecognizedOlderApi(command)) return state
                if (error.isDeferredSync()) {
                    state = state.updateCommand(command.operationId) { it.copy(deliveryUncertain = true) }
                    state = save(session, state)
                    return state
                }
                val refreshed = refreshConflictTargetBestEffort(session, state, command)
                state = refreshed.state.updateCommand(command.operationId) {
                    it.copy(
                        status = ProjectMutationStatus.REQUIRES_RECONCILIATION,
                        failureMessage = error.message,
                        authoritativeStateConfirmed = refreshed.confirmed,
                        authoritativePage = refreshed.page,
                    )
                }
                state = save(session, state)
                return state
            }
        }
    }

    private fun prepareCommand(
        session: Session,
        initial: TenantSyncState,
        original: ProjectMutation,
    ): Pair<TenantSyncState, ProjectMutation>? {
        if (original.ifMatch != null || original.type !in VERSIONED_TYPES) return initial to original
        var state = initial
        val projectId = original.projectIdOrTarget()
        if (original.type == ProjectMutationType.SAVE_NOTES &&
            projectId in state.projectCache.projectsAwaitingNotesBaseline
        ) {
            state = try {
                val authoritative = service.projectPage(session, projectId)
                val merged = state.withPage(authoritative)
                merged.copy(
                    projectCache = merged.projectCache.copy(
                        projectsAwaitingNotesBaseline = merged.projectCache.projectsAwaitingNotesBaseline - projectId,
                    ),
                ).reapplyPendingProjection()
            } catch (error: Exception) {
                error.handleMembershipRevocation(session)
                if (!error.isDeferredSync()) throw error
                return null
            }
        }
        val page = state.projectCache.pages.firstOrNull { it.project.id == projectId } ?: return null
        val ifMatch = if (original.type == ProjectMutationType.SAVE_NOTES) {
            page.notes.rowVersion
        } else {
            page.project.rowVersion
        }
        val prepared = original.copy(ifMatch = ifMatch)
        state = state.updateCommand(original.operationId) { prepared }
        state = save(session, state)
        return state to prepared
    }

    private fun sendProjectCommand(session: Session, command: ProjectMutation): ProjectOutcome = when (command.type) {
        ProjectMutationType.CREATE_PROJECT -> ProjectOutcome.Project(
            service.createProject(
                session,
                requireNotNull(command.projectName),
                command.clientResourceId,
                requireNotNull(command.idempotencyKey),
            ),
        )
        ProjectMutationType.RENAME_PROJECT -> ProjectOutcome.Project(
            service.renameProject(session, command.targetId, requireNotNull(command.ifMatch), requireNotNull(command.projectName)),
        )
        ProjectMutationType.ARCHIVE_PROJECT -> ProjectOutcome.Project(
            service.archiveProject(session, command.targetId, requireNotNull(command.ifMatch)),
        )
        ProjectMutationType.REOPEN_PROJECT -> ProjectOutcome.Project(
            service.reopenProject(session, command.targetId, requireNotNull(command.ifMatch)),
        )
        ProjectMutationType.SAVE_NOTES -> ProjectOutcome.Notes(
            service.saveNotes(session, command.targetId, requireNotNull(command.ifMatch), requireNotNull(command.notesBody)),
        )
        ProjectMutationType.ADD_PARKING -> ProjectOutcome.Parking(
            service.addParking(
                session,
                command.targetId,
                requireNotNull(command.parking),
                command.supersedesId,
                command.clientResourceId,
                command.localCreatedAt,
                requireNotNull(command.idempotencyKey),
            ),
        )
        ProjectMutationType.BIND_BLOCK -> ProjectOutcome.Binding(
            service.bindBlock(session, command.targetId, requireNotNull(command.projectId), requireNotNull(command.idempotencyKey)),
        )
        ProjectMutationType.UNBIND_BLOCK -> {
            service.unbindBlock(session, command.targetId)
            ProjectOutcome.Unbound(command.targetId)
        }
    }

    private fun applyOutcome(
        state: TenantSyncState,
        command: ProjectMutation,
        outcome: ProjectOutcome,
    ): TenantSyncState = when (outcome) {
        is ProjectOutcome.Project -> if (command.type == ProjectMutationType.CREATE_PROJECT) {
            if (command.contractVersion == "0.4") {
                check(outcome.value.clientId == command.clientResourceId) {
                    "Service acknowledged a different project client identity"
                }
            }
            state.reconcileProjectIdentity(command.targetId, outcome.value)
        } else {
            state.withProject(outcome.value)
        }
        is ProjectOutcome.Notes -> state.updatePage(outcome.value.projectId) { it.copy(notes = outcome.value) }
        is ProjectOutcome.Parking -> {
            if (command.contractVersion == "0.4") {
                check(outcome.value.clientId == command.clientResourceId) {
                    "Service acknowledged a different parking client identity"
                }
            }
            state.withParkingAcknowledgement(command, outcome.value)
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
                state = save(session, state)
            } while (page.hasMore)
            state
        } catch (error: Exception) {
            if (error is ServiceException && error.status == 403) throw error
            if (!error.isDeferredSync()) throw error
            syncStore.loadSyncState(session.tenantId)
        }
    }

    private fun observeEvent(state: TenantSyncState, event: ChangeEvent): TenantSyncState {
        var updated = legacyReplayer.observe(state, event)
        val projectId = event.payload["projectId"]?.toString()
        val cache = updated.projectCache
        val changed = when {
            event.eventType.startsWith("session.") -> cache
            event.eventType in PROJECT_LIST_EVENTS -> cache.copy(
                activeProjectsLoaded = false,
                archivedProjectsLoaded = false,
                stalePageIds = (cache.stalePageIds + listOfNotNull(projectId)).distinct(),
            )
            event.eventType in PROJECT_PAGE_EVENTS && projectId != null -> cache.copy(
                stalePageIds = (cache.stalePageIds + projectId).distinct(),
                parkingLoadedProjectIds = if (event.eventType == "project.parked") {
                    cache.parkingLoadedProjectIds - projectId
                } else {
                    cache.parkingLoadedProjectIds
                },
            )
            event.eventType in BLOCK_EVENTS -> cache.copy(blocksLoaded = false)
            else -> cache
        }
        if (changed != cache) updated = updated.copy(projectCache = changed)
        return updated
    }

    private fun refreshInvalidatedBestEffort(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.activeProjectsLoaded) it else {
                it.mergeRemoteProjects(service.listProjects(session, ProjectLifecycle.ACTIVE), ProjectLifecycle.ACTIVE)
            }
        }
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.archivedProjectsLoaded) it else {
                it.mergeRemoteProjects(service.listProjects(session, ProjectLifecycle.ARCHIVED), ProjectLifecycle.ARCHIVED)
            }
        }
        state = bestEffortRefresh(session, state) {
            if (it.projectCache.blocksLoaded) it else it.copy(
                projectCache = it.projectCache.copy(blocks = service.blocks(session), blocksLoaded = true),
            )
        }
        val missingOrStalePages = (
            state.projectCache.stalePageIds +
                (state.projectCache.activeProjects + state.projectCache.archivedProjects)
                    .map { it.id }
                    .filter { projectId -> state.projectCache.pages.none { it.project.id == projectId } }
            ).distinct()
        missingOrStalePages.forEach { projectId ->
            if (projectId !in state.projectCache.provisionalProjectIds) {
                state = bestEffortRefresh(session, state) {
                    it.withPage(service.projectPage(session, projectId)).reapplyPendingProjection()
                }
            }
        }
        val missingParkingHistory = (state.projectCache.activeProjects + state.projectCache.archivedProjects)
            .map { it.id }
            .filterNot { it in state.projectCache.provisionalProjectIds }
            .filterNot { it in state.projectCache.parkingLoadedProjectIds }
            .distinct()
        missingParkingHistory.forEach { projectId ->
            state = bestEffortRefresh(session, state) {
                it.withParkingHistory(projectId, service.parking(session, projectId))
            }
        }
        state = save(session, state)
        return state
    }

    private fun bestEffortRefresh(
        session: Session,
        state: TenantSyncState,
        refresh: (TenantSyncState) -> TenantSyncState,
    ): TenantSyncState = try {
        save(session, refresh(state))
    } catch (error: Exception) {
        if (error is ServiceException && error.status == 403) throw error
        if (!error.isDeferredSync()) throw error
        state
    }

    private fun refreshConflictTargetBestEffort(
        session: Session,
        state: TenantSyncState,
        command: ProjectMutation,
    ): ConflictRefresh {
        if (command.type == ProjectMutationType.CREATE_PROJECT) return ConflictRefresh(state, false, null)
        return try {
            val page = service.projectPage(session, command.projectIdOrTarget())
            ConflictRefresh(state, true, page)
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (!error.isDeferredSync()) throw error
            ConflictRefresh(state, false, null)
        }
    }

    private fun retireProjectCommand(state: TenantSyncState, command: ProjectMutation): TenantSyncState = state.copy(
        projectCache = state.projectCache.copy(
            commands = state.projectCache.commands.filterNot { it.operationId == command.operationId },
            retiredOperationIds = (state.projectCache.retiredOperationIds + command.operationId).distinct(),
        ),
        projectDrafts = state.projectDrafts.cleanAfter(command),
    )

    private fun TenantSyncState.reconcileProjectIdentity(
        provisionalId: String,
        serverProject: ProjectSummary,
    ): TenantSyncState {
        val serverId = serverProject.id
        val localPage = projectCache.pages.firstOrNull { it.project.id == provisionalId }
        val commands = projectCache.commands.map { command ->
            command.copy(
                targetId = if (command.targetId == provisionalId) serverId else command.targetId,
                projectId = if (command.projectId == provisionalId) serverId else command.projectId,
            )
        }
        val mappedDrafts = projectDrafts.remapProject(provisionalId, serverId)
        val page = localPage?.copy(
            project = serverProject.copy(
                name = localPage.project.name,
                lifecycle = localPage.project.lifecycle,
            ),
            notes = localPage.notes.copy(projectId = serverId),
            newestParking = localPage.newestParking?.copy(projectId = serverId),
        ) ?: ProjectPage(serverProject, ProjectNotes(serverId, "", 0, serverProject.updatedAt), null)
        return copy(
            projectCache = projectCache.copy(
                activeProjects = projectCache.activeProjects.filterNot { it.id == provisionalId },
                archivedProjects = projectCache.archivedProjects.filterNot { it.id == provisionalId },
                pages = projectCache.pages.filterNot { it.project.id == provisionalId }.upsert(page) { it.project.id },
                parkingRecords = projectCache.parkingRecords.map {
                    if (it.projectId == provisionalId) it.copy(projectId = serverId) else it
                },
                parkingLoadedProjectIds = projectCache.parkingLoadedProjectIds - provisionalId,
                commands = commands,
                provisionalProjectIds = projectCache.provisionalProjectIds - provisionalId,
                projectIdMappings = (
                    projectCache.projectIdMappings.filterNot { it.provisionalId == provisionalId } +
                        ProjectIdentityMapping(provisionalId, serverId)
                    ).distinctBy { it.provisionalId },
                projectsAwaitingNotesBaseline = (
                    projectCache.projectsAwaitingNotesBaseline.filterNot { it == provisionalId } + serverId
                    ).distinct(),
                stalePageIds = projectCache.stalePageIds.map { if (it == provisionalId) serverId else it }.distinct(),
                blocks = projectCache.blocks.map {
                    if (it.boundProjectId == provisionalId) it.copy(boundProjectId = serverId) else it
                },
                localBlocks = projectCache.localBlocks.map {
                    it.remapProject(provisionalId, serverId)
                },
                localBlockOperations = projectCache.localBlockOperations.map {
                    it.remapProject(provisionalId, serverId)
                },
                projectLinks = projectCache.projectLinks.map { identity ->
                    if (identity.projectId == provisionalId) identity.copy(projectId = serverId) else identity
                }.distinctBy { it.projectId },
                projectCovers = projectCache.projectCovers.map { cover ->
                    if (cover.projectId == provisionalId) cover.copy(projectId = serverId) else cover
                }.distinctBy { it.projectId },
            ),
            projectDrafts = mappedDrafts,
        ).withProjectedLists()
    }

    private fun TenantSyncState.mergeRemoteProjects(
        remote: List<ProjectSummary>,
        lifecycle: ProjectLifecycle,
    ): TenantSyncState {
        val provisional = projectCache.pages.map { it.project }.filter {
            it.id in projectCache.provisionalProjectIds && it.lifecycle == lifecycle
        }
        val cache = if (lifecycle == ProjectLifecycle.ACTIVE) {
            projectCache.copy(activeProjects = (remote + provisional).distinctBy { it.id }, activeProjectsLoaded = true)
        } else {
            projectCache.copy(archivedProjects = (remote + provisional).distinctBy { it.id }, archivedProjectsLoaded = true)
        }
        return copy(projectCache = cache).reapplyPendingProjection()
    }

    private fun TenantSyncState.reapplyPendingProjection(): TenantSyncState {
        var state = withProjectedLists()
        projectCache.commands.forEach { command -> state = state.applyProjection(command) }
        return state.withProjectedLists()
    }

    private fun TenantSyncState.applyProjection(command: ProjectMutation): TenantSyncState = when (command.type) {
        ProjectMutationType.CREATE_PROJECT -> {
            val createdAt = command.localCreatedAt ?: now()
            val project = ProjectSummary(
                id = command.targetId,
                tenantId = activeTenantId ?: error("No active tenant for local project projection"),
                name = requireNotNull(command.projectName),
                lifecycle = ProjectLifecycle.ACTIVE,
                rowVersion = 0,
                createdAt = createdAt,
                updatedAt = createdAt,
                clientId = command.clientResourceId,
            )
            val page = ProjectPage(project, ProjectNotes(project.id, "", 0, createdAt), null)
            copy(
                projectCache = projectCache.copy(
                    activeProjects = projectCache.activeProjects.upsert(project) { it.id },
                    pages = projectCache.pages.upsert(page) { it.project.id },
                    provisionalProjectIds = (projectCache.provisionalProjectIds + project.id).distinct(),
                    parkingLoadedProjectIds = (projectCache.parkingLoadedProjectIds + project.id).distinct(),
                ),
            )
        }
        ProjectMutationType.RENAME_PROJECT -> updateProject(command.targetId) {
            it.copy(name = requireNotNull(command.projectName))
        }
        ProjectMutationType.ARCHIVE_PROJECT -> updateProject(command.targetId) {
            it.copy(lifecycle = ProjectLifecycle.ARCHIVED)
        }
        ProjectMutationType.REOPEN_PROJECT -> updateProject(command.targetId) {
            it.copy(lifecycle = ProjectLifecycle.ACTIVE, archivedAt = null)
        }
        ProjectMutationType.SAVE_NOTES -> updatePage(command.targetId) { page ->
            page.copy(notes = page.notes.copy(body = requireNotNull(command.notesBody)))
        }
        ProjectMutationType.ADD_PARKING -> {
            val parking = requireNotNull(command.parking)
            val project = projectCache.pages.first { it.project.id == command.targetId }.project
            withParkingRecord(
                ProjectParking(
                    id = command.clientResourceId ?: command.operationId,
                    projectId = command.targetId,
                    location = parking.location,
                    nextAction = parking.nextAction,
                    mustNotForget = parking.mustNotForget,
                    authoredByUserId = "local",
                    createdAt = command.localCreatedAt ?: project.updatedAt,
                    occurredAt = command.localCreatedAt ?: project.updatedAt,
                    clientId = command.clientResourceId,
                    supersedesId = command.supersedesId,
                ),
            )
        }
        ProjectMutationType.BIND_BLOCK -> copy(
            projectCache = projectCache.copy(
                blocks = projectCache.blocks.map {
                    if (it.id == command.targetId) it.copy(boundProjectId = command.projectId) else it
                },
            ),
        )
        ProjectMutationType.UNBIND_BLOCK -> copy(
            projectCache = projectCache.copy(
                blocks = projectCache.blocks.map {
                    if (it.id == command.targetId) it.copy(boundProjectId = null) else it
                },
            ),
        )
    }

    private fun TenantSyncState.withProjectedLists(): TenantSyncState {
        val known = (projectCache.activeProjects + projectCache.archivedProjects)
            .associateBy { it.id }
            .toMutableMap()
        known.putAll(projectCache.pages.associate { it.project.id to it.project })
        return copy(
            projectCache = projectCache.copy(
                activeProjects = known.values.filter { it.lifecycle == ProjectLifecycle.ACTIVE },
                archivedProjects = known.values.filter { it.lifecycle == ProjectLifecycle.ARCHIVED },
            ),
        )
    }

    private fun TenantSyncState.withProject(project: ProjectSummary): TenantSyncState {
        val page = projectCache.pages.firstOrNull { it.project.id == project.id }
        return if (page == null) {
            val updated = copy(
                projectCache = projectCache.copy(
                    activeProjects = projectCache.activeProjects.filterNot { it.id == project.id } +
                        listOfNotNull(project.takeIf { it.lifecycle == ProjectLifecycle.ACTIVE }),
                    archivedProjects = projectCache.archivedProjects.filterNot { it.id == project.id } +
                        listOfNotNull(project.takeIf { it.lifecycle == ProjectLifecycle.ARCHIVED }),
                ),
            )
            updated
        } else {
            withPage(page.copy(project = project))
        }
    }

    private fun TenantSyncState.withPage(page: ProjectPage): TenantSyncState {
        val withPage = copy(
            projectCache = projectCache.copy(
                pages = projectCache.pages.upsert(page) { it.project.id },
                parkingRecords = page.newestParking?.let { newest ->
                    projectCache.parkingRecords.upsertParking(newest)
                } ?: projectCache.parkingRecords,
                stalePageIds = projectCache.stalePageIds - page.project.id,
            ),
        )
        return withPage.withProjectedLists()
    }

    private fun TenantSyncState.withParkingRecord(record: ProjectParking): TenantSyncState {
        val records = projectCache.parkingRecords.upsertParking(record)
        val newest = records.newestFirstFor(record.projectId).firstOrNull()
        return copy(
            projectCache = projectCache.copy(
                parkingRecords = records,
                pages = projectCache.pages.map { page ->
                    if (page.project.id == record.projectId) page.copy(newestParking = newest) else page
                },
            ),
        )
    }

    private fun TenantSyncState.withParkingAcknowledgement(
        command: ProjectMutation,
        acknowledged: ProjectParking,
    ): TenantSyncState {
        val clientId = command.clientResourceId
        val withoutProvisional = projectCache.parkingRecords.filterNot {
            it.id == clientId || clientId != null && it.clientId == clientId
        }
        return copy(projectCache = projectCache.copy(parkingRecords = withoutProvisional))
            .withParkingRecord(acknowledged)
    }

    private fun TenantSyncState.withParkingHistory(
        projectId: String,
        remote: List<ProjectParking>,
    ): TenantSyncState {
        val pendingClientIds = projectCache.commands.filter {
            it.type == ProjectMutationType.ADD_PARKING && it.projectIdOrTarget() == projectId
        }.mapNotNull { it.clientResourceId }.toSet()
        val localPending = projectCache.parkingRecords.filter {
            it.projectId == projectId && (it.clientId ?: it.id) in pendingClientIds
        }
        val mergedForProject = (remote + localPending).distinctBy { it.clientId ?: it.id }
        val records = projectCache.parkingRecords.filterNot { it.projectId == projectId } + mergedForProject
        val newest = records.newestFirstFor(projectId).firstOrNull()
        return copy(
            projectCache = projectCache.copy(
                parkingRecords = records,
                parkingLoadedProjectIds = (projectCache.parkingLoadedProjectIds + projectId).distinct(),
                pages = projectCache.pages.map { page ->
                    if (page.project.id == projectId) page.copy(newestParking = newest) else page
                },
            ),
        )
    }

    private fun TenantSyncState.updatePage(projectId: String, update: (ProjectPage) -> ProjectPage): TenantSyncState {
        require(projectCache.pages.any { it.project.id == projectId }) { "Unknown local project $projectId" }
        return copy(
            projectCache = projectCache.copy(
                pages = projectCache.pages.map { if (it.project.id == projectId) update(it) else it },
            ),
        )
    }

    private fun TenantSyncState.updateProject(
        projectId: String,
        update: (ProjectSummary) -> ProjectSummary,
    ): TenantSyncState {
        val existing = projectCache.pages.firstOrNull { it.project.id == projectId }?.project
            ?: projectCache.activeProjects.firstOrNull { it.id == projectId }
            ?: projectCache.archivedProjects.firstOrNull { it.id == projectId }
            ?: throw IllegalArgumentException("Unknown local project $projectId")
        val updated = update(existing)
        return copy(
            projectCache = projectCache.copy(
                activeProjects = projectCache.activeProjects.map { if (it.id == projectId) updated else it },
                archivedProjects = projectCache.archivedProjects.map { if (it.id == projectId) updated else it },
                pages = projectCache.pages.map {
                    if (it.project.id == projectId) it.copy(project = updated) else it
                },
            ),
        )
    }

    private fun TenantSyncState.updateCommand(
        operationId: String,
        update: (ProjectMutation) -> ProjectMutation,
    ) = copy(
        projectCache = projectCache.copy(
            commands = projectCache.commands.map { if (it.operationId == operationId) update(it) else it },
        ),
    )

    private fun ProjectCacheState.latestProjectDependency(projectId: String): String? = commands.lastOrNull {
        it.targetId == projectId || it.projectId == projectId
    }?.operationId

    private fun FormDrafts.cleanAfter(command: ProjectMutation): FormDrafts {
        val contentCleaned = when (command.type) {
            ProjectMutationType.CREATE_PROJECT -> copy(
                projectName = if (projectName == command.projectName) "" else projectName,
            )
            ProjectMutationType.RENAME_PROJECT -> copy(
                projectRenames = if (projectRenames[command.targetId] == command.projectName) {
                    projectRenames - command.targetId
                } else projectRenames,
            )
            ProjectMutationType.SAVE_NOTES -> copy(
                projectNotes = if (projectNotes[command.targetId] == command.notesBody) {
                    projectNotes - command.targetId
                } else projectNotes,
            )
            ProjectMutationType.ADD_PARKING -> copy(
                projectParking = if (projectParking[command.targetId] == command.parking) {
                    projectParking - command.targetId
                } else projectParking,
            )
            else -> this
        }
        return contentCleaned.copy(
            pendingMutations = contentCleaned.pendingMutations.filterValues {
                it.idempotencyKey != command.operationId
            },
        )
    }

    private fun FormDrafts.remapProject(from: String, to: String): FormDrafts = copy(
        projectNotes = projectNotes.remapKey(from, to),
        projectParking = projectParking.remapKey(from, to),
        projectRenames = projectRenames.remapKey(from, to),
        pendingMutations = pendingMutations.mapValues { (_, pending) ->
            if (pending.targetId == from) pending.copy(targetId = to) else pending
        }.mapKeys { (slot, _) -> slot.replace(":$from", ":$to") },
    )

    private fun <T> Map<String, T>.remapKey(from: String, to: String): Map<String, T> = buildMap {
        this@remapKey.forEach { (key, value) -> put(if (key == from) to else key, value) }
    }

    private fun migrateProjectDrafts(session: Session) {
        val state = syncStore.loadSyncState(session.tenantId)
        if (state.projectDraftsMigrated) return
        val legacy = legacyDraftStore.load(session.tenantId)
        val projectPending = legacy.pendingMutations.filterKeys { it.startsWith("project-") }
        val migrated = state.projectDrafts.copy(
            projectName = legacy.projectName,
            projectNotes = legacy.projectNotes,
            projectParking = legacy.projectParking,
            projectRenames = legacy.projectRenames,
            pendingMutations = state.projectDrafts.pendingMutations + projectPending,
        )
        save(session, state.copy(projectDrafts = migrated, projectDraftsMigrated = true))
        legacyDraftStore.save(
            session.tenantId,
            legacy.copy(
                projectName = "",
                projectNotes = emptyMap(),
                projectParking = emptyMap(),
                projectRenames = emptyMap(),
                pendingMutations = legacy.pendingMutations - projectPending.keys,
            ),
        )
    }

    private fun ProjectMutation.normalized() = copy(
        ifMatch = null,
        dependsOnOperationId = null,
        localCreatedAt = null,
        status = ProjectMutationStatus.PENDING,
        deliveryUncertain = false,
        failureMessage = null,
        authoritativeStateConfirmed = false,
        authoritativePage = null,
    )

    private fun ProjectMutation.projectIdOrTarget() = projectId ?: targetId

    private fun ProjectMutation.isSatisfiedByAuthoritativeState(): Boolean {
        if (!authoritativeStateConfirmed) return false
        val page = authoritativePage ?: return false
        return when (type) {
            ProjectMutationType.CREATE_PROJECT -> false
            ProjectMutationType.RENAME_PROJECT -> page.project.rowVersion > requireNotNull(ifMatch) &&
                page.project.name == projectName
            ProjectMutationType.ARCHIVE_PROJECT -> page.project.rowVersion > requireNotNull(ifMatch) &&
                page.project.lifecycle == ProjectLifecycle.ARCHIVED
            ProjectMutationType.REOPEN_PROJECT -> page.project.rowVersion > requireNotNull(ifMatch) &&
                page.project.lifecycle == ProjectLifecycle.ACTIVE
            ProjectMutationType.SAVE_NOTES -> page.notes.rowVersion > requireNotNull(ifMatch) && page.notes.body == notesBody
            // An append is never retired by content equality; only replay of its exact key can acknowledge it.
            ProjectMutationType.ADD_PARKING -> false
            ProjectMutationType.BIND_BLOCK,
            ProjectMutationType.UNBIND_BLOCK,
            -> false
        }
    }

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
        val conflicting = projectCache.commands.firstOrNull {
            it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
        }
        return BackgroundSyncResult(
            cache = projectCache,
            cursor = changeCursor,
            pendingLegacyOperations = commands.size,
            pendingProjectOperations = projectCache.commands.size,
            reconciliationRequired = reconciliation,
            conflict = conflicting?.let { command ->
                ProjectConflict(
                    operationId = command.operationId,
                    type = command.type,
                    projectId = command.projectIdOrTarget().takeUnless {
                        command.type == ProjectMutationType.CREATE_PROJECT
                    },
                    localProjectName = command.projectName,
                    localNotes = command.notesBody,
                    localParking = command.parking,
                    authoritativePage = command.authoritativePage,
                )
            },
            pendingBlockOperations = projectCache.localBlockOperations.size,
        )
    }

    private fun checkActive(session: Session) {
        check(activeTenantId == session.tenantId) { "No project state is active for this tenant" }
    }

    private fun activeState(session: Session): TenantSyncState {
        checkActive(session)
        return syncStore.loadSyncState(session.tenantId)
    }

    private fun save(session: Session, state: TenantSyncState): TenantSyncState {
        val stateWithLinks = state.withProjectLinks()
        val context = backgroundSaveContext.get()
        if (context == null) {
            syncStore.saveSyncState(session.tenantId, stateWithLinks)
            return stateWithLinks
        }
        return synchronized(this) {
            checkActive(session)
            val current = syncStore.loadSyncState(session.tenantId)
            val merged = mergeConcurrentLocalChanges(context.baseline, stateWithLinks, current).withProjectLinks()
            syncStore.saveSyncState(session.tenantId, merged)
            context.baseline = merged
            merged
        }
    }

    private fun TenantSyncState.withProjectLinks(): TenantSyncState = copy(
        projectCache = projectCache.ensureProjectLinks(projectKeyFactory),
    )

    /** Keeps local commits made while transport is waiting when a background snapshot is saved. */
    private fun mergeConcurrentLocalChanges(
        baseline: TenantSyncState,
        proposed: TenantSyncState,
        current: TenantSyncState,
    ): TenantSyncState {
        if (current == baseline) return proposed

        val baselineCommands = baseline.projectCache.commands.associateBy { it.operationId }
        val currentCommands = current.projectCache.commands.associateBy { it.operationId }
        val locallyRemoved = baselineCommands.keys - currentCommands.keys
        val mappings = proposed.projectCache.projectIdMappings
        val locallyChanged = current.projectCache.commands.filter {
            baselineCommands[it.operationId] != it
        }.map { command ->
            mappings.fold(command) { mapped, identity ->
                mapped.remapProject(identity.provisionalId, identity.serverId)
            }
        }
        val locallyChangedIds = locallyChanged.map { it.operationId }.toSet()
        val commands = proposed.projectCache.commands.filterNot {
            it.operationId in locallyRemoved || it.operationId in locallyChangedIds
        } + locallyChanged

        var merged = proposed.copy(
            projectDrafts = if (current.projectDrafts != baseline.projectDrafts) {
                mappings.fold(current.projectDrafts) { drafts, identity ->
                    drafts.remapProject(identity.provisionalId, identity.serverId)
                }
            } else {
                proposed.projectDrafts
            },
            projectUiPreferences = if (current.projectUiPreferences != baseline.projectUiPreferences) {
                current.projectUiPreferences
            } else {
                proposed.projectUiPreferences
            },
            projectCache = proposed.projectCache.copy(
                commands = commands,
                localBlocks = if (current.projectCache.localBlocks != baseline.projectCache.localBlocks) {
                    current.projectCache.localBlocks.map { local ->
                        mappings.fold(local) { mapped, identity ->
                            mapped.remapProject(identity.provisionalId, identity.serverId)
                        }
                    }
                } else {
                    proposed.projectCache.localBlocks
                },
                localBlockOperations = if (
                    current.projectCache.localBlockOperations != baseline.projectCache.localBlockOperations
                ) {
                    current.projectCache.localBlockOperations.map { local ->
                        mappings.fold(local) { mapped, identity ->
                            mapped.remapProject(identity.provisionalId, identity.serverId)
                        }
                    }
                } else {
                    proposed.projectCache.localBlockOperations
                },
                retiredOperationIds = (
                    proposed.projectCache.retiredOperationIds +
                        (current.projectCache.retiredOperationIds - baseline.projectCache.retiredOperationIds.toSet())
                    ).distinct(),
                provisionalProjectIds = (
                    proposed.projectCache.provisionalProjectIds +
                        (current.projectCache.provisionalProjectIds - baseline.projectCache.provisionalProjectIds.toSet())
                    ).distinct().filterNot { provisional ->
                    proposed.projectCache.projectIdMappings.any { it.provisionalId == provisional }
                },
                parkingLoadedProjectIds = (
                    proposed.projectCache.parkingLoadedProjectIds +
                        (current.projectCache.parkingLoadedProjectIds -
                            baseline.projectCache.parkingLoadedProjectIds.toSet())
                    ).distinct().filterNot { projectId ->
                    mappings.any { it.provisionalId == projectId }
                },
            ),
        )

        val baselineParking = baseline.projectCache.parkingRecords.associateBy { it.clientId ?: it.id }
        current.projectCache.parkingRecords.filter {
            baselineParking[it.clientId ?: it.id] != it
        }.forEach { local ->
            val resolvedProjectId = proposed.projectCache.projectIdMappings
                .firstOrNull { it.provisionalId == local.projectId }?.serverId ?: local.projectId
            merged = merged.withParkingRecord(local.copy(projectId = resolvedProjectId))
        }

        merged = merged.copy(
            projectCache = merged.projectCache.copy(commands = commands),
        ).reapplyPendingProjection()
        return merged
    }

    private fun ProjectMutation.remapProject(provisionalId: String, serverId: String) = copy(
        targetId = if (targetId == provisionalId) serverId else targetId,
        projectId = if (projectId == provisionalId) serverId else projectId,
        authoritativePage = authoritativePage?.let { page ->
            if (page.project.id == provisionalId) {
                page.copy(
                    project = page.project.copy(id = serverId),
                    notes = page.notes.copy(projectId = serverId),
                    newestParking = page.newestParking?.copy(projectId = serverId),
                )
            } else {
                page
            }
        },
    )

    private fun LocalBlockRecord.remapProject(provisionalId: String, serverId: String) = copy(
        intendedProjectId = if (intendedProjectId == provisionalId) serverId else intendedProjectId,
        boundProjectId = if (boundProjectId == provisionalId) serverId else boundProjectId,
    )

    private fun LocalBlockOperation.remapProject(provisionalId: String, serverId: String) = copy(
        projectId = if (projectId == provisionalId) serverId else projectId,
    )

    private fun updateLocalBlock(
        session: Session,
        clientBlockId: String,
        update: (LocalBlockRecord) -> LocalBlockRecord,
    ): LocalBlockRecord {
        val state = activeState(session)
        val block = state.projectCache.localBlocks.firstOrNull { it.clientBlockId == clientBlockId }
            ?: throw IllegalArgumentException("The local block does not exist")
        val updatedBlock = update(block)
        save(
            session,
            state.copy(
                projectCache = state.projectCache.copy(
                    localBlocks = state.projectCache.localBlocks.replaceBlock(updatedBlock),
                ),
            ),
        )
        return updatedBlock
    }

    private fun purge(tenantId: String) {
        val syncFailure = runCatching { syncStore.clearSyncState(tenantId) }.exceptionOrNull()
        val draftFailure = runCatching { legacyDraftStore.clear(tenantId) }.exceptionOrNull()
        (syncFailure ?: draftFailure)?.let { failure ->
            listOfNotNull(syncFailure, draftFailure).filterNot { it === failure }.forEach(failure::addSuppressed)
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

    private fun Exception.isDeferredSync() = this is IOException ||
        this is IncompatibleServiceApiException ||
        this is ServiceException && (status >= 500 || status in setOf(404, 405, 426))

    private fun Exception.isRecognizedOlderApi(command: ProjectMutation) =
        this is ServiceException && status == 422 && command.contractVersion == "0.4" &&
            command.type in setOf(ProjectMutationType.CREATE_PROJECT, ProjectMutationType.ADD_PARKING)

    private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> = filterNot { id(it) == id(value) } + value

    private fun List<LocalBlockRecord>.replaceBlock(value: LocalBlockRecord): List<LocalBlockRecord> =
        map { if (it.clientBlockId == value.clientBlockId) value else it }

    private fun ProjectCacheState.resolveProjectId(projectId: String): String =
        projectIdMappings.firstOrNull { it.provisionalId == projectId }?.serverId ?: projectId

    private fun LocalBlockRecord.toResolution(): LocalTagResolution = when {
        state in PENDING_TAG_STATES -> LocalTagResolution.PendingProgramming(this)
        state == LocalBlockState.UNBOUND || boundProjectId == null -> LocalTagResolution.Unbound(this)
        else -> LocalTagResolution.Bound(this, requireNotNull(boundProjectId))
    }

    private fun List<ProjectParking>.upsertParking(value: ProjectParking): List<ProjectParking> =
        filterNot { existing ->
            existing.id == value.id ||
                value.clientId != null && existing.clientId == value.clientId
        } + value

    private fun List<ProjectParking>.newestFirstFor(projectId: String): List<ProjectParking> =
        filter { it.projectId == projectId }.sortedWith(
            compareByDescending<ProjectParking> { it.occurredAt }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id },
        )

    private data class ConflictRefresh(
        val state: TenantSyncState,
        val confirmed: Boolean,
        val page: ProjectPage?,
    )

    private data class BackgroundSaveContext(var baseline: TenantSyncState)

    private sealed interface ProjectOutcome {
        data class Project(val value: ProjectSummary) : ProjectOutcome
        data class Notes(val value: ProjectNotes) : ProjectOutcome
        data class Parking(val value: ProjectParking) : ProjectOutcome
        data class Binding(val value: BlockBinding) : ProjectOutcome
        data class Unbound(val blockId: String) : ProjectOutcome
    }

    private companion object {
        const val CHANGE_PAGE_SIZE = 100
        const val MAX_BLOCK_LABEL = 200
        val PENDING_TAG_STATES = setOf(LocalBlockState.AWAITING_WRITE, LocalBlockState.WRITE_UNCERTAIN)
        val UNCERTAIN_TAG_FAILURES = setOf(
            TagProgrammingFailure.IO_UNCERTAIN,
            TagProgrammingFailure.VERIFICATION_MISMATCH,
        )
        val VERSIONED_TYPES = setOf(
            ProjectMutationType.RENAME_PROJECT,
            ProjectMutationType.ARCHIVE_PROJECT,
            ProjectMutationType.REOPEN_PROJECT,
            ProjectMutationType.SAVE_NOTES,
        )
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
