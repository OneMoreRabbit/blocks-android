package dev.blocks.android.sync

import dev.blocks.android.api.IncompatibleServiceApiException
import dev.blocks.android.api.ServiceException
import dev.blocks.android.cover.ProjectCoverMediaStore
import dev.blocks.android.cover.ProjectCoverVariant
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
import dev.blocks.android.project.AuthoritativeOrder
import dev.blocks.android.project.DashboardOrderMode
import dev.blocks.android.project.BlockBinding
import dev.blocks.android.project.PhysicalBlock
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectConflict
import dev.blocks.android.project.ProjectCover
import dev.blocks.android.project.ProjectCoverContent
import dev.blocks.android.project.ProjectIdentityMapping
import dev.blocks.android.project.InteractionMutation
import dev.blocks.android.project.InteractionAcknowledgement
import dev.blocks.android.project.InteractionMutationType
import dev.blocks.android.project.LinkOrderState
import dev.blocks.android.project.NoteOrderState
import dev.blocks.android.project.ParkingTombstone
import dev.blocks.android.project.ProjectItemAcknowledgement
import dev.blocks.android.project.ProjectItemMutation
import dev.blocks.android.project.ProjectItemMutationType
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectMutation
import dev.blocks.android.project.ProjectMutationStatus
import dev.blocks.android.project.ProjectMutationType
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectNoteItem
import dev.blocks.android.project.ParkingHistoryPage
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectParking
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.project.ProjectResourceLink
import dev.blocks.android.project.ProjectResourceUri
import dev.blocks.android.project.ProjectUiPreferences
import dev.blocks.android.project.ProjectWorkspace
import dev.blocks.android.project.ensureProjectLinks
import dev.blocks.android.project.ensureInteractionState
import dev.blocks.android.project.visibleParking
import dev.blocks.android.project.newOperationIdentity
import dev.blocks.android.project.noteItemDraftKey
import dev.blocks.android.project.resourceLinkDraftKey
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
import dev.blocks.android.tag.ProjectLinkIdentity
import dev.blocks.android.tag.TagActivationMedium
import dev.blocks.android.tag.TagProgrammingFailure
import java.io.IOException
import java.math.BigInteger
import java.time.Instant

/**
 * Tenant-local workspace. UI-facing calls atomically commit only the affected normalized rows
 * and durable command before returning. Only [backgroundSync] performs transport, so every
 * ordinary action remains available with an empty cache and no API.
 */
