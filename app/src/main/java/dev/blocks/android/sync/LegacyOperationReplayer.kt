package dev.blocks.android.sync

import dev.blocks.android.api.ServiceException
import dev.blocks.android.mvp.BlocksRepository
import dev.blocks.android.mvp.ChangeEvent
import dev.blocks.android.mvp.DraftStore
import dev.blocks.android.mvp.OfflineOperation
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.ParkingRecord
import dev.blocks.android.mvp.PickupResult
import dev.blocks.android.mvp.QueuedCommand
import dev.blocks.android.mvp.QueuedCommandStatus
import dev.blocks.android.mvp.Reentry
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.SyncStateStore
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.mvp.TransitionConflictException
import dev.blocks.android.mvp.WorkSession
import dev.blocks.android.mvp.WorkSessionState
import java.io.IOException
import java.math.BigInteger

/**
 * Restricted API 0.2 compatibility path. It can only replay commands already present in
 * the signed pilot database; it has no enqueue surface and therefore cannot create new
 * session-model work after upgrade.
 */
interface LegacyQueueReplay {
    fun observe(state: TenantSyncState, event: ChangeEvent): TenantSyncState
    fun replay(session: Session, initial: TenantSyncState): TenantSyncState
}

object NoOpLegacyQueueReplay : LegacyQueueReplay {
    override fun observe(state: TenantSyncState, event: ChangeEvent) = state
    override fun replay(session: Session, initial: TenantSyncState) = initial
}

