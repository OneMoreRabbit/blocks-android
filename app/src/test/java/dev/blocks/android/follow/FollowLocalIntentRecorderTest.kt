package dev.blocks.android.follow

import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.AccountIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLocalIntentRecorderTest {
    private val accountA = FollowAccount("tenant-a", AccountIdentity("issuer", "user-a"))
    private val accountB = FollowAccount("tenant-b", AccountIdentity("issuer", "user-b"))
    private val store = MemoryStore()
    private var active: AcceptedTenantContinuity? = AcceptedTenantContinuity(
        accountA.tenantId, accountA.identity,
    )
    private var nextSource = 0
    private val recorder = FollowLocalIntentRecorder(store, { active }, { "source-${++nextSource}" })

    @Test
    fun firstPromptPressNeedsNoRevisionAndRetainsItsImmutableClass() {
        val first = recorder.record(accountA, "project-a", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 100, bootIdentity = "boot-1")!!
        assertEquals(FollowDispatchClass.IMMEDIATE, first.dispatchClass)
        assertNull(first.observedRevision)
        assertEquals("source-1", first.sourceId)
        assertEquals(1L, first.sequence)
        assertEquals(9L, first.ageMs(109, "boot-1"))
        assertNull(first.ageMs(109, "boot-2"))
        assertNull(first.ageMs(99, "boot-1"))
        assertEquals(first, store.load(accountA)!!.candidate)
    }

    @Test
    fun offlineNoBaselineCannotPublishAndReplacesOlderUnsentSelection() {
        recorder.record(accountA, "project-a", FollowKind.AUTOMATIC_NFC,
            promptTransportAvailable = false, lastObservedRevision = 4,
            elapsedMs = 100, bootIdentity = "boot-1")
        val result = recorder.record(accountA, "project-b", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = false, lastObservedRevision = null,
            elapsedMs = 101, bootIdentity = "boot-1")
        assertNull(result)
        val state = store.load(accountA)!!
        assertNull(state.candidate)
        assertEquals(3L, state.nextSequence)
    }

    @Test
    fun unsentRapidSelectionsCoalesceToHigherSequenceWithoutChangingSource() {
        val a = recorder.record(accountA, "project-a", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = 7,
            elapsedMs = 100, bootIdentity = "boot-1")!!
        val b = recorder.record(accountA, "project-b", FollowKind.AUTOMATIC_NFC,
            promptTransportAvailable = false, lastObservedRevision = 7,
            elapsedMs = 101, bootIdentity = "boot-1")!!
        assertEquals(a.sourceId, b.sourceId)
        assertEquals(a.sequence + 1, b.sequence)
        assertEquals(FollowDispatchClass.DEFERRED, b.dispatchClass)
        assertEquals(7L, b.observedRevision)
        assertEquals(b, store.load(accountA)!!.candidate)
    }

    @Test
    fun processRestartDropsCandidateButKeepsSourceAndCounter() {
        val first = recorder.record(accountA, "project-a", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 100, bootIdentity = "boot-1")!!
        recorder.discardPendingAfterProcessStart(accountA)
        assertNull(store.load(accountA)!!.candidate)
        val next = recorder.record(accountA, "project-b", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 4, bootIdentity = "boot-2")!!
        assertEquals(first.sourceId, next.sourceId)
        assertEquals(2L, next.sequence)
    }

    @Test
    fun accountSwitchCannotUsePreviousPartitionOrSource() {
        val a = recorder.record(accountA, "project-a", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 100, bootIdentity = "boot-1")!!
        active = null
        assertTrue(runCatching {
            recorder.record(accountA, "project-x", FollowKind.EXPLICIT_BUTTON,
                true, null, 101, "boot-1")
        }.isFailure)
        active = AcceptedTenantContinuity(accountB.tenantId, accountB.identity)
        val b = recorder.record(accountB, "project-b", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 102, bootIdentity = "boot-1")!!
        assertTrue(a.sourceId != b.sourceId)
        active = AcceptedTenantContinuity(accountA.tenantId, accountA.identity)
        val resumed = recorder.record(accountA, "project-a2", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 103, bootIdentity = "boot-1")!!
        assertEquals(a.sourceId, resumed.sourceId)
        assertEquals(2L, resumed.sequence)
    }

    @Test
    fun automaticTriggerIsOnlySuccessfulFreshNfcWithSettingAndAcceptedBinding() {
        FollowOpenOrigin.entries.forEach { origin ->
            val allowed = FollowTriggerPolicy.automaticSelectionAllowed(origin,
                settingEnabled = true, resolvedLocally = true,
                serviceAcceptedBindingAvailable = true)
            assertEquals(origin == FollowOpenOrigin.FRESH_NFC, allowed)
        }
        assertTrue(!FollowTriggerPolicy.automaticSelectionAllowed(FollowOpenOrigin.FRESH_NFC,
            settingEnabled = false, resolvedLocally = true,
            serviceAcceptedBindingAvailable = true))
        assertTrue(!FollowTriggerPolicy.automaticSelectionAllowed(FollowOpenOrigin.FRESH_NFC,
            settingEnabled = true, resolvedLocally = false,
            serviceAcceptedBindingAvailable = true))
        assertTrue(!FollowTriggerPolicy.automaticSelectionAllowed(FollowOpenOrigin.FRESH_NFC,
            settingEnabled = true, resolvedLocally = true,
            serviceAcceptedBindingAvailable = false))
    }

    private class MemoryStore : FollowLocalStateStore {
        private val states = mutableMapOf<FollowAccount, FollowLocalState>()
        override fun load(account: FollowAccount): FollowLocalState? = states[account]
        override fun save(account: FollowAccount, state: FollowLocalState) {
            states[account] = state
        }
        override fun eraseTenant(tenantId: String) {
            states.keys.removeAll { it.tenantId == tenantId }
        }
    }
}
