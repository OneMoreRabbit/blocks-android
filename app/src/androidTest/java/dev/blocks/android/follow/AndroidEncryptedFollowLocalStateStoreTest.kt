package dev.blocks.android.follow

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.auth.AcceptedTenantContinuity
import dev.blocks.android.mvp.AccountIdentity
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedFollowLocalStateStoreTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountA = FollowAccount("follow-test-tenant-a", AccountIdentity("issuer", "subject-a"))
    private val accountB = FollowAccount("follow-test-tenant-b", AccountIdentity("issuer", "subject-b"))
    private val store get() = AndroidEncryptedFollowLocalStateStore(context)

    @Before
    @After
    fun clearFixtures() {
        store.eraseTenant(accountA.tenantId)
        store.eraseTenant(accountB.tenantId)
    }

    @Test
    fun keystoreEncryptedCandidateAndSourceSurviveStoreRecreationButNotProcessCandidateReset() {
        val recorder = FollowLocalIntentRecorder(store,
            { AcceptedTenantContinuity(accountA.tenantId, accountA.identity) }, { "private-source-a" })
        val candidate = recorder.record(accountA, "private-project-a", FollowKind.EXPLICIT_BUTTON,
            promptTransportAvailable = true, lastObservedRevision = null,
            elapsedMs = 100, bootIdentity = "boot-a")!!
        assertEquals(candidate, AndroidEncryptedFollowLocalStateStore(context).load(accountA)!!.candidate)
        val files = File(context.applicationInfo.dataDir, "databases").listFiles().orEmpty()
            .filter { it.name.startsWith("blocks_follow_${digest(accountA.tenantId)}") }
        assertTrue(files.isNotEmpty())
        val persisted = files.joinToString { it.readText() }
        assertFalse(persisted.contains("private-source-a"))
        assertFalse(persisted.contains("private-project-a"))
        assertFalse(persisted.contains("subject-a"))

        val recreated = FollowLocalIntentRecorder(AndroidEncryptedFollowLocalStateStore(context),
            { AcceptedTenantContinuity(accountA.tenantId, accountA.identity) })
        recreated.discardPendingAfterProcessStart(accountA)
        val after = store.load(accountA)!!
        assertEquals("private-source-a", after.sourceId)
        assertEquals(2L, after.nextSequence)
        assertNull(after.candidate)
    }

    @Test
    fun tenantAndAccountMismatchFailClosedAndErasureRemovesOnlyTargetTenant() {
        store.save(accountA, FollowLocalState("source-a", 2, null))
        store.save(accountB, FollowLocalState("source-b", 3, null))
        assertTrue(runCatching {
            store.load(FollowAccount(accountA.tenantId, accountB.identity))
        }.isFailure)
        store.eraseTenant(accountA.tenantId)
        assertNull(store.load(accountA))
        assertEquals("source-b", store.load(accountB)!!.sourceId)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
