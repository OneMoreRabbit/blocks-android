package dev.blocks.android.sync

import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.ChangePage
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.FormDrafts
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.OutcomeTier
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.PickupRequest
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.Project
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.QueuedCommandStatus
import dev.blocks.android.mvp.QueuedOfflineException
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncResult
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.SyncStatus
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.TransitionConflictException
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import java.io.IOException
import java.math.BigInteger

class DurableBlocksRepository(
    private val delegate: BlocksRepository,
    private val draftStore: DraftStore,
    private val syncStore: SyncStateStore,
) : BlocksRepository {
    private var activeTenantId: String? = null

    @Synchronized
    override fun login(identityToken: String): Session = delegate.login(identityToken).also { session ->
        activeTenantId = session.tenantId
    }

    @Synchronized
    override fun projects(session: Session): List<Project> {
        checkActive(session)
        val state = syncStore.loadSyncState(session.tenantId)
        return try {
            delegate.projects(session).also { projects ->
                save(session, state.copy(projects = projects, projectsLoaded = true))
            }
        } catch (error: Exception) {
            if (error.isRetryable() && state.projectsLoaded) state.projects else throw error
        }
    }

    @Synchronized
    override fun createProject(session: Session, name: String): Project {
        checkActive(session)
        return delegate.createProject(session, name)
    }

    @Synchronized
    override fun current(session: Session): Reentry {
        checkActive(session)
        val state = syncStore.loadSyncState(session.tenantId)
        return try {
            val reentry = delegate.current(session)
            resolveSatisfied(
                session,
                state.copy(reentry = reentry, reentryLoaded = true),
                discoveredRemotely = true,
            ).also { save(session, it) }.reentry ?: EMPTY_REENTRY
        } catch (error: Exception) {
            if (error.isRetryable() && state.reentryLoaded) state.reentry ?: EMPTY_REENTRY else throw error
        }
    }

    @Synchronized
    override fun pickup(
        session: Session,
        request: PickupRequest,
        idempotencyKey: String,
    ): PickupResult {
        val localState = activeState(session)
        val command = QueuedCommand(
            operation = OfflineOperation.PICKUP,
            targetId = request.projectId,
            projectId = request.projectId,
            idempotencyKey = idempotencyKey,
            pickup = request,
            afterCursor = localState.changeCursor,
            baselineSessionId = localState.reentry?.workSession?.id,
        )
        val final = enqueueAndDrain(session, command)
        val remaining = final.commands.firstOrNull { it.idempotencyKey == idempotencyKey }
        if (remaining?.status == QueuedCommandStatus.REQUIRES_RECONCILIATION) {
            return PickupResult.ExistingSession(final.reentry ?: EMPTY_REENTRY)
        }
        if (remaining != null) throw queuedOffline(remaining)
        return PickupResult.Started(final.reentry ?: EMPTY_REENTRY)
    }

    @Synchronized
    override fun park(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry = executeTransition(
        session,
        QueuedCommand(
            operation = OfflineOperation.PARK,
            targetId = workSession.id,
            projectId = workSession.projectId,
            idempotencyKey = idempotencyKey,
            ifMatch = workSession.rowVersion,
            workSession = workSession,
            parking = record,
            afterCursor = activeState(session).changeCursor,
        ),
    )

    @Synchronized
    override fun resume(session: Session, workSession: WorkSession): Reentry {
        checkActive(session)
        return try {
            delegate.resume(session, workSession).also { cacheReentry(session, it) }
        } catch (error: Exception) {
            if (!error.isRetryable()) throw error
            val reconciled = reconcileBestEffort(session, activeState(session))
            val authoritative = reconciled.reentry
            if (
                authoritative?.workSession?.id == workSession.id &&
                authoritative.workSession.state == WorkSessionState.ACTIVE &&
                authoritative.workSession.rowVersion > workSession.rowVersion
            ) {
                authoritative
            } else {
                throw QueuedOfflineException(
                    "Resume could not be confirmed. Latest saved state is retained for reconciliation.",
                    error,
                )
            }
        }
    }

    @Synchronized
    override fun closeWithContinuation(
        session: Session,
        workSession: WorkSession,
        record: ParkingRecord,
        idempotencyKey: String,
    ): Reentry = executeTransition(
        session,
        QueuedCommand(
            operation = OfflineOperation.PARK_AND_CLOSE,
            targetId = workSession.id,
            projectId = workSession.projectId,
            idempotencyKey = idempotencyKey,
            ifMatch = workSession.rowVersion,
            workSession = workSession,
            parking = record,
            afterCursor = activeState(session).changeCursor,
        ),
    )

    @Synchronized
    override fun closeOnExistingContinuation(
        session: Session,
        workSession: WorkSession,
        parking: ParkingRecord,
        idempotencyKey: String,
    ): Reentry = executeTransition(
        session,
        QueuedCommand(
            operation = OfflineOperation.CLOSE_EXISTING_CONTINUATION,
            targetId = workSession.id,
            projectId = workSession.projectId,
            idempotencyKey = idempotencyKey,
            ifMatch = workSession.rowVersion,
            workSession = workSession,
            parking = parking,
            afterCursor = activeState(session).changeCursor,
        ),
    )

    @Synchronized
    override fun closeWithOutcome(
        session: Session,
        workSession: WorkSession,
        outcomeTier: OutcomeTier,
        idempotencyKey: String,
    ): Reentry = executeTransition(
        session,
        QueuedCommand(
            operation = OfflineOperation.CLOSE_WITH_OUTCOME,
            targetId = workSession.id,
            projectId = workSession.projectId,
            idempotencyKey = idempotencyKey,
            ifMatch = workSession.rowVersion,
            workSession = workSession,
            outcomeTier = outcomeTier,
            afterCursor = activeState(session).changeCursor,
        ),
    )

    @Synchronized
    override fun changes(session: Session, cursor: String?, limit: Int): ChangePage {
        checkActive(session)
        return delegate.changes(session, cursor, limit)
    }

    @Synchronized
    override fun synchronize(session: Session): SyncResult {
        checkActive(session)
        var state = migrateOrphanedIdentities(session, syncStore.loadSyncState(session.tenantId))
        state = reconcileBestEffort(session, state)
        state = drainQueue(session, state)
        state = reconcileBestEffort(session, state)
        save(session, state)
        return SyncResult(
            reentry = state.reentry ?: EMPTY_REENTRY,
            status = state.toStatus(),
        )
    }

    @Synchronized
    override fun clearLocalState(session: Session) {
        syncStore.clearSyncState(session.tenantId)
        if (activeTenantId == session.tenantId) activeTenantId = null
    }

    @Synchronized
    override fun resolveReconciliation(session: Session): SyncResult {
        checkActive(session)
        val state = syncStore.loadSyncState(session.tenantId)
        val command = state.commands.firstOrNull {
            it.status == QueuedCommandStatus.REQUIRES_RECONCILIATION
        } ?: return synchronize(session)
        val updated = state.copy(
            commands = state.commands.filterNot { it.idempotencyKey == command.idempotencyKey },
            retiredIdempotencyKeys = (
                state.retiredIdempotencyKeys + command.idempotencyKey
                ).distinct(),
            notice = "Authoritative state accepted; the local ${command.operation.description()} draft was preserved.",
        )
        save(session, updated)
        retireDraftIdentity(session, command, clearContent = false)
        return synchronize(session)
    }

    private fun executeTransition(session: Session, command: QueuedCommand): Reentry {
        val final = enqueueAndDrain(session, command)
        val remaining = final.commands.firstOrNull { it.idempotencyKey == command.idempotencyKey }
        if (remaining?.status == QueuedCommandStatus.REQUIRES_RECONCILIATION) {
            throw TransitionConflictException(
                remaining.failureMessage ?: "The saved request conflicts with authoritative state.",
            )
        }
        if (remaining != null) throw queuedOffline(remaining)
        return final.reentry ?: EMPTY_REENTRY
    }

    private fun enqueueAndDrain(session: Session, command: QueuedCommand): TenantSyncState {
        checkActive(session)
        var state = syncStore.loadSyncState(session.tenantId)
        if (command.idempotencyKey in state.retiredIdempotencyKeys) {
            retireDraftIdentity(session, command)
            return state
        }
        val existing = state.commands.firstOrNull { it.idempotencyKey == command.idempotencyKey }
        if (existing == null) {
            state = state.copy(commands = state.commands + command)
            save(session, state)
        } else {
            require(existing.sameRequestAs(command)) {
                "An idempotency key cannot identify two different local commands"
            }
        }
        return drainQueue(session, state)
    }

    private fun drainQueue(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = resolveSatisfied(session, initial, discoveredRemotely = true)
        while (true) {
            val command = state.commands.firstOrNull() ?: return state
            if (command.status == QueuedCommandStatus.REQUIRES_RECONCILIATION) return state
            try {
                when (val outcome = sendQueued(session, command)) {
                    is SendOutcome.Applied -> {
                        state = state.copy(reentry = outcome.reentry, reentryLoaded = true)
                        state = retireCommand(session, state, command, notice = null)
                    }
                    is SendOutcome.Diverged -> {
                        state = state.copy(reentry = outcome.reentry, reentryLoaded = true)
                        state = resolveSatisfied(session, state, discoveredRemotely = true)
                        if (state.commands.any { it.idempotencyKey == command.idempotencyKey }) {
                            state = markForReconciliation(session, state, command, outcome.message)
                            return state
                        }
                    }
                }
            } catch (conflict: TransitionConflictException) {
                state = reconcileBestEffort(session, state)
                if (state.commands.any { it.idempotencyKey == command.idempotencyKey }) {
                    state = markForReconciliation(session, state, command, conflict.message.orEmpty())
                    return state
                }
            } catch (error: Exception) {
                if (error.isRetryable()) {
                    state = markDeliveryUncertain(session, state, command)
                    state = reconcileBestEffort(session, state)
                    return state
                }
                if (error is ServiceException) {
                    return markForReconciliation(session, state, command, error.message.orEmpty())
                }
                throw error
            }
        }
    }

    private fun sendQueued(session: Session, command: QueuedCommand): SendOutcome = when (command.operation) {
        OfflineOperation.PICKUP -> when (
            val result = delegate.pickup(
                session,
                requireNotNull(command.pickup),
                command.idempotencyKey,
            )
        ) {
            is PickupResult.Started -> SendOutcome.Applied(result.reentry)
            is PickupResult.ExistingSession -> SendOutcome.Diverged(
                result.reentry,
                "Another session is authoritative; the pickup intent is retained.",
            )
        }
        OfflineOperation.PARK -> SendOutcome.Applied(
            delegate.park(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.PARK_AND_CLOSE -> SendOutcome.Applied(
            delegate.closeWithContinuation(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.CLOSE_EXISTING_CONTINUATION -> SendOutcome.Applied(
            delegate.closeOnExistingContinuation(
                session,
                command.toWorkSession(WorkSessionState.PARKED),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.CLOSE_WITH_OUTCOME -> SendOutcome.Applied(
            delegate.closeWithOutcome(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.outcomeTier),
                command.idempotencyKey,
            ),
        )
    }

    private fun reconcileBestEffort(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        try {
            val reentry = delegate.current(session)
            state = state.copy(reentry = reentry, reentryLoaded = true)
            state = resolveSatisfied(session, state, discoveredRemotely = true)
            save(session, state)
        } catch (error: Exception) {
            if (!error.isRetryable()) throw error
        }
        try {
            state = synchronizeChangeFeed(session, state)
        } catch (error: Exception) {
            if (!error.isRetryable()) throw error
            state = activeState(session)
        }
        return resolveSatisfied(session, state, discoveredRemotely = true)
    }

    private fun synchronizeChangeFeed(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        do {
            val requestedCursor = state.changeCursor
            val page = delegate.changes(session, requestedCursor, CHANGE_PAGE_SIZE)
            page.validateOrderAfter(requestedCursor)
            var updated = state
            page.events.forEach { event ->
                updated = observeEvent(updated, event)
                updated = resolveSatisfied(session, updated, discoveredRemotely = true)
            }
            val advancedCursor = page.nextCursor ?: requestedCursor
            if (page.hasMore || page.events.isNotEmpty()) {
                require(advancedCursor != null && advancedCursor != requestedCursor) {
                    "Change feed did not advance its cursor"
                }
            }
            state = updated.copy(changeCursor = advancedCursor)
            save(session, state)
        } while (page.hasMore)
        return state
    }

    private fun observeEvent(state: TenantSyncState, event: ChangeEvent): TenantSyncState {
        val commands = state.commands.map { command ->
            if (event.isEvidenceFor(command)) command.copy(remoteEventObserved = true) else command
        }
        return if (commands == state.commands) state else state.copy(commands = commands)
    }

    private fun resolveSatisfied(
        session: Session,
        initial: TenantSyncState,
        discoveredRemotely: Boolean,
    ): TenantSyncState {
        var state = initial
        while (true) {
            val command = state.commands.firstOrNull() ?: return state
            if (!command.isSatisfiedBy(state.reentry)) return state
            state = retireCommand(
                session,
                state,
                command,
                notice = if (discoveredRemotely) {
                    "A saved ${command.operation.description()} request was confirmed by authoritative state."
                } else {
                    null
                },
            )
        }
    }

    private fun retireCommand(
        session: Session,
        state: TenantSyncState,
        command: QueuedCommand,
        notice: String?,
    ): TenantSyncState {
        val updated = state.copy(
            commands = state.commands.filterNot { it.idempotencyKey == command.idempotencyKey },
            retiredIdempotencyKeys = (
                state.retiredIdempotencyKeys + command.idempotencyKey
                ).distinct(),
            notice = notice ?: state.notice,
        )
        save(session, updated)
        retireDraftIdentity(session, command)
        return updated
    }

    private fun retireDraftIdentity(
        session: Session,
        command: QueuedCommand,
        clearContent: Boolean = true,
    ) {
        val drafts = draftStore.load(session.tenantId)
        val slot = command.operation.draftSlot()
        if (drafts.pendingMutations[slot]?.idempotencyKey != command.idempotencyKey) return
        val updated = drafts.copy(
            pickups = if (clearContent && command.operation == OfflineOperation.PICKUP) {
                drafts.pickups - command.projectId
            } else {
                drafts.pickups
            },
            parking = if (
                clearContent &&
                (
                    command.operation == OfflineOperation.PARK ||
                        command.operation == OfflineOperation.PARK_AND_CLOSE
                    )
            ) {
                ParkingDraft()
            } else {
                drafts.parking
            },
            pendingMutations = drafts.pendingMutations - slot,
        )
        draftStore.save(session.tenantId, updated)
    }

    private fun migrateOrphanedIdentities(session: Session, initial: TenantSyncState): TenantSyncState {
        val drafts = draftStore.load(session.tenantId)
        val represented = initial.commands.mapTo(mutableSetOf()) { it.idempotencyKey }
        represented += initial.retiredIdempotencyKeys
        val orphans = drafts.pendingMutations.filterValues { it.idempotencyKey !in represented }
        val alreadyRetired = drafts.pendingMutations.filterValues {
            it.idempotencyKey in initial.retiredIdempotencyKeys
        }
        val removableSlots = orphans.keys + alreadyRetired.keys
        if (removableSlots.isEmpty()) return initial

        draftStore.save(
            session.tenantId,
            drafts.copy(pendingMutations = drafts.pendingMutations - removableSlots),
        )
        val migrated = initial.copy(
            retiredIdempotencyKeys = (
                initial.retiredIdempotencyKeys + orphans.values.map { it.idempotencyKey }
                ).distinct(),
            notice = if (orphans.isNotEmpty()) {
                "A pre-queue pending request needs review. Its form was preserved and its old retry key retired."
            } else {
                initial.notice
            },
        )
        save(session, migrated)
        return migrated
    }

    private fun markForReconciliation(
        session: Session,
        state: TenantSyncState,
        command: QueuedCommand,
        message: String,
    ): TenantSyncState {
        val updated = state.copy(
            commands = state.commands.map {
                if (it.idempotencyKey == command.idempotencyKey) {
                    it.copy(
                        status = QueuedCommandStatus.REQUIRES_RECONCILIATION,
                        failureMessage = message,
                    )
                } else {
                    it
                }
            },
            notice = "A saved ${command.operation.description()} request needs reconciliation.",
        )
        save(session, updated)
        return updated
    }

    private fun markDeliveryUncertain(
        session: Session,
        state: TenantSyncState,
        command: QueuedCommand,
    ): TenantSyncState {
        val updated = state.copy(
            commands = state.commands.map {
                if (it.idempotencyKey == command.idempotencyKey) {
                    it.copy(deliveryUncertain = true)
                } else {
                    it
                }
            },
        )
        save(session, updated)
        return updated
    }

    private fun cacheReentry(session: Session, reentry: Reentry) {
        val state = syncStore.loadSyncState(session.tenantId)
        save(session, state.copy(reentry = reentry, reentryLoaded = true))
    }

    private fun save(session: Session, state: TenantSyncState) {
        syncStore.saveSyncState(session.tenantId, state)
    }

    private fun checkActive(session: Session) {
        check(activeTenantId == session.tenantId) { "No local state is active for this tenant" }
    }

    private fun activeState(session: Session): TenantSyncState {
        checkActive(session)
        return syncStore.loadSyncState(session.tenantId)
    }

    private fun TenantSyncState.toStatus(): SyncStatus {
        val reconciliation = commands.any { it.status == QueuedCommandStatus.REQUIRES_RECONCILIATION }
        val message = notice ?: when {
            reconciliation -> "A saved request needs reconciliation with authoritative state."
            commands.isNotEmpty() -> "${commands.size} saved request(s) are waiting for ordered replay."
            else -> null
        }
        return SyncStatus(commands.size, reconciliation, message)
    }

    private fun QueuedCommand.toWorkSession(state: WorkSessionState): WorkSession {
        val persisted = requireNotNull(workSession)
        require(persisted.id == targetId && persisted.projectId == projectId)
        require(persisted.rowVersion == ifMatch)
        return persisted.copy(state = state)
    }

    private fun QueuedCommand.sameRequestAs(other: QueuedCommand) = copy(
        status = QueuedCommandStatus.PENDING,
        deliveryUncertain = false,
        remoteEventObserved = false,
        failureMessage = null,
    ) == other.copy(
        status = QueuedCommandStatus.PENDING,
        deliveryUncertain = false,
        remoteEventObserved = false,
        failureMessage = null,
    )

    private fun QueuedCommand.isSatisfiedBy(reentry: Reentry?): Boolean = when (operation) {
        OfflineOperation.PICKUP -> (deliveryUncertain || remoteEventObserved) && run {
            val session = reentry?.workSession
            val request = pickup
            session?.id != null &&
                session.id != baselineSessionId &&
                session.projectId == targetId &&
                request != null &&
                session.coreOutcome == request.coreOutcome &&
                session.fullOutcome == request.fullOutcome &&
                session.polishOutcome == request.polishOutcome
        }
        OfflineOperation.PARK -> (deliveryUncertain || remoteEventObserved) && run {
            val session = reentry?.workSession
            session?.id == targetId &&
                session.rowVersion > requireNotNull(ifMatch) &&
                reentry.parking.sameAnswersAs(parking)
        }
        OfflineOperation.PARK_AND_CLOSE -> (deliveryUncertain || remoteEventObserved) && run {
            val session = reentry?.workSession
            session?.id == targetId &&
                session.state == WorkSessionState.CLOSED &&
                session.rowVersion > requireNotNull(ifMatch) &&
                reentry.parking.sameAnswersAs(parking)
        }
        OfflineOperation.CLOSE_EXISTING_CONTINUATION ->
            (deliveryUncertain || remoteEventObserved) && run {
                val session = reentry?.workSession
                session?.id == targetId &&
                    session.state == WorkSessionState.CLOSED &&
                    session.rowVersion > requireNotNull(ifMatch) &&
                    reentry.parking?.id == parking?.id
            }
        OfflineOperation.CLOSE_WITH_OUTCOME -> remoteEventObserved
    }

    private fun ParkingRecord?.sameAnswersAs(other: ParkingRecord?): Boolean =
        this != null && other != null &&
            location == other.location &&
            nextAction == other.nextAction &&
            mustNotForget == other.mustNotForget

    private fun ChangeEvent.isEvidenceFor(command: QueuedCommand): Boolean {
        if (!eventId.isAfter(command.afterCursor)) return false
        val sessionId = payload["sessionId"]?.toString()
        return when (command.operation) {
            OfflineOperation.PICKUP ->
                eventType == "session.started" && payload["projectId"]?.toString() == command.targetId
            OfflineOperation.PARK ->
                eventType == "session.parked" && sessionId == command.targetId
            OfflineOperation.PARK_AND_CLOSE ->
                (eventType == "session.parked" || eventType == "session.closed") && sessionId == command.targetId
            OfflineOperation.CLOSE_EXISTING_CONTINUATION ->
                eventType == "session.closed" && sessionId == command.targetId
            OfflineOperation.CLOSE_WITH_OUTCOME ->
                eventType == "session.closed" &&
                    sessionId == command.targetId &&
                    payload["outcomeTier"]?.toString() == command.outcomeTier?.wireValue
        }
    }

    private fun String.isAfter(cursor: String?): Boolean = runCatching {
        cursor == null || BigInteger(this) > BigInteger(cursor)
    }.getOrDefault(false)

    private fun ChangePage.validateOrderAfter(cursor: String?) {
        var previous = cursor?.let(::BigInteger) ?: BigInteger.valueOf(-1)
        events.forEach { event ->
            val current = runCatching { BigInteger(event.eventId) }
                .getOrElse { throw IllegalStateException("Change event id is not numeric") }
            require(current > previous) { "Change feed events are not strictly ordered" }
            previous = current
        }
        nextCursor?.let { next ->
            require(BigInteger(next) >= previous) { "Change feed cursor precedes its events" }
        }
    }

    private fun OfflineOperation.draftSlot() = when (this) {
        OfflineOperation.PICKUP -> "pickup"
        OfflineOperation.PARK, OfflineOperation.PARK_AND_CLOSE -> "parking"
        OfflineOperation.CLOSE_EXISTING_CONTINUATION, OfflineOperation.CLOSE_WITH_OUTCOME -> "close"
    }

    private fun OfflineOperation.description() = name.lowercase().replace('_', ' ')

    private fun Exception.isRetryable() = this is IOException || this is ServiceException && status >= 500

    private fun queuedOffline(command: QueuedCommand) = QueuedOfflineException(
        "The ${command.operation.description()} request is saved securely and will retry in order.",
    )

    private sealed interface SendOutcome {
        data class Applied(val reentry: Reentry) : SendOutcome
        data class Diverged(val reentry: Reentry, val message: String) : SendOutcome
    }

    private companion object {
        const val CHANGE_PAGE_SIZE = 100
        val EMPTY_REENTRY = Reentry(null, null)
    }
}
