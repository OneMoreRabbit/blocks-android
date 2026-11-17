package dev.blocks.android.follow

import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.AccountIdentity
import java.util.UUID

/** Local-only candidate. No Follow transport exists in the active API contract. */
internal data class FollowAccount(val tenantId: String, val identity: AccountIdentity)

internal enum class FollowKind { EXPLICIT_BUTTON, AUTOMATIC_NFC }

internal enum class FollowDispatchClass { IMMEDIATE, DEFERRED }

internal enum class FollowOpenOrigin { FRESH_NFC, QR, APP_LINK, DASHBOARD, RESTORE }

internal object FollowTriggerPolicy {
    fun automaticSelectionAllowed(
        origin: FollowOpenOrigin,
        settingEnabled: Boolean,
        resolvedLocally: Boolean,
        serviceAcceptedBindingAvailable: Boolean,
    ): Boolean = origin == FollowOpenOrigin.FRESH_NFC && settingEnabled && resolvedLocally &&
        serviceAcceptedBindingAvailable
}

internal data class FollowCandidate(
    val sourceId: String,
    val sequence: Long,
    val projectId: String,
    val kind: FollowKind,
    val dispatchClass: FollowDispatchClass,
    val observedRevision: Long?,
    val createdElapsedMs: Long,
    val bootIdentity: String,
) {
    fun ageMs(nowElapsedMs: Long, currentBootIdentity: String): Long? =
        if (currentBootIdentity == bootIdentity && nowElapsedMs >= createdElapsedMs) {
            nowElapsedMs - createdElapsedMs
        } else null
}

internal data class FollowLocalState(
    val sourceId: String,
    val nextSequence: Long,
    val candidate: FollowCandidate?,
)

internal interface FollowLocalStateStore {
    fun load(account: FollowAccount): FollowLocalState?
    fun save(account: FollowAccount, state: FollowLocalState)
    fun eraseTenant(tenantId: String)
}

/** Persist the source and newest unsent candidate together before any future dispatch. */
internal class FollowLocalIntentRecorder(
    private val store: FollowLocalStateStore,
    private val acceptedAccount: () -> AcceptedTenantContinuity?,
    private val newSourceId: () -> String = { UUID.randomUUID().toString() },
) {
    @Synchronized
    fun record(
        account: FollowAccount,
        projectId: String,
        kind: FollowKind,
        promptTransportAvailable: Boolean,
        lastObservedRevision: Long?,
        elapsedMs: Long,
        bootIdentity: String,
    ): FollowCandidate? {
        requireAccepted(account)
        require(projectId.isNotBlank() && elapsedMs >= 0 && bootIdentity.isNotBlank())
        require(lastObservedRevision == null || lastObservedRevision >= 0)
        val current = store.load(account) ?: FollowLocalState(newSourceId(), 1, null)
        require(current.nextSequence in 1 until Long.MAX_VALUE)
        val intentClass = if (promptTransportAvailable) {
            FollowDispatchClass.IMMEDIATE
        } else {
            FollowDispatchClass.DEFERRED
        }
        // A deferred action without an action-time baseline cannot be sent later.
        // It still replaces a previous unsent selection and consumes a sequence.
        val candidate = if (intentClass == FollowDispatchClass.DEFERRED && lastObservedRevision == null) {
            null
        } else {
            FollowCandidate(
                sourceId = current.sourceId,
                sequence = current.nextSequence,
                projectId = projectId,
                kind = kind,
                dispatchClass = intentClass,
                observedRevision = if (intentClass == FollowDispatchClass.DEFERRED) lastObservedRevision else null,
                createdElapsedMs = elapsedMs,
                bootIdentity = bootIdentity,
            )
        }
        store.save(account, current.copy(nextSequence = current.nextSequence + 1, candidate = candidate))
        return candidate
    }

    /** Fail closed until a reliable same-boot marker is implemented and tested. */
    @Synchronized
    fun discardPendingAfterProcessStart(account: FollowAccount) {
        requireAccepted(account)
        val current = store.load(account) ?: return
        if (current.candidate != null) store.save(account, current.copy(candidate = null))
    }

    private fun requireAccepted(account: FollowAccount) {
        require(account.tenantId.isNotBlank() && account.identity.issuer.isNotBlank() &&
            account.identity.subject.isNotBlank())
        check(acceptedAccount() == AcceptedTenantContinuity(account.tenantId, account.identity)) {
            "Follow source is unavailable without this Service-accepted account binding"
        }
    }
}