class ProjectSyncRepository(
    private val service: ProjectService,
    private val legacyReplayer: LegacyQueueReplay,
    private val legacyDraftStore: DraftStore,
    private val syncStore: SyncStateStore,
    private val now: () -> String = { Instant.now().toString() },
    private val projectKeyFactory: ProjectKeyFactory = ProjectKeyFactory(),
    private val coverMediaStore: ProjectCoverMediaStore,
) : ProjectWorkspace {
    @Volatile
    private var activeTenantId: String? = null
    @Volatile
    private var activeStateCache: TenantSyncState? = null
    private val backgroundLock = Any()
    private val backgroundSaveContext = ThreadLocal<BackgroundSaveContext?>()

    @Synchronized
    override fun activate(session: Session) {
        activeTenantId = session.tenantId
        activeStateCache = syncStore.loadSyncState(session.tenantId)
        migrateProjectDrafts(session)
        val state = activeState(session)
        val withProjectLinks = state.withProjectLinks()
        val active = if (withProjectLinks != state) save(session, withProjectLinks) else state
        require(active.projectCache.projectCovers.all { it.tenantId == session.tenantId }) {
            "Encrypted cover metadata crosses a tenant boundary"
        }
        coverMediaStore.cleanupOrphans(session.tenantId, active.projectCache.projectCovers)
    }

    @Synchronized
    override fun deactivate(session: Session) {
        if (activeTenantId == session.tenantId) {
            activeTenantId = null
            activeStateCache = null
        }
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
        return state.projectCache.ensureInteractionState().pages.firstOrNull { it.project.id == resolvedId }
            ?: throw IllegalStateException("This project is not available in saved local data.")
    }

    @Synchronized
    override fun projectCover(session: Session, projectId: String): ProjectCover? {
        val state = activeState(session)
        val resolvedId = state.projectCache.resolveProjectId(projectId)
        return state.projectCache.projectCovers.firstOrNull { it.projectId == resolvedId }
    }

    override fun readProjectCoverContent(
        session: Session,
        cover: ProjectCover,
        thumbnail: Boolean,
    ): ByteArray {
        synchronized(this) {
            checkActive(session)
            require(cover.tenantId == session.tenantId) { "Cover belongs to another tenant" }
            val current = projectCover(session, cover.projectId)
            require(current == cover) { "Cover metadata is no longer current" }
        }
        return coverMediaStore.read(
            session.tenantId,
            cover,
            if (thumbnail) ProjectCoverVariant.THUMBNAIL else ProjectCoverVariant.DISPLAY,
        )
    }

    @Synchronized
    override fun saveProjectCover(session: Session, cover: ProjectCoverContent): BackgroundSyncResult {
        val state = activeState(session)
        val resolvedId = state.projectCache.resolveProjectId(cover.projectId)
        require(state.projectCache.pages.any { it.project.id == resolvedId }) {
            "The selected project is not available in saved local data"
        }
        require(cover.displayWidth > 0 && cover.displayHeight > 0) { "The display cover is invalid" }
        require(cover.thumbnailWidth > 0 && cover.thumbnailHeight > 0) { "The cover thumbnail is invalid" }
        require(cover.displayJpeg.isNotEmpty() && cover.thumbnailJpeg.isNotEmpty()) {
            "The project cover has no display content"
        }
        val previous = state.projectCache.projectCovers.firstOrNull { it.projectId == resolvedId }
        var staged: ProjectCover? = null
        var persisted = false
        try {
            staged = coverMediaStore.stage(session.tenantId, resolvedId, cover, previous)
            val updated = state.copy(
                projectCache = state.projectCache.copy(
                    projectCovers = state.projectCache.projectCovers
                        .filterNot { it.projectId == resolvedId } + staged,
                ),
            )
            save(session, updated)
            persisted = true
            previous?.let { runCatching { coverMediaStore.retire(session.tenantId, it) } }
            return updated.toResult()
        } finally {
            if (!persisted) staged?.let { runCatching { coverMediaStore.retire(session.tenantId, it) } }
            cover.clear()
        }
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
        state.projectCache.projectCovers.firstOrNull { it.projectId == resolvedId }?.let {
            runCatching { coverMediaStore.retire(session.tenantId, it) }
        }
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
        val records = state.projectCache.visibleParking(resolvedId).newestFirstFor(resolvedId)
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
    override fun resolveProjectLinkThroughService(session: Session, key: ProjectKey): String? {
        resolveProjectLinkLocally(session, key)?.let { return it }
        val projectId = service.resolveNavigationKey(session, key) ?: return null
        var state = activeState(session)
        if (state.projectCache.pages.none { it.project.id == projectId }) {
            state = state.withPage(service.projectPage(session, projectId)).withProjectLinks()
        }
        val existingForProject = state.projectCache.projectLinks.firstOrNull { it.projectId == projectId }
        val existingForKey = state.projectCache.projectLinks.firstOrNull { it.key == key }
        require(existingForKey == null || existingForKey.projectId == projectId) {
            "A navigation key cannot resolve to two local projects"
        }
        require(existingForProject == null || existingForProject.key == key) {
            "Service navigation identity conflicts with the retained local project key"
        }
        if (existingForProject == null) {
            state = state.copy(
                projectCache = state.projectCache.copy(
                    projectLinks = state.projectCache.projectLinks + ProjectLinkIdentity(projectId, key),
                ),
            )
        }
        save(session, state)
        return projectId
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
        return save(session, updated).toResult()
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
    override fun addNoteItem(
        session: Session,
        projectId: String,
        body: String,
        operationId: String,
        clientId: String,
    ): BackgroundSyncResult {
        require(body.isNotBlank()) { "Enter a note." }
        require(body.length <= MAX_NOTE_BODY) { "Notes can contain at most 100,000 characters." }
        require(runCatching { java.util.UUID.fromString(clientId) }.isSuccess) { "Note identity must be a UUID." }
        val state = activeState(session)
        require(state.projectCache.pages.single { it.project.id == projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val note = ProjectNoteItem(
            id = clientId,
            projectId = projectId,
            body = body,
            pinned = false,
            authoredByUserId = "local",
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.CREATE_NOTE,
            targetId = clientId,
            projectId = projectId,
            clientResourceId = clientId,
            noteBody = body,
            dependsOnOperationId = state.projectCache.latestItemDependency(projectId, clientId),
            createdAt = timestamp,
        )
        return commitItemLocal(session, operation) { current ->
            current.copy(
                projectCache = current.projectCache.copy(noteItems = current.projectCache.noteItems + note),
                projectDrafts = current.projectDrafts.copy(
                    projectNoteItems = current.projectDrafts.projectNoteItems - noteItemDraftKey(projectId),
                ),
            )
        }
    }

    @Synchronized
    override fun editNoteItem(
        session: Session,
        noteId: String,
        body: String,
        operationId: String,
    ): BackgroundSyncResult {
        require(body.isNotBlank()) { "Enter a note." }
        require(body.length <= MAX_NOTE_BODY) { "Notes can contain at most 100,000 characters." }
        val state = activeState(session)
        val existing = state.projectCache.noteItems.single { it.id == noteId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == existing.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.EDIT_NOTE,
            targetId = noteId,
            projectId = existing.projectId,
            ifMatch = existing.rowVersion.takeIf { it > 0 },
            noteBody = body,
            dependsOnOperationId = state.projectCache.latestItemDependency(existing.projectId, noteId),
            createdAt = timestamp,
        )
        return commitItemLocal(session, operation) { current ->
            current.updateNote(noteId) { it.copy(body = body, updatedAt = timestamp) }.copy(
                projectDrafts = current.projectDrafts.copy(
                    projectNoteItems = current.projectDrafts.projectNoteItems - noteItemDraftKey(existing.projectId, noteId),
                ),
            )
        }
    }

    @Synchronized
    override fun setNoteItemPinned(
        session: Session,
        noteId: String,
        pinned: Boolean,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val existing = state.projectCache.noteItems.single { it.id == noteId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == existing.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.PIN_NOTE,
            targetId = noteId,
            projectId = existing.projectId,
            ifMatch = existing.rowVersion.takeIf { it > 0 },
            pinned = pinned,
            dependsOnOperationId = state.projectCache.latestItemDependency(existing.projectId, noteId),
            createdAt = timestamp,
        )
        return commitItemLocal(session, operation) { current ->
            current.updateNote(noteId) { it.copy(pinned = pinned, updatedAt = timestamp) }
        }
    }

    @Synchronized
    override fun deleteNoteItem(
        session: Session,
        noteId: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val existing = state.projectCache.noteItems.single { it.id == noteId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == existing.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.DELETE_NOTE,
            targetId = noteId,
            projectId = existing.projectId,
            ifMatch = existing.rowVersion.takeIf { it > 0 },
            dependsOnOperationId = state.projectCache.latestItemDependency(existing.projectId, noteId),
            createdAt = timestamp,
        )
        return commitItemLocal(session, operation) { current ->
            current.updateNote(noteId) { it.copy(updatedAt = timestamp, deletedAt = timestamp) }
        }
    }

    @Synchronized
    override fun addResourceLink(
        session: Session,
        projectId: String,
        title: String,
        uri: String,
        operationId: String,
        clientId: String,
    ): BackgroundSyncResult {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= MAX_LINK_TITLE) { "Enter a link title." }
        val cleanUri = ProjectResourceUri.validate(uri)
        require(runCatching { java.util.UUID.fromString(clientId) }.isSuccess) { "Link identity must be a UUID." }
        val state = activeState(session)
        require(state.projectCache.pages.single { it.project.id == projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val link = ProjectResourceLink(clientId, projectId, cleanTitle, cleanUri, timestamp, timestamp)
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.CREATE_LINK,
            targetId = clientId,
            projectId = projectId,
            clientResourceId = clientId,
            linkTitle = cleanTitle,
            linkUri = cleanUri,
            dependsOnOperationId = state.projectCache.latestItemDependency(projectId, clientId),
            createdAt = timestamp,
        )
        return commitLinkMutationLocal(session, operation) { current ->
            current.copy(
                projectCache = current.projectCache.copy(resourceLinks = current.projectCache.resourceLinks + link),
                projectDrafts = current.projectDrafts.copy(
                    projectResourceLinks = current.projectDrafts.projectResourceLinks - resourceLinkDraftKey(projectId),
                ),
            )
        }
    }

    @Synchronized
    override fun editResourceLink(
        session: Session,
        linkId: String,
        title: String,
        uri: String,
        operationId: String,
    ): BackgroundSyncResult {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= MAX_LINK_TITLE) { "Enter a link title." }
        val cleanUri = ProjectResourceUri.validate(uri)
        val state = activeState(session)
        val existing = state.projectCache.resourceLinks.single { it.id == linkId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == existing.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val invalidIntents = state.projectCache.itemOperations.filter { pending ->
            pending.targetId == linkId &&
                pending.status == ProjectMutationStatus.REQUIRES_RECONCILIATION &&
                pending.type in setOf(ProjectItemMutationType.CREATE_LINK, ProjectItemMutationType.EDIT_LINK) &&
                pending.linkUri?.let(ProjectResourceUri::canLaunch) == false
        }
        if (invalidIntents.isNotEmpty()) {
            return replaceInvalidLinkIntent(
                session = session,
                state = state,
                existing = existing,
                invalidIntents = invalidIntents,
                title = cleanTitle,
                uri = cleanUri,
                operationId = operationId,
                timestamp = timestamp,
            )
        }
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.EDIT_LINK,
            targetId = linkId,
            projectId = existing.projectId,
            ifMatch = existing.rowVersion.takeIf { it > 0 },
            linkTitle = cleanTitle,
            linkUri = cleanUri,
            dependsOnOperationId = state.projectCache.latestItemDependency(existing.projectId, linkId),
            createdAt = timestamp,
        )
        return commitItemLocal(session, operation) { current ->
            current.updateResourceLink(linkId) {
                it.copy(
                    title = cleanTitle,
                    uri = cleanUri,
                    updatedAt = timestamp,
                    requiresReconciliation = false,
                )
            }.copy(
                projectDrafts = current.projectDrafts.copy(
                    projectResourceLinks = current.projectDrafts.projectResourceLinks -
                        resourceLinkDraftKey(existing.projectId, linkId),
                ),
            )
        }
    }

    private fun replaceInvalidLinkIntent(
        session: Session,
        state: TenantSyncState,
        existing: ProjectResourceLink,
        invalidIntents: List<ProjectItemMutation>,
        title: String,
        uri: String,
        operationId: String,
        timestamp: String,
    ): BackgroundSyncResult {
        val invalidIds = invalidIntents.map { it.operationId }.toSet()
        require(operationId !in invalidIds && operationId !in state.projectCache.retiredItemOperationIds) {
            "A corrected Link needs a fresh operation identity"
        }
        require(state.projectCache.itemOperations.none { it.operationId == operationId }) {
            "A project item operation identity cannot describe two requests"
        }
        val firstInvalidIndex = state.projectCache.itemOperations.indexOfFirst {
            it.operationId in invalidIds
        }
        val earlierSameItem = state.projectCache.itemOperations
            .take(firstInvalidIndex)
            .lastOrNull { it.targetId == existing.id && it.operationId !in invalidIds }
        val originalDependency = invalidIntents.first().dependsOnOperationId?.takeUnless { it in invalidIds }
        val dependency = earlierSameItem?.operationId ?: originalDependency
        val replacementType = if (
            existing.rowVersion == 0 &&
            state.projectCache.itemOperations.none {
                it.targetId == existing.id &&
                    it.operationId !in invalidIds &&
                    it.type == ProjectItemMutationType.CREATE_LINK
            }
        ) {
            ProjectItemMutationType.CREATE_LINK
        } else {
            ProjectItemMutationType.EDIT_LINK
        }
        val replacement = ProjectItemMutation(
            operationId = operationId,
            type = replacementType,
            targetId = existing.id,
            projectId = existing.projectId,
            clientResourceId = existing.id.takeIf { replacementType == ProjectItemMutationType.CREATE_LINK },
            ifMatch = existing.rowVersion.takeIf {
                replacementType == ProjectItemMutationType.EDIT_LINK && dependency == null && it > 0
            },
            linkTitle = title,
            linkUri = uri,
            dependsOnOperationId = dependency,
            createdAt = timestamp,
        )
        val operations = buildList {
            state.projectCache.itemOperations.forEachIndexed { index, pending ->
                if (index == firstInvalidIndex) add(replacement)
                if (pending.operationId !in invalidIds) {
                    add(
                        if (pending.dependsOnOperationId in invalidIds) {
                            pending.copy(dependsOnOperationId = replacement.operationId)
                        } else {
                            pending
                        },
                    )
                }
            }
        }
        val updatedLinkState = state.updateResourceLink(existing.id) {
            it.copy(
                title = title,
                uri = uri,
                updatedAt = timestamp,
                requiresReconciliation = false,
            )
        }
        val updated = updatedLinkState.copy(
            projectDrafts = state.projectDrafts.copy(
                projectResourceLinks = state.projectDrafts.projectResourceLinks -
                    resourceLinkDraftKey(existing.projectId, existing.id),
            ),
            projectCache = updatedLinkState.projectCache.copy(
                itemOperations = operations,
                retiredItemOperationIds = (
                    state.projectCache.retiredItemOperationIds + invalidIds
                    ).distinct(),
            ),
        )
        return save(session, updated).toResult()
    }

    @Synchronized
    override fun deleteResourceLink(
        session: Session,
        linkId: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session)
        val existing = state.projectCache.resourceLinks.single { it.id == linkId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == existing.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val timestamp = now()
        val operation = ProjectItemMutation(
            operationId = operationId,
            type = ProjectItemMutationType.DELETE_LINK,
            targetId = linkId,
            projectId = existing.projectId,
            ifMatch = existing.rowVersion.takeIf { it > 0 },
            dependsOnOperationId = state.projectCache.latestItemDependency(existing.projectId, linkId),
            createdAt = timestamp,
        )
        return commitLinkMutationLocal(session, operation) { current ->
            current.updateResourceLink(linkId) { it.copy(updatedAt = timestamp, deletedAt = timestamp) }
        }
    }

    @Synchronized
    override fun moveResourceLink(
        session: Session,
        linkId: String,
        delta: Int,
        operationId: String,
    ): BackgroundSyncResult {
        if (delta == 0) return activeState(session).toResult()
        val state = activeState(session).withProjectLinks()
        val link = state.projectCache.resourceLinks.single { it.id == linkId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == link.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val aggregate = state.projectCache.linkOrders.single { it.projectId == link.projectId }
        val from = aggregate.orderedLinkIds.indexOf(linkId)
        val to = from + delta
        if (from < 0 || to !in aggregate.orderedLinkIds.indices) return state.toResult()
        val ordered = aggregate.orderedLinkIds.toMutableList().apply {
            add(to, removeAt(from))
        }
        return commitLinkOrder(session, state, link.projectId, ordered, operationId)
    }

    @Synchronized
    override fun moveNoteItem(
        session: Session,
        noteId: String,
        delta: Int,
        operationId: String,
    ): BackgroundSyncResult {
        if (delta == 0) return activeState(session).toResult()
        val state = activeState(session).withProjectLinks()
        val note = state.projectCache.noteItems.single { it.id == noteId && it.deletedAt == null }
        require(state.projectCache.pages.single { it.project.id == note.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        val aggregate = state.projectCache.noteOrders.single { it.projectId == note.projectId }
        val from = aggregate.orderedNoteIds.indexOf(noteId)
        val to = from + delta
        if (from < 0 || to !in aggregate.orderedNoteIds.indices) return state.toResult()
        val ordered = aggregate.orderedNoteIds.toMutableList().apply { add(to, removeAt(from)) }
        val dependencies = buildList {
            addAll(state.projectCache.itemOperations.filter {
                it.projectId == note.projectId && it.type in NOTE_ORDER_DEPENDENCY_TYPES
            }.map { it.operationId })
            state.projectCache.interactionOperations.lastOrNull {
                it.type == InteractionMutationType.REORDER_NOTES && it.projectId == note.projectId
            }?.operationId?.let(::add)
        }.distinct()
        val timestamp = now()
        val operation = InteractionMutation(
            operationId = operationId,
            type = InteractionMutationType.REORDER_NOTES,
            aggregateId = aggregate.aggregateId ?: "notes:${note.projectId}",
            projectId = note.projectId,
            orderedClientIds = ordered,
            baseVersion = aggregate.version,
            dependsOnOperationIds = dependencies,
            createdAt = timestamp,
            contractVersion = "0.8",
        )
        return commitInteractionLocal(session, operation, state, state, null) { current ->
            current.copy(
                projectCache = current.projectCache.copy(
                    noteOrders = current.projectCache.noteOrders.filterNot { it.projectId == note.projectId } +
                        aggregate.copy(
                            orderedNoteIds = ordered,
                            version = aggregate.version + 1,
                            updatedAt = timestamp,
                        ),
                ),
            )
        }
    }

    @Synchronized
    override fun reorderActiveProjects(
        session: Session,
        orderedProjectIds: List<String>,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session).withProjectLinks()
        val activeIds = state.projectCache.activeProjects.map { it.id }
        require(orderedProjectIds.size == orderedProjectIds.distinct().size &&
            orderedProjectIds.toSet() == activeIds.toSet()) {
            "Custom order must contain every active Project exactly once"
        }
        if (orderedProjectIds == state.projectCache.projectOrder.orderedProjectIds) return state.toResult()
        val aggregate = state.projectCache.projectOrder
        val dependencies = buildList {
            addAll(state.projectCache.commands.filter {
                it.type in PROJECT_ORDER_DEPENDENCY_TYPES
            }.map { it.operationId })
            state.projectCache.interactionOperations.lastOrNull {
                it.type == InteractionMutationType.REORDER_PROJECTS
            }?.operationId?.let(::add)
        }.distinct()
        val timestamp = now()
        val operation = InteractionMutation(
            operationId = operationId,
            type = InteractionMutationType.REORDER_PROJECTS,
            aggregateId = aggregate.aggregateId ?: ACTIVE_PROJECT_ORDER_AGGREGATE,
            orderedClientIds = state.projectCache.projectClientIds(orderedProjectIds),
            baseVersion = aggregate.version,
            dependsOnOperationIds = dependencies,
            createdAt = timestamp,
        )
        return commitInteractionLocal(session, operation, state, state, null) { current ->
            current.copy(
                projectCache = current.projectCache.copy(
                    projectOrder = aggregate.copy(
                        orderedProjectIds = orderedProjectIds,
                        version = aggregate.version + 1,
                        updatedAt = timestamp,
                    ),
                ),
            )
        }
    }

    @Synchronized
    override fun deleteParking(
        session: Session,
        parkingId: String,
        operationId: String,
    ): BackgroundSyncResult {
        val state = activeState(session).withProjectLinks()
        val record = state.projectCache.parkingRecords.single { it.id == parkingId }
        require(state.projectCache.pages.single { it.project.id == record.projectId }.project.lifecycle == ProjectLifecycle.ACTIVE)
        if (state.projectCache.parkingTombstones.any { it.parkingId == parkingId }) return state.toResult()
        val timestamp = now()
        val dependencies = buildList {
            state.projectCache.commands.firstOrNull {
                it.type == ProjectMutationType.ADD_PARKING && it.clientResourceId == parkingId
            }?.operationId?.let(::add)
            state.projectCache.interactionOperations.lastOrNull {
                it.type == InteractionMutationType.DELETE_PARKING && it.targetId == parkingId
            }?.operationId?.let(::add)
        }.distinct()
        val operation = InteractionMutation(
            operationId = operationId,
            type = InteractionMutationType.DELETE_PARKING,
            aggregateId = parkingId,
            projectId = record.projectId,
            targetId = parkingId,
            baseVersion = record.rowVersion,
            dependsOnOperationIds = dependencies,
            createdAt = timestamp,
        )
        return commitInteractionLocal(session, operation, state, state, null) { current ->
            current.copy(
                projectCache = current.projectCache.copy(
                    parkingTombstones = current.projectCache.parkingTombstones + ParkingTombstone(
                        parkingId = parkingId,
                        projectId = record.projectId,
                        version = record.rowVersion + 1,
                        deletedAt = timestamp,
                        lastOperationId = operationId,
                    ),
                ).ensureInteractionState(),
            )
        }
    }

    @Synchronized
    override fun acknowledgeProjectItem(
        session: Session,
        acknowledgement: ProjectItemAcknowledgement,
    ): BackgroundSyncResult {
        return save(session, applyProjectItemAcknowledgement(activeState(session), acknowledgement)).toResult()
    }

    private fun applyProjectItemAcknowledgement(
        state: TenantSyncState,
        acknowledgement: ProjectItemAcknowledgement,
    ): TenantSyncState {
        val operationIndex = state.projectCache.itemOperations.indexOfFirst {
            it.operationId == acknowledgement.operationId
        }
        require(operationIndex >= 0) { "The acknowledged project item operation is not pending" }
        val operation = state.projectCache.itemOperations[operationIndex]
        require(operation.status == ProjectMutationStatus.PENDING) {
            "A conflicting project item operation cannot be acknowledged as successful"
        }
        require(operation.projectId == acknowledgement.projectId && operation.targetId == acknowledgement.itemId) {
            "Project item acknowledgement identity does not match the pending operation"
        }
        require(state.projectCache.itemOperations.take(operationIndex).none {
            it.targetId == operation.targetId
        }) { "Project item acknowledgements must follow dependency order" }

        val authoritativeVersion = when (acknowledgement) {
            is ProjectItemAcknowledgement.Note -> {
                require(operation.type in NOTE_ITEM_TYPES) { "A note acknowledgement cannot retire a link operation" }
                require(acknowledgement.item.rowVersion > 0) { "A note acknowledgement needs an authoritative version" }
                val author = acknowledgement.item.authoredByUserId
                require(author != null && author !in LOCAL_AUTHOR_SENTINELS) {
                    "A new note needs a Service-derived author and local sentinels cannot cross the boundary"
                }
                acknowledgement.item.rowVersion
            }
            is ProjectItemAcknowledgement.Link -> {
                require(operation.type in LINK_ITEM_TYPES) { "A link acknowledgement cannot retire a note operation" }
                require(acknowledgement.item.rowVersion > 0) { "A link acknowledgement needs an authoritative version" }
                acknowledgement.item.rowVersion
            }
        }
        val directDependents = state.projectCache.itemOperations.filter {
            it.dependsOnOperationId == operation.operationId
        }
        require(directDependents.size <= 1) { "A project item operation chain must be linear" }
        val remaining = state.projectCache.itemOperations.filterNot {
            it.operationId == operation.operationId
        }.map { pending ->
            if (pending.dependsOnOperationId == operation.operationId) {
                pending.copy(ifMatch = pending.ifMatch ?: authoritativeVersion, dependsOnOperationId = null)
            } else {
                pending
            }
        }
        return when (acknowledgement) {
            is ProjectItemAcknowledgement.Note -> state.copy(
                projectCache = state.projectCache.copy(
                    noteItems = state.projectCache.noteItems.filterNot { it.id == acknowledgement.item.id } +
                        acknowledgement.item,
                    itemOperations = remaining,
                    retiredItemOperationIds = (
                        state.projectCache.retiredItemOperationIds + operation.operationId
                        ).distinct(),
                ),
            )
            is ProjectItemAcknowledgement.Link -> state.copy(
                projectCache = state.projectCache.copy(
                    resourceLinks = state.projectCache.resourceLinks.filterNot { it.id == acknowledgement.item.id } +
                        acknowledgement.item,
                    itemOperations = remaining,
                    retiredItemOperationIds = (
                        state.projectCache.retiredItemOperationIds + operation.operationId
                        ).distinct(),
                ),
            )
        }.reapplyPendingItemProjection(operation.targetId)
    }

    @Synchronized
    override fun recordProjectItemDeliveryUncertain(
        session: Session,
        operationId: String,
    ): BackgroundSyncResult = updateProjectItemOperation(session, operationId) {
        it.copy(deliveryUncertain = true)
    }

    @Synchronized
    override fun recordProjectItemConflict(
        session: Session,
        operationId: String,
    ): BackgroundSyncResult = updateProjectItemOperation(session, operationId) {
        it.copy(status = ProjectMutationStatus.REQUIRES_RECONCILIATION, deliveryUncertain = false)
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
            state = drainProjectItemQueue(session, state)
            state = synchronizeChangesBestEffort(session, state)
            state = legacyReplayer.replay(session, state)
            state = refreshInvalidatedBestEffort(session, state)
            state = synchronizeInteractionAggregatesBestEffort(session, state)
            state = drainInteractionQueue(session, state)
            state = synchronizeNavigationAliasesBestEffort(session, state)
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
        if (activeTenantId == session.tenantId) {
            activeTenantId = null
            activeStateCache = null
        }
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
        var updated = projected.copy(
            projectCache = projected.projectCache.copy(commands = projected.projectCache.commands + command),
        ).withProjectLinks()
        if (command.type in PROJECT_ORDER_DEPENDENCY_TYPES) {
            updated = updated.withQueuedProjectOrderAfter(command)
            syncStore.saveInteractionMutation(
                session.tenantId,
                state,
                updated,
                projectMutationType = command.type,
            )
        } else {
            syncStore.saveProjectMutation(session.tenantId, state, updated, command.type)
        }
        activeStateCache = updated
        return updated.toResult()
    }

    private fun TenantSyncState.withQueuedProjectOrderAfter(command: ProjectMutation): TenantSyncState {
        val normalized = copy(projectCache = projectCache.ensureInteractionState())
        val previousAggregate = activeStateCache?.projectCache?.ensureInteractionState()?.projectOrder
            ?: normalized.projectCache.projectOrder
        val previousClientOrder = normalized.projectCache.interactionOperations.lastOrNull {
            it.type == InteractionMutationType.REORDER_PROJECTS
        }?.orderedClientIds ?: normalized.projectCache.projectClientIds(previousAggregate.orderedProjectIds)
        val targetClientId = normalized.projectCache.projectClientId(command.targetId)
        val ordered = when (command.type) {
            ProjectMutationType.CREATE_PROJECT,
            ProjectMutationType.REOPEN_PROJECT,
            -> previousAggregate.orderedProjectIds.filterNot { it == command.targetId } + command.targetId
            ProjectMutationType.ARCHIVE_PROJECT -> previousAggregate.orderedProjectIds - command.targetId
            else -> return normalized
        }
        val orderedClientIds = when (command.type) {
            ProjectMutationType.CREATE_PROJECT,
            ProjectMutationType.REOPEN_PROJECT,
            -> previousClientOrder.filterNot { it == targetClientId } + targetClientId
            ProjectMutationType.ARCHIVE_PROJECT -> previousClientOrder - targetClientId
            else -> error("Project order dependency type is not supported")
        }
        val timestamp = now()
        val orderOperationId = newOperationIdentity()
        val dependencies = buildList {
            add(command.operationId)
            normalized.projectCache.interactionOperations.lastOrNull {
                it.type == InteractionMutationType.REORDER_PROJECTS
            }?.operationId?.let(::add)
        }.distinct()
        return normalized.copy(
            projectCache = normalized.projectCache.copy(
                projectOrder = previousAggregate.copy(
                    orderedProjectIds = ordered,
                    version = previousAggregate.version + 1,
                    updatedAt = timestamp,
                ),
                interactionOperations = normalized.projectCache.interactionOperations + InteractionMutation(
                    operationId = orderOperationId,
                    type = InteractionMutationType.REORDER_PROJECTS,
                    aggregateId = previousAggregate.aggregateId ?: ACTIVE_PROJECT_ORDER_AGGREGATE,
                    orderedClientIds = orderedClientIds,
                    baseVersion = previousAggregate.version,
                    dependsOnOperationIds = dependencies,
                    createdAt = timestamp,
                ),
            ),
        )
    }

    private fun commitItemLocal(
        session: Session,
        operation: ProjectItemMutation,
        projection: (TenantSyncState) -> TenantSyncState,
    ): BackgroundSyncResult {
        val state = activeState(session).withProjectLinks()
        require(operation.operationId !in state.projectCache.retiredItemOperationIds) {
            "A retired project item operation identity cannot be reused"
        }
        val existing = state.projectCache.itemOperations.firstOrNull { it.operationId == operation.operationId }
        if (existing != null) {
            require(existing == operation) { "A project item operation identity cannot describe two requests" }
            return state.toResult()
        }
        val projected = projection(state)
        val updated = projected.copy(
            projectCache = projected.projectCache.copy(
                itemOperations = projected.projectCache.itemOperations + operation,
            ),
        ).withProjectLinks()
        syncStore.saveProjectItemMutation(session.tenantId, state, updated, operation.type)
        activeStateCache = updated
        return updated.toResult()
    }

    private fun commitLinkMutationLocal(
        session: Session,
        operation: ProjectItemMutation,
        projection: (TenantSyncState) -> TenantSyncState,
    ): BackgroundSyncResult {
        val state = activeState(session).withProjectLinks()
        require(operation.operationId !in state.projectCache.retiredItemOperationIds) {
            "A retired project item operation identity cannot be reused"
        }
        state.projectCache.itemOperations.firstOrNull { it.operationId == operation.operationId }?.let { existing ->
            require(existing == operation) { "A project item operation identity cannot describe two requests" }
            return state.toResult()
        }
        val localProjection = projection(state)
        val projected = localProjection.copy(
            projectCache = localProjection.projectCache.copy(
                itemOperations = localProjection.projectCache.itemOperations + operation,
            ),
        ).withProjectLinks()
        val order = projected.projectCache.linkOrders.single { it.projectId == operation.projectId }.orderedLinkIds
        return commitLinkOrder(
            session = session,
            state = state,
            projectId = operation.projectId,
            orderedLinkIds = order,
            operationId = newOperationIdentity(),
            requiredOperation = operation,
            projected = projected,
        )
    }

    private fun commitLinkOrder(
        session: Session,
        state: TenantSyncState,
        projectId: String,
        orderedLinkIds: List<String>,
        operationId: String,
        requiredOperation: ProjectItemMutation? = null,
        projected: TenantSyncState = state,
    ): BackgroundSyncResult {
        val aggregate = state.projectCache.linkOrders.firstOrNull { it.projectId == projectId }
            ?: LinkOrderState(projectId)
        val timestamp = now()
        val dependencies = buildList {
            requiredOperation?.operationId?.let(::add)
            addAll(projected.projectCache.itemOperations.filter {
                it.projectId == projectId && it.type in LINK_ORDER_DEPENDENCY_TYPES
            }.map { it.operationId })
            projected.projectCache.interactionOperations.lastOrNull {
                it.type == InteractionMutationType.REORDER_LINKS && it.projectId == projectId
            }?.operationId?.let(::add)
        }.distinct()
        val orderOperation = InteractionMutation(
            operationId = operationId,
            type = InteractionMutationType.REORDER_LINKS,
            aggregateId = aggregate.aggregateId ?: "links:$projectId",
            projectId = projectId,
            orderedClientIds = orderedLinkIds,
            baseVersion = aggregate.version,
            dependsOnOperationIds = dependencies,
            createdAt = timestamp,
        )
        return commitInteractionLocal(
            session,
            orderOperation,
            state,
            projected,
            requiredOperation?.type,
        ) { current ->
            val other = current.projectCache.linkOrders.filterNot { it.projectId == projectId }
            current.copy(
                projectCache = current.projectCache.copy(
                    linkOrders = other + aggregate.copy(
                        orderedLinkIds = orderedLinkIds,
                        version = aggregate.version + 1,
                        updatedAt = timestamp,
                    ),
                ),
            )
        }
    }

    private fun commitInteractionLocal(
        session: Session,
        operation: InteractionMutation,
        state: TenantSyncState,
        projected: TenantSyncState,
        itemMutationType: ProjectItemMutationType?,
        projection: (TenantSyncState) -> TenantSyncState,
    ): BackgroundSyncResult {
        require(operation.operationId !in state.projectCache.retiredInteractionOperationIds) {
            "A retired interaction operation identity cannot be reused"
        }
        state.projectCache.interactionOperations.firstOrNull { it.operationId == operation.operationId }?.let { existing ->
            require(existing == operation) { "An interaction operation identity cannot describe two requests" }
            return state.toResult()
        }
        val local = projection(projected)
        val updated = local.copy(
            projectCache = local.projectCache.copy(
                interactionOperations = local.projectCache.interactionOperations + operation,
            ).ensureInteractionState(),
        )
        syncStore.saveInteractionMutation(
            session.tenantId,
            state,
            updated,
            itemMutationType = itemMutationType,
        )
        activeStateCache = updated
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

    private fun drainProjectItemQueue(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        while (true) {
            val operation = state.projectCache.itemOperations.withIndex().firstOrNull { (index, candidate) ->
                candidate.status == ProjectMutationStatus.PENDING &&
                    state.projectCache.itemOperations.take(index).none { it.targetId == candidate.targetId } &&
                    candidate.dependsOnOperationId?.let { dependency ->
                        state.projectCache.itemOperations.none { it.operationId == dependency } &&
                            state.projectCache.commands.none { it.operationId == dependency }
                    } != false
            }?.value ?: return state
            if (
                operation.type in setOf(ProjectItemMutationType.CREATE_LINK, ProjectItemMutationType.EDIT_LINK) &&
                operation.linkUri?.let(ProjectResourceUri::canLaunch) == false
            ) {
                state = state.updateProjectItemOperation(operation.operationId) {
                    it.copy(status = ProjectMutationStatus.REQUIRES_RECONCILIATION, deliveryUncertain = false)
                }
                state = save(session, state)
                continue
            }
            try {
                val acknowledgement = sendProjectItemOperation(session, operation)
                state = applyProjectItemAcknowledgement(state, acknowledgement)
                state = save(session, state)
            } catch (error: Exception) {
                error.handleMembershipRevocation(session)
                if (error is ServiceException && error.status == 401) throw error
                if (error.isDeferredItemSync()) {
                    state = state.updateProjectItemOperation(operation.operationId) {
                        it.copy(deliveryUncertain = true)
                    }
                    return save(session, state)
                }
                state = reconcileProjectItemConflict(session, state, operation)
                state = save(session, state)
            }
        }
    }

    private fun drainInteractionQueue(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        while (true) {
            val operation = state.projectCache.interactionOperations.firstOrNull { candidate ->
                candidate.status == ProjectMutationStatus.PENDING &&
                    candidate.dependsOnOperationIds.none { dependency ->
                        state.projectCache.commands.any { it.operationId == dependency } ||
                            state.projectCache.itemOperations.any { it.operationId == dependency } ||
                            state.projectCache.interactionOperations.any { it.operationId == dependency }
                    }
            } ?: return state
            val prepared = try {
                prepareInteractionOperation(session, state, operation)
            } catch (error: Exception) {
                error.handleMembershipRevocation(session)
                if (!error.isDeferredInteractionSync()) throw error
                return state
            }
            state = prepared.state
            val sendable = prepared.operation ?: continue
            try {
                val acknowledgement = when (sendable.type) {
                    InteractionMutationType.REORDER_PROJECTS -> service.reorderProjects(session, sendable)
                    InteractionMutationType.REORDER_LINKS -> service.reorderLinks(session, sendable)
                    InteractionMutationType.REORDER_NOTES -> service.reorderNotes(session, sendable)
                    InteractionMutationType.DELETE_PARKING -> service.deleteParking(session, sendable)
                }
                state = applyInteractionAcknowledgement(state, acknowledgement)
                state = save(session, state)
            } catch (error: Exception) {
                error.handleMembershipRevocation(session)
                if (error is ServiceException && error.status == 401) throw error
                if (error.isDeferredInteractionSync()) {
                    state = state.updateInteractionOperation(sendable.operationId) {
                        it.copy(deliveryUncertain = true)
                    }
                    return save(session, state)
                }
                if (
                    error is ServiceException && error.status == 409 &&
                    sendable.type == InteractionMutationType.REORDER_NOTES
                ) {
                    val projectId = requireNotNull(sendable.projectId)
                    val authoritative = service.noteOrder(session, projectId)
                    state = hydrateNoteOrderMembers(session, state, authoritative)
                        .applyAuthoritativeOrder(authoritative, authoritative.operationId)
                    if (state.projectCache.interactionOperations.any { it.operationId == sendable.operationId }) {
                        state = state.updateInteractionOperation(sendable.operationId) {
                            it.copy(
                                aggregateId = authoritative.aggregateId,
                                baseVersion = authoritative.version,
                                orderedClientIds = it.orderedClientIds.normalizeMembers(
                                    authoritative.orderedClientIds,
                                ),
                                deliveryUncertain = false,
                                preparedForDelivery = false,
                            )
                        }
                    }
                    state = save(session, state)
                    continue
                }
                state = state.updateInteractionOperation(sendable.operationId) {
                    it.copy(status = ProjectMutationStatus.REQUIRES_RECONCILIATION)
                }
                return save(session, state)
            }
        }
    }

    private fun prepareInteractionOperation(
        session: Session,
        initial: TenantSyncState,
        operation: InteractionMutation,
    ): PreparedInteraction {
        if (operation.deliveryUncertain || operation.preparedForDelivery) {
            return PreparedInteraction(initial, operation)
        }
        var state = initial
        val prepared = when (operation.type) {
            InteractionMutationType.REORDER_PROJECTS -> {
                val authoritative = service.projectOrder(session)
                state = state.applyAuthoritativeOrder(authoritative, authoritative.operationId)
                if (state.projectCache.interactionOperations.none { it.operationId == operation.operationId }) {
                    return PreparedInteraction(save(session, state), null)
                }
                val ordered = state.projectCache.normalizeProjectClientOrder(operation.orderedClientIds, authoritative)
                operation.copy(
                    aggregateId = authoritative.aggregateId,
                    baseVersion = authoritative.version,
                    orderedClientIds = ordered,
                    preparedForDelivery = true,
                )
            }
            InteractionMutationType.REORDER_LINKS -> {
                val projectId = requireNotNull(operation.projectId)
                val authoritative = service.linkOrder(session, projectId)
                state = state.applyAuthoritativeOrder(authoritative, authoritative.operationId)
                if (state.projectCache.interactionOperations.none { it.operationId == operation.operationId }) {
                    return PreparedInteraction(save(session, state), null)
                }
                val ordered = operation.orderedClientIds.normalizeMembers(authoritative.orderedClientIds)
                operation.copy(
                    aggregateId = authoritative.aggregateId,
                    baseVersion = authoritative.version,
                    orderedClientIds = ordered,
                    preparedForDelivery = true,
                )
            }
            InteractionMutationType.REORDER_NOTES -> {
                val projectId = requireNotNull(operation.projectId)
                val authoritative = service.noteOrder(session, projectId)
                state = hydrateNoteOrderMembers(session, state, authoritative)
                    .applyAuthoritativeOrder(authoritative, authoritative.operationId)
                if (state.projectCache.interactionOperations.none { it.operationId == operation.operationId }) {
                    return PreparedInteraction(save(session, state), null)
                }
                val ordered = operation.orderedClientIds.normalizeMembers(authoritative.orderedClientIds)
                operation.copy(
                    aggregateId = authoritative.aggregateId,
                    baseVersion = authoritative.version,
                    orderedClientIds = ordered,
                    preparedForDelivery = true,
                )
            }
            InteractionMutationType.DELETE_PARKING -> {
                val projectId = requireNotNull(operation.projectId)
                state = state.applyAuthoritativeTombstones(
                    projectId,
                    service.parkingTombstones(session, projectId),
                )
                if (state.projectCache.interactionOperations.none { it.operationId == operation.operationId }) {
                    return PreparedInteraction(save(session, state), null)
                }
                val targetId = requireNotNull(operation.targetId)
                if (state.projectCache.parkingTombstones.any {
                        it.parkingId == targetId && it.lastOperationId != operation.operationId
                    }
                ) {
                    val reconciled = state.updateInteractionOperation(operation.operationId) {
                        it.copy(status = ProjectMutationStatus.REQUIRES_RECONCILIATION)
                    }
                    return PreparedInteraction(save(session, reconciled), null)
                }
                val refreshed = service.parking(session, projectId)
                state = state.withParkingHistory(projectId, refreshed)
                val authoritative = state.projectCache.parkingRecords.single { it.id == targetId }
                operation.copy(
                    aggregateId = targetId,
                    baseVersion = authoritative.rowVersion,
                    preparedForDelivery = true,
                )
            }
        }
        require(prepared.aggregateId.isUuid() && prepared.baseVersion > 0) {
            "Interaction operation is not bound to an authoritative aggregate"
        }
        state = state.updateInteractionOperation(operation.operationId) { prepared }
        state = save(session, state)
        return PreparedInteraction(state, prepared)
    }

    private fun applyInteractionAcknowledgement(
        state: TenantSyncState,
        acknowledgement: InteractionAcknowledgement,
    ): TenantSyncState = when (acknowledgement) {
        is InteractionAcknowledgement.Order -> state.applyAuthoritativeOrder(
            AuthoritativeOrder(
                aggregateId = acknowledgement.aggregateId,
                projectId = acknowledgement.projectId,
                orderedClientIds = acknowledgement.orderedClientIds,
                version = acknowledgement.version,
                operationId = acknowledgement.operationId,
                type = acknowledgement.type,
            ),
            acknowledgement.operationId,
        )
        is InteractionAcknowledgement.Parking -> {
            val pending = state.projectCache.interactionOperations.singleOrNull {
                it.operationId == acknowledgement.operationId &&
                    it.type == InteractionMutationType.DELETE_PARKING &&
                    it.projectId == acknowledgement.projectId &&
                    it.targetId == acknowledgement.targetId
            } ?: error("Service acknowledged an interaction operation that is not pending")
            val local = state.projectCache.parkingTombstones.firstOrNull {
                it.parkingId == acknowledgement.targetId
            }
            val tombstone = ParkingTombstone(
                parkingId = acknowledgement.targetId,
                projectId = acknowledgement.projectId,
                version = acknowledgement.version,
                deletedAt = acknowledgement.deletedAt ?: local?.deletedAt ?: pending.createdAt,
                lastOperationId = acknowledgement.operationId,
            )
            state.copy(
                projectCache = state.projectCache.copy(
                    parkingTombstones = state.projectCache.parkingTombstones.filterNot {
                        it.parkingId == acknowledgement.targetId
                    } + tombstone,
                    interactionOperations = state.projectCache.interactionOperations.filterNot {
                        it.operationId == acknowledgement.operationId
                    },
                    retiredInteractionOperationIds = (
                        state.projectCache.retiredInteractionOperationIds + acknowledgement.operationId
                        ).distinct(),
                ),
            ).reapplyPendingInteractionProjection()
        }
    }

    private fun synchronizeInteractionAggregatesBestEffort(
        session: Session,
        initial: TenantSyncState,
    ): TenantSyncState {
        var state = initial
        state = bestEffortInteractionRefresh(session, state) {
            val order = service.projectOrder(session)
            it.applyAuthoritativeOrder(order, order.operationId)
        }
        val projectIds = state.projectCache.activeProjects.map { it.id }
            .filterNot { it in state.projectCache.provisionalProjectIds }
            .distinct()
        projectIds.forEach { projectId ->
            state = bestEffortInteractionRefresh(session, state) {
                val order = service.linkOrder(session, projectId)
                it.applyAuthoritativeOrder(order, order.operationId)
            }
            state = bestEffortInteractionRefresh(session, state) {
                it.applyAuthoritativeTombstones(projectId, service.parkingTombstones(session, projectId))
            }
            state = bestEffortInteractionRefresh(session, state) {
                val order = service.noteOrder(session, projectId)
                hydrateNoteOrderMembers(session, it, order)
                    .applyAuthoritativeOrder(order, order.operationId)
            }
        }
        return state
    }

    private fun bestEffortInteractionRefresh(
        session: Session,
        state: TenantSyncState,
        refresh: (TenantSyncState) -> TenantSyncState,
    ): TenantSyncState = try {
        save(session, refresh(state))
    } catch (error: Exception) {
        error.handleMembershipRevocation(session)
        if (!error.isDeferredInteractionSync()) throw error
        state
    }

    private fun sendProjectItemOperation(
        session: Session,
        operation: ProjectItemMutation,
    ): ProjectItemAcknowledgement = when (operation.type) {
        ProjectItemMutationType.CREATE_NOTE -> service.createNote(session, operation)
        ProjectItemMutationType.EDIT_NOTE -> service.editNote(session, operation)
        ProjectItemMutationType.PIN_NOTE -> service.pinNote(session, operation)
        ProjectItemMutationType.DELETE_NOTE -> service.deleteNote(session, operation)
        ProjectItemMutationType.CREATE_LINK -> service.createLink(session, operation)
        ProjectItemMutationType.EDIT_LINK -> service.editLink(session, operation)
        ProjectItemMutationType.DELETE_LINK -> service.deleteLink(session, operation)
    }

    private fun reconcileProjectItemConflict(
        session: Session,
        initial: TenantSyncState,
        operation: ProjectItemMutation,
    ): TenantSyncState {
        val authoritative = try {
            when (operation.type) {
                in NOTE_ITEM_TYPES -> ItemAuthoritativeState.Note(
                    service.readNote(session, operation.projectId, operation.targetId),
                )
                in LINK_ITEM_TYPES -> ItemAuthoritativeState.Link(
                    service.readLink(session, operation.projectId, operation.targetId),
                )
                else -> error("Unknown project item operation ${operation.type}")
            }
        } catch (error: Exception) {
            error.handleMembershipRevocation(session)
            if (error is ServiceException && error.status == 401) throw error
            null
        }
        var state = when (authoritative) {
            is ItemAuthoritativeState.Note -> initial.copy(
                projectCache = initial.projectCache.copy(
                    noteItems = initial.projectCache.noteItems.filterNot { it.id == authoritative.value.id } +
                        authoritative.value,
                ),
            )
            is ItemAuthoritativeState.Link -> initial.copy(
                projectCache = initial.projectCache.copy(
                    resourceLinks = initial.projectCache.resourceLinks.filterNot { it.id == authoritative.value.id } +
                        authoritative.value,
                ),
            )
            null -> initial
        }
        state = state.updateProjectItemOperation(operation.operationId) {
            it.copy(status = ProjectMutationStatus.REQUIRES_RECONCILIATION, deliveryUncertain = false)
        }
        return state.reapplyPendingItemProjection(operation.targetId)
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
                page.events.forEach { event -> updated = observeEvent(session, updated, event) }
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

    private fun observeEvent(session: Session, state: TenantSyncState, event: ChangeEvent): TenantSyncState {
        var updated = legacyReplayer.observe(state, event)
        val projectId = event.payload["projectId"]?.toString()
        if (event.eventType == PROJECT_ORDER_EVENT && event.eventVersion == INTERACTION_EVENT_VERSION) {
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A Project-order event has no operation identity"
            }
            val aggregateId = requireNotNull(event.payload["aggregateId"]?.toString()) {
                "A Project-order event has no aggregate identity"
            }
            val authoritative = service.projectOrder(session)
            check(authoritative.aggregateId == aggregateId) {
                "Project-order event and authoritative aggregate disagree"
            }
            return updated.applyAuthoritativeOrder(authoritative, operationId)
        }
        if (event.eventType == LINK_ORDER_EVENT && event.eventVersion == INTERACTION_EVENT_VERSION) {
            val resolvedProjectId = requireNotNull(projectId) { "A Link-order event has no Project identity" }
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A Link-order event has no operation identity"
            }
            val aggregateId = requireNotNull(event.payload["aggregateId"]?.toString()) {
                "A Link-order event has no aggregate identity"
            }
            val authoritative = service.linkOrder(session, resolvedProjectId)
            check(authoritative.aggregateId == aggregateId) {
                "Link-order event and authoritative aggregate disagree"
            }
            return updated.applyAuthoritativeOrder(authoritative, operationId)
        }
        if (event.eventType == NOTE_ORDER_EVENT && event.eventVersion == INTERACTION_EVENT_VERSION) {
            val resolvedProjectId = requireNotNull(projectId) { "A Note-order event has no Project identity" }
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A Note-order event has no operation identity"
            }
            val aggregateId = requireNotNull(event.payload["aggregateId"]?.toString()) {
                "A Note-order event has no aggregate identity"
            }
            val authoritative = service.noteOrder(session, resolvedProjectId)
            check(authoritative.aggregateId == aggregateId) {
                "Note-order event and authoritative aggregate disagree"
            }
            return hydrateNoteOrderMembers(session, updated, authoritative)
                .applyAuthoritativeOrder(authoritative, operationId)
        }
        if (event.eventType == PARKING_DELETED_EVENT && event.eventVersion == INTERACTION_EVENT_VERSION) {
            val resolvedProjectId = requireNotNull(projectId) { "A Park-deleted event has no Project identity" }
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A Park-deleted event has no operation identity"
            }
            val parkingId = requireNotNull(event.payload["parkingRecordId"]?.toString()) {
                "A Park-deleted event has no Park identity"
            }
            val tombstones = service.parkingTombstones(session, resolvedProjectId)
            check(tombstones.any { it.parkingId == parkingId && it.lastOperationId == operationId }) {
                "Park-deleted event is absent from the authoritative tombstone aggregate"
            }
            return updated.applyAuthoritativeTombstones(resolvedProjectId, tombstones)
        }
        if (event.eventType in NOTE_ITEM_EVENTS) {
            val resolvedProjectId = requireNotNull(projectId) { "A note event has no project identity" }
            val itemId = requireNotNull(event.payload["itemId"]?.toString()) { "A note event has no item identity" }
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A note event has no operation identity"
            }
            val item = service.readNote(session, resolvedProjectId, itemId)
            val pending = updated.projectCache.itemOperations.any {
                it.operationId == operationId && it.projectId == resolvedProjectId && it.targetId == itemId
            }
            updated = if (pending) {
                applyProjectItemAcknowledgement(updated, ProjectItemAcknowledgement.Note(operationId, item))
            } else {
                updated.copy(
                    projectCache = updated.projectCache.copy(
                        noteItems = updated.projectCache.noteItems.filterNot { it.id == itemId } + item,
                    ),
                ).reapplyPendingItemProjection(itemId)
            }
            return updated
        }
        if (event.eventType in LINK_ITEM_EVENTS) {
            val resolvedProjectId = requireNotNull(projectId) { "A link event has no project identity" }
            val itemId = requireNotNull(event.payload["itemId"]?.toString()) { "A link event has no item identity" }
            val operationId = requireNotNull(event.payload["operationId"]?.toString()) {
                "A link event has no operation identity"
            }
            val item = service.readLink(session, resolvedProjectId, itemId)
            val pending = updated.projectCache.itemOperations.any {
                it.operationId == operationId && it.projectId == resolvedProjectId && it.targetId == itemId
            }
            updated = if (pending) {
                applyProjectItemAcknowledgement(updated, ProjectItemAcknowledgement.Link(operationId, item))
            } else {
                updated.copy(
                    projectCache = updated.projectCache.copy(
                        resourceLinks = updated.projectCache.resourceLinks.filterNot { it.id == itemId } + item,
                    ),
                ).reapplyPendingItemProjection(itemId)
            }
            return updated
        }
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

    private fun synchronizeNavigationAliasesBestEffort(
        session: Session,
        initial: TenantSyncState,
    ): TenantSyncState {
        var state = initial
        state.projectCache.projectLinks.toList().forEach { identity ->
            if (identity.projectId in state.projectCache.provisionalProjectIds) return@forEach
            val project = state.projectCache.projectSummary(identity.projectId) ?: return@forEach
            if (project.navigationKey == identity.key.encoded) return@forEach
            if (project.navigationKey != null) return@forEach
            try {
                val attached = service.attachNavigationKey(session, project.id, identity.key)
                check(attached == identity.key) { "Service acknowledged a different navigation key" }
                state = state.updateProject(project.id) { it.copy(navigationKey = identity.key.encoded) }
                state = save(session, state)
            } catch (error: Exception) {
                error.handleMembershipRevocation(session)
                if (error is ServiceException && error.status == 409) return@forEach
                if (!error.isDeferredItemSync()) throw error
            }
        }
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
        val createOperationIds = projectCache.commands.filter {
            it.type == ProjectMutationType.CREATE_PROJECT && it.targetId == provisionalId
        }.mapTo(mutableSetOf()) { it.operationId }
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
                noteItems = projectCache.noteItems.map { note ->
                    if (note.projectId == provisionalId) {
                        note.copy(projectId = serverId)
                    } else note
                }.distinctBy { it.id },
                resourceLinks = projectCache.resourceLinks.map { link ->
                    if (link.projectId == provisionalId) link.copy(projectId = serverId) else link
                },
                itemOperations = projectCache.itemOperations.map { operation ->
                    if (operation.projectId == provisionalId) {
                        operation.copy(
                            projectId = serverId,
                            dependsOnOperationId = operation.dependsOnOperationId
                                ?.takeUnless { it in createOperationIds },
                        )
                    } else operation
                },
                projectOrder = projectCache.projectOrder.copy(
                    orderedProjectIds = projectCache.projectOrder.orderedProjectIds.map {
                        if (it == provisionalId) serverId else it
                    }.distinct(),
                ),
                linkOrders = projectCache.linkOrders.map { order ->
                    if (order.projectId == provisionalId) order.copy(projectId = serverId) else order
                }.distinctBy { it.projectId },
                noteOrders = projectCache.noteOrders.map { order ->
                    if (order.projectId == provisionalId) order.copy(projectId = serverId) else order
                }.distinctBy { it.projectId },
                parkingTombstones = projectCache.parkingTombstones.map { tombstone ->
                    if (tombstone.projectId == provisionalId) tombstone.copy(projectId = serverId) else tombstone
                },
                interactionOperations = projectCache.interactionOperations.map { operation ->
                    operation.copy(
                        aggregateId = when {
                            operation.aggregateId == "links:$provisionalId" -> "links:$serverId"
                            operation.aggregateId == "notes:$provisionalId" -> "notes:$serverId"
                            else -> operation.aggregateId
                        },
                        projectId = if (operation.projectId == provisionalId) serverId else operation.projectId,
                        orderedClientIds = operation.orderedClientIds,
                        dependsOnOperationIds = operation.dependsOnOperationIds.filterNot { it in createOperationIds },
                    )
                },
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

    private fun TenantSyncState.applyAuthoritativeOrder(
        authoritative: AuthoritativeOrder,
        acknowledgedOperationId: String?,
    ): TenantSyncState {
        require(authoritative.aggregateId.isUuid() && authoritative.version > 0) {
            "Service returned an invalid order aggregate"
        }
        require(authoritative.orderedClientIds.size == authoritative.orderedClientIds.distinct().size) {
            "Service returned duplicate order members"
        }
        var cache = projectCache
        val matching = acknowledgedOperationId?.let { operationId ->
            cache.interactionOperations.firstOrNull { operation ->
                operation.operationId == operationId &&
                    operation.type == authoritative.type &&
                    operation.projectId == authoritative.projectId &&
                    operation.preparedForDelivery &&
                    operation.aggregateId == authoritative.aggregateId &&
                    operation.baseVersion + 1 == authoritative.version &&
                    operation.orderedClientIds == authoritative.orderedClientIds
            }
        }
        if (matching != null) {
            cache = cache.copy(
                interactionOperations = cache.interactionOperations.filterNot {
                    it.operationId == matching.operationId
                },
                retiredInteractionOperationIds = (
                    cache.retiredInteractionOperationIds + matching.operationId
                    ).distinct(),
            )
        }
        when (authoritative.type) {
            InteractionMutationType.REORDER_PROJECTS -> {
                require(authoritative.projectId == null) { "Project order unexpectedly names a Project" }
                val localIds = authoritative.orderedClientIds.mapNotNull { cache.projectIdForClientOrNull(it) }
                if (localIds.size != authoritative.orderedClientIds.size) {
                    return copy(
                        projectCache = cache.copy(
                            activeProjectsLoaded = false,
                            archivedProjectsLoaded = false,
                        ),
                    )
                }
                cache = cache.copy(
                    projectOrder = cache.projectOrder.copy(
                        orderedProjectIds = localIds,
                        version = authoritative.version,
                        updatedAt = now(),
                        aggregateId = authoritative.aggregateId,
                    ),
                )
            }
            InteractionMutationType.REORDER_LINKS -> {
                val projectId = requireNotNull(authoritative.projectId)
                val existing = cache.linkOrders.firstOrNull { it.projectId == projectId }
                    ?: LinkOrderState(projectId)
                cache = cache.copy(
                    linkOrders = cache.linkOrders.filterNot { it.projectId == projectId } + existing.copy(
                        orderedLinkIds = authoritative.orderedClientIds,
                        version = authoritative.version,
                        updatedAt = now(),
                        aggregateId = authoritative.aggregateId,
                    ),
                )
            }
            InteractionMutationType.REORDER_NOTES -> {
                val projectId = requireNotNull(authoritative.projectId)
                val knownIds = cache.noteItems.filter { it.projectId == projectId }.mapTo(mutableSetOf()) { it.id }
                require(authoritative.orderedClientIds.all { it in knownIds }) {
                    "Service Note order contains an unhydrated member"
                }
                val existing = cache.noteOrders.firstOrNull { it.projectId == projectId }
                    ?: NoteOrderState(projectId)
                cache = cache.copy(
                    noteOrders = cache.noteOrders.filterNot { it.projectId == projectId } + existing.copy(
                        orderedNoteIds = authoritative.orderedClientIds,
                        version = authoritative.version,
                        updatedAt = now(),
                        aggregateId = authoritative.aggregateId,
                    ),
                )
            }
            InteractionMutationType.DELETE_PARKING -> error("A Park deletion cannot be an order aggregate")
        }
        return copy(projectCache = cache).reapplyPendingInteractionProjection()
    }

    private fun hydrateNoteOrderMembers(
        session: Session,
        initial: TenantSyncState,
        authoritative: AuthoritativeOrder,
    ): TenantSyncState {
        if (authoritative.type != InteractionMutationType.REORDER_NOTES) return initial
        val projectId = requireNotNull(authoritative.projectId)
        var state = initial
        val knownIds = state.projectCache.noteItems.filter { it.projectId == projectId }
            .mapTo(mutableSetOf()) { it.id }
        authoritative.orderedClientIds.filterNot(knownIds::contains).forEach { noteId ->
            val note = service.readNote(session, projectId, noteId)
            check(note.projectId == projectId && note.deletedAt == null) {
                "Service returned an invalid live Note-order member"
            }
            state = state.copy(
                projectCache = state.projectCache.copy(
                    noteItems = state.projectCache.noteItems.filterNot { it.id == noteId } + note,
                ),
            )
            knownIds += noteId
        }
        return state
    }

    private fun TenantSyncState.applyAuthoritativeTombstones(
        projectId: String,
        authoritative: List<ParkingTombstone>,
    ): TenantSyncState {
        require(authoritative.all { it.projectId == projectId }) {
            "Service returned a Park tombstone from another Project"
        }
        val acknowledgedIds = authoritative.mapTo(mutableSetOf()) { it.lastOperationId }
        val retired = projectCache.interactionOperations.filter {
            it.type == InteractionMutationType.DELETE_PARKING &&
                it.projectId == projectId &&
                it.operationId in acknowledgedIds
        }.map { it.operationId }
        val cache = projectCache.copy(
            parkingTombstones = projectCache.parkingTombstones.filterNot { it.projectId == projectId } + authoritative,
            interactionOperations = projectCache.interactionOperations.filterNot { it.operationId in retired },
            retiredInteractionOperationIds = (projectCache.retiredInteractionOperationIds + retired).distinct(),
        )
        return copy(projectCache = cache).reapplyPendingInteractionProjection()
    }

    private fun TenantSyncState.reapplyPendingInteractionProjection(): TenantSyncState {
        var cache = projectCache.ensureInteractionState()
        cache.interactionOperations.forEach { operation ->
            when (operation.type) {
                InteractionMutationType.REORDER_PROJECTS -> {
                    val activeIds = cache.activeProjects.map { it.id }
                    val desired = operation.orderedClientIds.mapNotNull { cache.projectIdForClientOrNull(it) }
                        .filter { it in activeIds }
                        .distinct()
                    cache = cache.copy(
                        projectOrder = cache.projectOrder.copy(
                            orderedProjectIds = desired + activeIds.filterNot(desired::contains),
                        ),
                    )
                }
                InteractionMutationType.REORDER_LINKS -> {
                    val projectId = requireNotNull(operation.projectId)
                    val liveIds = cache.resourceLinks.filter {
                        it.projectId == projectId && it.deletedAt == null
                    }.map { it.id }
                    val desired = operation.orderedClientIds.filter { it in liveIds }.distinct()
                    val existing = cache.linkOrders.firstOrNull { it.projectId == projectId } ?: LinkOrderState(projectId)
                    cache = cache.copy(
                        linkOrders = cache.linkOrders.filterNot { it.projectId == projectId } + existing.copy(
                            orderedLinkIds = desired + liveIds.filterNot(desired::contains),
                        ),
                    )
                }
                InteractionMutationType.REORDER_NOTES -> {
                    val projectId = requireNotNull(operation.projectId)
                    val liveIds = cache.noteItems.filter {
                        it.projectId == projectId && it.deletedAt == null
                    }.map { it.id }
                    val desired = operation.orderedClientIds.filter { it in liveIds }.distinct()
                    val existing = cache.noteOrders.firstOrNull { it.projectId == projectId }
                        ?: NoteOrderState(projectId)
                    cache = cache.copy(
                        noteOrders = cache.noteOrders.filterNot { it.projectId == projectId } + existing.copy(
                            orderedNoteIds = desired + liveIds.filterNot(desired::contains),
                        ),
                    )
                }
                InteractionMutationType.DELETE_PARKING -> {
                    val parkingId = requireNotNull(operation.targetId)
                    if (cache.parkingTombstones.none { it.parkingId == parkingId }) {
                        cache = cache.copy(
                            parkingTombstones = cache.parkingTombstones + ParkingTombstone(
                                parkingId = parkingId,
                                projectId = requireNotNull(operation.projectId),
                                version = operation.baseVersion + 1,
                                deletedAt = operation.createdAt,
                                lastOperationId = operation.operationId,
                            ),
                        )
                    }
                }
            }
        }
        return copy(projectCache = cache.ensureInteractionState())
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
        val hidden = projectCache.parkingTombstones.mapTo(mutableSetOf()) { it.parkingId }
        val newest = records.filterNot { it.id in hidden }.newestFirstFor(record.projectId).firstOrNull()
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
        val remappedTombstones = projectCache.parkingTombstones.map { tombstone ->
            if (clientId != null && tombstone.parkingId == clientId) {
                tombstone.copy(parkingId = acknowledged.id, projectId = acknowledged.projectId)
            } else tombstone
        }.distinctBy { it.projectId to it.parkingId }
        val remappedOperations = projectCache.interactionOperations.map { operation ->
            if (
                clientId != null && operation.type == InteractionMutationType.DELETE_PARKING &&
                operation.targetId == clientId
            ) {
                operation.copy(
                    aggregateId = acknowledged.id,
                    projectId = acknowledged.projectId,
                    targetId = acknowledged.id,
                )
            } else operation
        }
        return copy(
            projectCache = projectCache.copy(
                parkingRecords = withoutProvisional,
                parkingTombstones = remappedTombstones,
                interactionOperations = remappedOperations,
            ),
        )
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
        val tombstonedRecords = projectCache.parkingRecords.filter { record ->
            record.projectId == projectId && projectCache.parkingTombstones.any { it.parkingId == record.id }
        }
        val mergedForProject = (remote + localPending + tombstonedRecords).distinctBy { it.clientId ?: it.id }
        val records = projectCache.parkingRecords.filterNot { it.projectId == projectId } + mergedForProject
        val hidden = projectCache.parkingTombstones.mapTo(mutableSetOf()) { it.parkingId }
        val newest = records.filterNot { it.id in hidden }.newestFirstFor(projectId).firstOrNull()
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

    private fun TenantSyncState.updateNote(
        noteId: String,
        update: (ProjectNoteItem) -> ProjectNoteItem,
    ): TenantSyncState = copy(
        projectCache = projectCache.copy(
            noteItems = projectCache.noteItems.map { if (it.id == noteId) update(it) else it },
        ),
    )

    private fun TenantSyncState.updateResourceLink(
        linkId: String,
        update: (ProjectResourceLink) -> ProjectResourceLink,
    ): TenantSyncState = copy(
        projectCache = projectCache.copy(
            resourceLinks = projectCache.resourceLinks.map { if (it.id == linkId) update(it) else it },
        ),
    )

    private fun updateProjectItemOperation(
        session: Session,
        operationId: String,
        update: (ProjectItemMutation) -> ProjectItemMutation,
    ): BackgroundSyncResult {
        val state = activeState(session)
        require(state.projectCache.itemOperations.any { it.operationId == operationId }) {
            "The project item operation is not pending"
        }
        val updated = state.updateProjectItemOperation(operationId, update)
        return save(session, updated).toResult()
    }

    private fun TenantSyncState.updateProjectItemOperation(
        operationId: String,
        update: (ProjectItemMutation) -> ProjectItemMutation,
    ): TenantSyncState = copy(
        projectCache = projectCache.copy(
            itemOperations = projectCache.itemOperations.map {
                if (it.operationId == operationId) update(it) else it
            },
        ),
    )

    private fun TenantSyncState.updateInteractionOperation(
        operationId: String,
        update: (InteractionMutation) -> InteractionMutation,
    ): TenantSyncState = copy(
        projectCache = projectCache.copy(
            interactionOperations = projectCache.interactionOperations.map {
                if (it.operationId == operationId) update(it) else it
            },
        ),
    )

    private fun TenantSyncState.reapplyPendingItemProjection(itemId: String): TenantSyncState {
        var projected = this
        projectCache.itemOperations.filter { it.targetId == itemId }.forEach { operation ->
            projected = when (operation.type) {
                ProjectItemMutationType.CREATE_NOTE -> projected.updateNote(itemId) {
                    it.copy(body = requireNotNull(operation.noteBody), updatedAt = operation.createdAt)
                }
                ProjectItemMutationType.EDIT_NOTE -> projected.updateNote(itemId) {
                    it.copy(body = requireNotNull(operation.noteBody), updatedAt = operation.createdAt)
                }
                ProjectItemMutationType.PIN_NOTE -> projected.updateNote(itemId) {
                    it.copy(pinned = requireNotNull(operation.pinned), updatedAt = operation.createdAt)
                }
                ProjectItemMutationType.DELETE_NOTE -> projected.updateNote(itemId) {
                    it.copy(updatedAt = operation.createdAt, deletedAt = operation.createdAt)
                }
                ProjectItemMutationType.CREATE_LINK -> projected.updateResourceLink(itemId) {
                    it.copy(
                        title = requireNotNull(operation.linkTitle),
                        uri = requireNotNull(operation.linkUri),
                        updatedAt = operation.createdAt,
                    )
                }
                ProjectItemMutationType.EDIT_LINK -> projected.updateResourceLink(itemId) {
                    it.copy(
                        title = requireNotNull(operation.linkTitle),
                        uri = requireNotNull(operation.linkUri),
                        updatedAt = operation.createdAt,
                    )
                }
                ProjectItemMutationType.DELETE_LINK -> projected.updateResourceLink(itemId) {
                    it.copy(updatedAt = operation.createdAt, deletedAt = operation.createdAt)
                }
            }
        }
        return projected
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

    private fun ProjectCacheState.latestItemDependency(projectId: String, itemId: String): String? =
        itemOperations.lastOrNull { it.targetId == itemId }?.operationId
            ?: commands.lastOrNull {
                it.type == ProjectMutationType.CREATE_PROJECT &&
                    (it.targetId == projectId || it.projectId == projectId)
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
        projectNoteItems = projectNoteItems.remapKeys(
            mapOf(noteItemDraftKey(from) to noteItemDraftKey(to)),
        ),
        projectResourceLinks = projectResourceLinks.remapKeys(
            mapOf(resourceLinkDraftKey(from) to resourceLinkDraftKey(to)),
        ),
        pendingMutations = pendingMutations.mapValues { (_, pending) ->
            if (pending.targetId == from) pending.copy(targetId = to) else pending
        }.mapKeys { (slot, _) -> slot.replace(":$from", ":$to") },
    )

    private fun <T> Map<String, T>.remapKey(from: String, to: String): Map<String, T> = buildMap {
        this@remapKey.forEach { (key, value) -> put(if (key == from) to else key, value) }
    }

    private fun <T> Map<String, T>.remapKeys(mapping: Map<String, String>): Map<String, T> = buildMap {
        this@remapKeys.forEach { (key, value) -> put(mapping[key] ?: key, value) }
    }

    private fun migrateProjectDrafts(session: Session) {
        val state = syncStore.loadSyncState(session.tenantId)
        if (state.projectDraftsMigrated) return
        val legacy = legacyDraftStore.load(session.tenantId)
        val allProjectPending = legacy.pendingMutations.filterKeys { it.startsWith("project-") }
        val projectPending = allProjectPending.filterValues { it.operation != ProjectMutationType.SAVE_NOTES.name }
        val migrated = state.projectDrafts.copy(
            projectName = legacy.projectName,
            projectNotes = emptyMap(),
            projectParking = legacy.projectParking,
            projectRenames = legacy.projectRenames,
            pendingMutations = state.projectDrafts.pendingMutations.filterValues {
                it.operation != ProjectMutationType.SAVE_NOTES.name
            } + projectPending,
        )
        save(session, state.copy(projectDrafts = migrated, projectDraftsMigrated = true))
        legacyDraftStore.save(
            session.tenantId,
            legacy.copy(
                projectName = "",
                projectNotes = emptyMap(),
                projectParking = emptyMap(),
                projectRenames = emptyMap(),
                pendingMutations = legacy.pendingMutations - allProjectPending.keys,
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
        val pendingAliases = projectCache.projectLinks.count { identity ->
            identity.projectId !in projectCache.provisionalProjectIds &&
                projectCache.projectSummary(identity.projectId)?.navigationKey != identity.key.encoded
        }
        val reconciliation = projectCache.commands.any {
            it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
        } || projectCache.itemOperations.any {
            it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
        } || projectCache.interactionOperations.any {
            it.status == ProjectMutationStatus.REQUIRES_RECONCILIATION
        } || commands.any {
            it.status.name == ProjectMutationStatus.REQUIRES_RECONCILIATION.name
        } || projectCache.projectLinks.any { identity ->
            val remoteKey = projectCache.projectSummary(identity.projectId)?.navigationKey
            remoteKey != null && remoteKey != identity.key.encoded
        }
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
            pendingItemOperations = projectCache.itemOperations.size,
            pendingNavigationAliases = pendingAliases,
            pendingInteractionOperations = projectCache.interactionOperations.size,
        )
    }

    private fun checkActive(session: Session) {
        check(activeTenantId == session.tenantId) { "No project state is active for this tenant" }
    }

    private fun activeState(session: Session): TenantSyncState {
        checkActive(session)
        return (activeStateCache ?: syncStore.loadSyncState(session.tenantId).also { activeStateCache = it }).also { state ->
            require(state.projectCache.projectCovers.all { it.tenantId == session.tenantId }) {
                "Encrypted cover metadata crosses a tenant boundary"
            }
        }
    }

    private fun save(session: Session, state: TenantSyncState): TenantSyncState {
        val stateWithLinks = state.withProjectLinks()
        val context = backgroundSaveContext.get()
        if (context == null) {
            val previous = activeState(session)
            syncStore.saveSyncState(session.tenantId, previous, stateWithLinks)
            activeStateCache = stateWithLinks
            return stateWithLinks
        }
        return synchronized(this) {
            checkActive(session)
            val current = activeState(session)
            val merged = mergeConcurrentLocalChanges(context.baseline, stateWithLinks, current).withProjectLinks()
            syncStore.saveSyncState(session.tenantId, current, merged)
            activeStateCache = merged
            context.baseline = merged
            merged
        }
    }

    private fun TenantSyncState.withProjectLinks(): TenantSyncState = copy(
        projectCache = projectCache.ensureProjectLinks(projectKeyFactory).ensureInteractionState(),
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
        val acknowledgedCreateOperations = mappings.associate { identity ->
            identity.provisionalId to baseline.projectCache.commands.filter {
                it.type == ProjectMutationType.CREATE_PROJECT && it.targetId == identity.provisionalId
            }.map { it.operationId }.toSet()
        }
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

        val baselineItemOperations = baseline.projectCache.itemOperations.associateBy { it.operationId }
        val currentItemOperations = current.projectCache.itemOperations.associateBy { it.operationId }
        val locallyRemovedItemOperations = baselineItemOperations.keys - currentItemOperations.keys
        val locallyChangedItemOperations = current.projectCache.itemOperations.filter { operation ->
            baselineItemOperations[operation.operationId]?.let { it != operation } == true
        }.associateBy { it.operationId }
        val locallyAddedItemOperations = current.projectCache.itemOperations.filter {
            it.operationId !in baselineItemOperations
        }
        val proposedRetiredItemOperations = proposed.projectCache.retiredItemOperationIds.toSet()
        fun remapItemOperation(operation: ProjectItemMutation): ProjectItemMutation =
            mappings.fold(operation) { mapped, identity ->
                if (mapped.projectId == identity.provisionalId) {
                    mapped.copy(
                        projectId = identity.serverId,
                        dependsOnOperationId = mapped.dependsOnOperationId?.takeUnless {
                            it in acknowledgedCreateOperations[identity.provisionalId].orEmpty()
                        },
                    )
                } else {
                    mapped
                }
            }
        val proposedItemIds = proposed.projectCache.itemOperations.map { it.operationId }.toSet()
        val itemOperationsBeforeHandoff = proposed.projectCache.itemOperations
            .filterNot { it.operationId in locallyRemovedItemOperations }
            .map { operation ->
                locallyChangedItemOperations[operation.operationId]?.let(::remapItemOperation) ?: operation
            } + locallyAddedItemOperations
            .filterNot { it.operationId in proposedItemIds || it.operationId in proposedRetiredItemOperations }
            .map(::remapItemOperation)
        val survivingItemOperationIds = itemOperationsBeforeHandoff.map { it.operationId }.toSet()
        val itemOperations = itemOperationsBeforeHandoff.map { operation ->
            val retiredDependency = operation.dependsOnOperationId?.takeIf {
                it !in survivingItemOperationIds && it in proposedRetiredItemOperations
            }
            if (retiredDependency == null) {
                operation
            } else {
                val authoritativeVersion = proposed.projectCache.noteItems
                    .firstOrNull { it.id == operation.targetId }?.rowVersion
                    ?: proposed.projectCache.resourceLinks
                        .firstOrNull { it.id == operation.targetId }?.rowVersion
                require(authoritativeVersion != null && authoritativeVersion > 0) {
                    "An acknowledged item operation must hand its authoritative version to a concurrent dependent"
                }
                operation.copy(
                    ifMatch = operation.ifMatch ?: authoritativeVersion,
                    dependsOnOperationId = null,
                )
            }
        }

        val locallyCreatedNoteIds = locallyAddedItemOperations.filter {
            it.type == ProjectItemMutationType.CREATE_NOTE
        }.map { it.targetId }.toSet()
        val locallyCreatedLinkIds = locallyAddedItemOperations.filter {
            it.type == ProjectItemMutationType.CREATE_LINK
        }.map { it.targetId }.toSet()
        val noteItems = proposed.projectCache.noteItems + current.projectCache.noteItems.filter { local ->
            local.id in locallyCreatedNoteIds && proposed.projectCache.noteItems.none { it.id == local.id }
        }.map { note ->
            mappings.fold(note) { mapped, identity ->
                if (mapped.projectId == identity.provisionalId) mapped.copy(projectId = identity.serverId) else mapped
            }
        }
        val resourceLinks = proposed.projectCache.resourceLinks + current.projectCache.resourceLinks.filter { local ->
            local.id in locallyCreatedLinkIds && proposed.projectCache.resourceLinks.none { it.id == local.id }
        }.map { link ->
            mappings.fold(link) { mapped, identity ->
                if (mapped.projectId == identity.provisionalId) mapped.copy(projectId = identity.serverId) else mapped
            }
        }

        val parkingIdentityMappings = proposed.projectCache.parkingRecords.mapNotNull { record ->
            record.clientId?.takeIf { it != record.id }?.let { it to record.id }
        }.toMap()
        fun remapInteractionOperation(operation: InteractionMutation): InteractionMutation {
            val projectMapped = mappings.fold(operation) { mapped, identity ->
                mapped.copy(
                    aggregateId = if (mapped.aggregateId == "links:${identity.provisionalId}") {
                        "links:${identity.serverId}"
                    } else mapped.aggregateId,
                    projectId = if (mapped.projectId == identity.provisionalId) identity.serverId else mapped.projectId,
                    dependsOnOperationIds = mapped.dependsOnOperationIds.filterNot {
                        it in acknowledgedCreateOperations[identity.provisionalId].orEmpty()
                    },
                )
            }
            val serverParkingId = projectMapped.targetId?.let(parkingIdentityMappings::get)
            return if (serverParkingId != null && projectMapped.type == InteractionMutationType.DELETE_PARKING) {
                projectMapped.copy(aggregateId = serverParkingId, targetId = serverParkingId)
            } else projectMapped
        }
        val baselineInteractionOperations = baseline.projectCache.interactionOperations.associateBy { it.operationId }
        val currentInteractionOperations = current.projectCache.interactionOperations.associateBy { it.operationId }
        val locallyRemovedInteractionIds = baselineInteractionOperations.keys - currentInteractionOperations.keys
        val locallyChangedInteractionOperations = current.projectCache.interactionOperations.filter {
            baselineInteractionOperations[it.operationId]?.let { baselineOperation -> baselineOperation != it } == true
        }.associateBy { it.operationId }
        val locallyAddedInteractionOperations = current.projectCache.interactionOperations.filter {
            it.operationId !in baselineInteractionOperations
        }
        val proposedRetiredInteractionIds = proposed.projectCache.retiredInteractionOperationIds.toSet()
        val proposedInteractionIds = proposed.projectCache.interactionOperations.mapTo(mutableSetOf()) { it.operationId }
        val interactionOperations = proposed.projectCache.interactionOperations
            .filterNot { it.operationId in locallyRemovedInteractionIds }
            .map { operation ->
                locallyChangedInteractionOperations[operation.operationId]
                    ?.takeUnless { it.operationId in proposedRetiredInteractionIds }
                    ?.let(::remapInteractionOperation)
                    ?: operation
            } + locallyAddedInteractionOperations
            .filterNot { it.operationId in proposedInteractionIds || it.operationId in proposedRetiredInteractionIds }
            .map(::remapInteractionOperation)

        val baselineTombstones = baseline.projectCache.parkingTombstones.associateBy { it.projectId to it.parkingId }
        val localTombstoneDeltas = current.projectCache.parkingTombstones.filter { tombstone ->
            baselineTombstones[tombstone.projectId to tombstone.parkingId] != tombstone
        }.map { tombstone ->
            val mappedProjectId = mappings.firstOrNull { it.provisionalId == tombstone.projectId }?.serverId
                ?: tombstone.projectId
            val mappedParkingId = parkingIdentityMappings[tombstone.parkingId] ?: tombstone.parkingId
            tombstone.copy(projectId = mappedProjectId, parkingId = mappedParkingId)
        }
        val parkingTombstones = (
            proposed.projectCache.parkingTombstones + localTombstoneDeltas.filter { local ->
                proposed.projectCache.parkingTombstones.none {
                    it.projectId == local.projectId && it.parkingId == local.parkingId
                }
            }
            ).distinctBy { it.projectId to it.parkingId }

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
                projectCovers = if (current.projectCache.projectCovers != baseline.projectCache.projectCovers) {
                    current.projectCache.projectCovers.map { cover ->
                        mappings.fold(cover) { mapped, identity ->
                            if (mapped.projectId == identity.provisionalId) {
                                mapped.copy(projectId = identity.serverId)
                            } else {
                                mapped
                            }
                        }
                    }
                } else {
                    proposed.projectCache.projectCovers
                },
                noteItems = noteItems.distinctBy { it.id },
                resourceLinks = resourceLinks.distinctBy { it.id },
                projectOrder = proposed.projectCache.projectOrder,
                linkOrders = proposed.projectCache.linkOrders,
                noteOrders = proposed.projectCache.noteOrders,
                parkingTombstones = parkingTombstones,
                interactionOperations = interactionOperations,
                retiredInteractionOperationIds = (
                    proposed.projectCache.retiredInteractionOperationIds +
                        (current.projectCache.retiredInteractionOperationIds -
                            baseline.projectCache.retiredInteractionOperationIds.toSet())
                    ).distinct(),
                itemOperations = itemOperations,
                retiredItemOperationIds = (
                    proposed.projectCache.retiredItemOperationIds +
                        (current.projectCache.retiredItemOperationIds -
                            baseline.projectCache.retiredItemOperationIds.toSet())
                    ).distinct(),
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
        merged.projectCache.itemOperations.map { it.targetId }.distinct().forEach { itemId ->
            merged = merged.reapplyPendingItemProjection(itemId)
        }
        return merged.reapplyPendingInteractionProjection()
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
        val mediaFailure = runCatching { coverMediaStore.eraseTenant(tenantId) }.exceptionOrNull()
        val syncFailure = runCatching { syncStore.clearSyncState(tenantId) }.exceptionOrNull()
        val draftFailure = runCatching { legacyDraftStore.clear(tenantId) }.exceptionOrNull()
        (mediaFailure ?: syncFailure ?: draftFailure)?.let { failure ->
            listOfNotNull(mediaFailure, syncFailure, draftFailure)
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

    private fun Exception.isDeferredSync() = this is IOException ||
        this is IncompatibleServiceApiException ||
        this is ServiceException && (status >= 500 || status in setOf(404, 405, 426))

    private fun Exception.isDeferredItemSync() = this is IOException ||
        this is IncompatibleServiceApiException ||
        this is UnsupportedOperationException ||
        this is ServiceException && (status >= 500 || status in setOf(405, 426))

    private fun Exception.isDeferredInteractionSync() = this is IOException ||
        this is IncompatibleServiceApiException ||
        this is UnsupportedOperationException ||
        this is ServiceException && (status >= 500 || status in setOf(404, 405, 426))

    private fun Exception.isRecognizedOlderApi(command: ProjectMutation) =
        this is ServiceException && status == 422 && command.contractVersion == "0.4" &&
            command.type in setOf(ProjectMutationType.CREATE_PROJECT, ProjectMutationType.ADD_PARKING)

    private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> = filterNot { id(it) == id(value) } + value

    private fun List<LocalBlockRecord>.replaceBlock(value: LocalBlockRecord): List<LocalBlockRecord> =
        map { if (it.clientBlockId == value.clientBlockId) value else it }

    private fun ProjectCacheState.resolveProjectId(projectId: String): String =
        projectIdMappings.firstOrNull { it.provisionalId == projectId }?.serverId ?: projectId

    private fun ProjectCacheState.projectSummary(projectId: String): ProjectSummary? =
        pages.firstOrNull { it.project.id == projectId }?.project
            ?: activeProjects.firstOrNull { it.id == projectId }
            ?: archivedProjects.firstOrNull { it.id == projectId }

    private fun ProjectCacheState.projectClientId(projectId: String): String =
        projectSummary(projectId)?.clientId ?: projectId

    private fun ProjectCacheState.projectClientIds(projectIds: List<String>): List<String> {
        val clientIdByProjectId = buildMap {
            archivedProjects.forEach { put(it.id, it.clientId) }
            activeProjects.forEach { put(it.id, it.clientId) }
            pages.forEach { put(it.project.id, it.project.clientId) }
        }
        return projectIds.map { projectId -> clientIdByProjectId[projectId] ?: projectId }
    }

    private fun ProjectCacheState.projectIdForClient(clientId: String): String =
        requireNotNull(projectIdForClientOrNull(clientId)) {
            "Authoritative Project order references an unknown client identity"
        }

    private fun ProjectCacheState.projectIdForClientOrNull(clientId: String): String? =
        (activeProjects + archivedProjects + pages.map { it.project })
            .distinctBy { it.id }
            .firstOrNull { it.clientId == clientId || it.id == clientId }
            ?.id

    private fun ProjectCacheState.normalizeProjectClientOrder(
        requested: List<String>,
        authoritative: AuthoritativeOrder,
    ): List<String> {
        val clientIdByAlias = buildMap {
            (archivedProjects + activeProjects + pages.map { it.project }).forEach { project ->
                put(project.id, project.clientId)
                put(project.clientId, project.clientId)
            }
        }
        val requestedClientIds = requested.map { value -> clientIdByAlias[value] ?: value }
        return requestedClientIds.normalizeMembers(authoritative.orderedClientIds)
    }

    private fun List<String>.normalizeMembers(authoritative: List<String>): List<String> {
        val live = authoritative.toSet()
        val retained = filter { it in live }.distinct()
        return retained + authoritative.filterNot(retained::contains)
    }

    private fun String.isUuid(): Boolean = runCatching { java.util.UUID.fromString(this) }.isSuccess

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

    private data class PreparedInteraction(
        val state: TenantSyncState,
        val operation: InteractionMutation?,
    )

    private sealed interface ProjectOutcome {
        data class Project(val value: ProjectSummary) : ProjectOutcome
        data class Notes(val value: ProjectNotes) : ProjectOutcome
        data class Parking(val value: ProjectParking) : ProjectOutcome
        data class Binding(val value: BlockBinding) : ProjectOutcome
        data class Unbound(val blockId: String) : ProjectOutcome
    }

    private sealed interface ItemAuthoritativeState {
        data class Note(val value: ProjectNoteItem) : ItemAuthoritativeState
        data class Link(val value: ProjectResourceLink) : ItemAuthoritativeState
    }

    private companion object {
        const val CHANGE_PAGE_SIZE = 100
        const val MAX_BLOCK_LABEL = 200
        const val MAX_NOTE_BODY = 100_000
        const val MAX_LINK_TITLE = 200
        const val ACTIVE_PROJECT_ORDER_AGGREGATE = "active-projects"
        const val INTERACTION_EVENT_VERSION = "0.1"
        const val PROJECT_ORDER_EVENT = "project.order_changed"
        const val LINK_ORDER_EVENT = "project.link_order_changed"
        const val NOTE_ORDER_EVENT = "project.note_order_changed"
        const val PARKING_DELETED_EVENT = "project.parking_deleted"
        val PENDING_TAG_STATES = setOf(LocalBlockState.AWAITING_WRITE, LocalBlockState.WRITE_UNCERTAIN)
        val NOTE_ITEM_TYPES = setOf(
            ProjectItemMutationType.CREATE_NOTE,
            ProjectItemMutationType.EDIT_NOTE,
            ProjectItemMutationType.PIN_NOTE,
            ProjectItemMutationType.DELETE_NOTE,
        )
        val LINK_ITEM_TYPES = setOf(
            ProjectItemMutationType.CREATE_LINK,
            ProjectItemMutationType.EDIT_LINK,
            ProjectItemMutationType.DELETE_LINK,
        )
        val LINK_ORDER_DEPENDENCY_TYPES = setOf(
            ProjectItemMutationType.CREATE_LINK,
            ProjectItemMutationType.DELETE_LINK,
        )
        val NOTE_ORDER_DEPENDENCY_TYPES = setOf(
            ProjectItemMutationType.CREATE_NOTE,
            ProjectItemMutationType.EDIT_NOTE,
            ProjectItemMutationType.DELETE_NOTE,
        )
        val PROJECT_ORDER_DEPENDENCY_TYPES = setOf(
            ProjectMutationType.CREATE_PROJECT,
            ProjectMutationType.ARCHIVE_PROJECT,
            ProjectMutationType.REOPEN_PROJECT,
        )
        val LOCAL_AUTHOR_SENTINELS = setOf("local")
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
        val NOTE_ITEM_EVENTS = setOf(
            "project.note_created",
            "project.note_edited",
            "project.note_pinned",
            "project.note_deleted",
        )
        val LINK_ITEM_EVENTS = setOf(
            "project.link_created",
            "project.link_edited",
            "project.link_deleted",
        )
        val BLOCK_EVENTS = setOf("block.bound", "block.unbound")
    }
}