class LegacyOperationReplayer(
    private val delegate: BlocksRepository,
    private val draftStore: DraftStore,
    private val syncStore: SyncStateStore,
) : LegacyQueueReplay {
    override fun observe(state: TenantSyncState, event: ChangeEvent): TenantSyncState {
        if (!event.eventType.startsWith(LEGACY_EVENT_PREFIX)) return state
        val commands = state.commands.map { command ->
            if (event.isEvidenceFor(command)) command.copy(remoteEventObserved = true) else command
        }
        return if (commands == state.commands) state else state.copy(commands = commands)
    }

    override fun replay(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = reconcile(session, initial)
        while (true) {
            val command = state.commands.firstOrNull() ?: return state
            if (command.status == QueuedCommandStatus.REQUIRES_RECONCILIATION) return state
            try {
                when (val result = send(session, command)) {
                    is LegacySend.Applied -> {
                        state = state.copy(reentry = result.reentry, reentryLoaded = true)
                        state = retire(session, state, command)
                    }
                    is LegacySend.Diverged -> {
                        state = state.copy(reentry = result.reentry, reentryLoaded = true)
                        state = resolveSatisfied(session, state)
                        if (state.commands.any { it.idempotencyKey == command.idempotencyKey }) {
                            return reconcileRequired(
                                session,
                                state,
                                command,
                                "Another legacy session is authoritative; the saved operation is retained.",
                            )
                        }
                    }
                }
            } catch (conflict: TransitionConflictException) {
                state = reconcile(session, state)
                if (state.commands.any { it.idempotencyKey == command.idempotencyKey }) {
                    return reconcileRequired(session, state, command, conflict.message.orEmpty())
                }
            } catch (error: Exception) {
                if (error is ServiceException && error.status == 403) throw error
                if (error.isRetryable()) {
                    state = state.copy(
                        commands = state.commands.map {
                            if (it.idempotencyKey == command.idempotencyKey) {
                                it.copy(deliveryUncertain = true)
                            } else {
                                it
                            }
                        },
                    )
                    save(session, state)
                    return reconcile(session, state)
                }
                return reconcileRequired(session, state, command, error.message.orEmpty())
            }
        }
    }

    private fun reconcile(session: Session, initial: TenantSyncState): TenantSyncState {
        if (initial.commands.isEmpty()) return initial
        return try {
            val state = initial.copy(reentry = delegate.current(session), reentryLoaded = true)
            resolveSatisfied(session, state).also { save(session, it) }
        } catch (error: Exception) {
            if (error is ServiceException && error.status == 403) throw error
            if (!error.isRetryable()) throw error
            initial
        }
    }

    private fun send(session: Session, command: QueuedCommand): LegacySend = when (command.operation) {
        OfflineOperation.PICKUP -> when (
            val result = delegate.pickup(session, requireNotNull(command.pickup), command.idempotencyKey)
        ) {
            is PickupResult.Started -> LegacySend.Applied(result.reentry)
            is PickupResult.ExistingSession -> LegacySend.Diverged(result.reentry)
        }
        OfflineOperation.PARK -> LegacySend.Applied(
            delegate.park(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.PARK_AND_CLOSE -> LegacySend.Applied(
            delegate.closeWithContinuation(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.CLOSE_EXISTING_CONTINUATION -> LegacySend.Applied(
            delegate.closeOnExistingContinuation(
                session,
                command.toWorkSession(WorkSessionState.PARKED),
                requireNotNull(command.parking),
                command.idempotencyKey,
            ),
        )
        OfflineOperation.CLOSE_WITH_OUTCOME -> LegacySend.Applied(
            delegate.closeWithOutcome(
                session,
                command.toWorkSession(WorkSessionState.ACTIVE),
                requireNotNull(command.outcomeTier),
                command.idempotencyKey,
            ),
        )
    }

    private fun resolveSatisfied(session: Session, initial: TenantSyncState): TenantSyncState {
        var state = initial
        while (true) {
            val command = state.commands.firstOrNull() ?: return state
            if (!command.isSatisfiedBy(state.reentry)) return state
            state = retire(session, state, command)
        }
    }

    private fun retire(session: Session, state: TenantSyncState, command: QueuedCommand): TenantSyncState {
        val updated = state.copy(
            commands = state.commands.filterNot { it.idempotencyKey == command.idempotencyKey },
            retiredIdempotencyKeys = (state.retiredIdempotencyKeys + command.idempotencyKey).distinct(),
        )
        save(session, updated)
        retireDraftIdentity(session, command)
        return updated
    }

    private fun reconcileRequired(
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
        )
        save(session, updated)
        return updated
    }

    private fun retireDraftIdentity(session: Session, command: QueuedCommand) {
        val drafts = draftStore.load(session.tenantId)
        val slot = command.operation.draftSlot()
        if (drafts.pendingMutations[slot]?.idempotencyKey != command.idempotencyKey) return
        draftStore.save(
            session.tenantId,
            drafts.copy(
                pickups = if (command.operation == OfflineOperation.PICKUP) {
                    drafts.pickups - command.projectId
                } else {
                    drafts.pickups
                },
                parking = if (
                    command.operation == OfflineOperation.PARK ||
                    command.operation == OfflineOperation.PARK_AND_CLOSE
                ) {
                    ParkingDraft()
                } else {
                    drafts.parking
                },
                pendingMutations = drafts.pendingMutations - slot,
            ),
        )
    }

    private fun save(session: Session, state: TenantSyncState) = syncStore.saveSyncState(session.tenantId, state)

    private fun QueuedCommand.toWorkSession(state: WorkSessionState): WorkSession {
        val persisted = requireNotNull(workSession)
        require(persisted.id == targetId && persisted.projectId == projectId)
        require(persisted.rowVersion == ifMatch)
        return persisted.copy(state = state)
    }

    private fun QueuedCommand.isSatisfiedBy(reentry: Reentry?): Boolean = when (operation) {
        OfflineOperation.PICKUP -> (deliveryUncertain || remoteEventObserved) && run {
            val authoritative = reentry?.workSession
            val request = pickup
            authoritative?.id != null &&
                authoritative.id != baselineSessionId &&
                authoritative.projectId == targetId &&
                request != null &&
                authoritative.coreOutcome == request.coreOutcome &&
                authoritative.fullOutcome == request.fullOutcome &&
                authoritative.polishOutcome == request.polishOutcome
        }
        OfflineOperation.PARK -> (deliveryUncertain || remoteEventObserved) && run {
            val authoritative = reentry?.workSession
            authoritative?.id == targetId &&
                authoritative.rowVersion > requireNotNull(ifMatch) &&
                reentry.parking.sameAnswersAs(parking)
        }
        OfflineOperation.PARK_AND_CLOSE -> (deliveryUncertain || remoteEventObserved) && run {
            val authoritative = reentry?.workSession
            authoritative?.id == targetId &&
                authoritative.state == WorkSessionState.CLOSED &&
                authoritative.rowVersion > requireNotNull(ifMatch) &&
                reentry.parking.sameAnswersAs(parking)
        }
        OfflineOperation.CLOSE_EXISTING_CONTINUATION -> (deliveryUncertain || remoteEventObserved) && run {
            val authoritative = reentry?.workSession
            authoritative?.id == targetId &&
                authoritative.state == WorkSessionState.CLOSED &&
                authoritative.rowVersion > requireNotNull(ifMatch) &&
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
            OfflineOperation.PARK -> eventType == "session.parked" && sessionId == command.targetId
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

    private fun OfflineOperation.draftSlot() = when (this) {
        OfflineOperation.PICKUP -> "pickup"
        OfflineOperation.PARK, OfflineOperation.PARK_AND_CLOSE -> "parking"
        OfflineOperation.CLOSE_EXISTING_CONTINUATION, OfflineOperation.CLOSE_WITH_OUTCOME -> "close"
    }

    private fun Exception.isRetryable() = this is IOException || this is ServiceException && status >= 500

    private sealed interface LegacySend {
        data class Applied(val reentry: Reentry) : LegacySend
        data class Diverged(val reentry: Reentry) : LegacySend
    }

    private companion object {
        const val LEGACY_EVENT_PREFIX = "session."
    }
}
