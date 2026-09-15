package dev.blocks.android.state

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blocks.android.cover.AndroidEncryptedProjectCoverMediaStore
import dev.blocks.android.mvp.NoOpDraftStore
import dev.blocks.android.mvp.ParkingDraft
import dev.blocks.android.mvp.Session
import dev.blocks.android.mvp.TenantSyncState
import dev.blocks.android.project.ProjectCacheState
import dev.blocks.android.project.ProjectLifecycle
import dev.blocks.android.project.ProjectNotes
import dev.blocks.android.project.ProjectPage
import dev.blocks.android.project.ProjectPresentationController
import dev.blocks.android.project.ProjectService
import dev.blocks.android.project.ProjectSummary
import dev.blocks.android.sync.NoOpLegacyQueueReplay
import dev.blocks.android.sync.ProjectSyncRepository
import dev.blocks.android.tag.ProjectKey
import dev.blocks.android.tag.ProjectLinkIdentity
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalActionPerformanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: AndroidSyncStateStore
        get() = AndroidSyncStateStore(context)

    @Before
    @After
    fun clear() = store.clearSyncState(TENANT)

    @Test
    fun seededCreateAndParkRenderLocallyWithinBudget() {
        val seed = seededState(500)
        store.saveSyncState(TENANT, seed)
        val workspace = ProjectSyncRepository(
            offlineService(),
            NoOpLegacyQueueReplay,
            NoOpDraftStore,
            store,
            coverMediaStore = AndroidEncryptedProjectCoverMediaStore(context),
        )
        val nextId = generateSequence(1) { it + 1 }.iterator()
        val controller = ProjectPresentationController(
            authenticator = { Session(TENANT, "accepted-local-test-credential") },
            workspace = workspace,
            newOperationId = { uuid("operation-${nextId.next()}") },
        )
        controller.login("accepted-local-test-credential")

        val createTimings = (1..25).map { index ->
            elapsedMillis { controller.createProject("Measured project $index") }
        }
        controller.openProject(seed.projectCache.pages.first().project.id)
        val parkTimings = (1..25).map { index ->
            elapsedMillis {
                controller.addParking("Bench $index", "Fit part $index", "Keep note $index")
            }
        }

        val create = createTimings.statistics()
        val park = parkTimings.statistics()
        Log.i(TAG, "create median=${create.median}ms p95=${create.p95}ms slowest=${create.slowest}ms samples=${createTimings.size}")
        Log.i(TAG, "park median=${park.median}ms p95=${park.p95}ms slowest=${park.slowest}ms samples=${parkTimings.size}")
        assertTrue("Create p95 was ${create.p95}ms", create.p95 < 100.0)
        assertTrue("Park p95 was ${park.p95}ms", park.p95 < 100.0)
        assertTrue(controller.state.activeProjects.any { it.name == "Measured project 25" })
        assertTrue(controller.state.parkingHistory.any { it.location == "Bench 25" })
    }

    private fun seededState(size: Int): TenantSyncState {
        val projects = (1..size).map { index ->
            val id = uuid("seed-project-$index")
            ProjectSummary(
                id = id,
                tenantId = TENANT,
                name = "Seed project $index",
                lifecycle = ProjectLifecycle.ACTIVE,
                rowVersion = 1,
                createdAt = NOW,
                updatedAt = NOW,
                clientId = id,
            )
        }
        return TenantSyncState(
            projectCache = ProjectCacheState(
                activeProjects = projects,
                activeProjectsLoaded = true,
                archivedProjectsLoaded = true,
                pages = projects.map { project ->
                    ProjectPage(project, ProjectNotes(project.id, "", 1, NOW), null)
                },
                parkingLoadedProjectIds = projects.map { it.id },
                projectLinks = projects.map { project ->
                    ProjectLinkIdentity(project.id, ProjectKey.parse(project.id)!!)
                },
            ),
            projectDraftsMigrated = true,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun offlineService(): ProjectService = Proxy.newProxyInstance(
        ProjectService::class.java.classLoader,
        arrayOf(ProjectService::class.java),
    ) { _, method, _ -> error("Foreground action called Service.${method.name}") } as ProjectService

    private fun elapsedMillis(action: () -> Unit): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        action()
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
    }

    private fun List<Double>.statistics(): TimingStatistics {
        val ordered = sorted()
        val p95Index = ((ordered.size * 0.95).toInt()).coerceAtMost(ordered.lastIndex)
        return TimingStatistics(
            median = ordered[ordered.size / 2],
            p95 = ordered[p95Index],
            slowest = ordered.last(),
        )
    }

    private fun uuid(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private data class TimingStatistics(val median: Double, val p95: Double, val slowest: Double)

    private companion object {
        const val TENANT = "00000000-0000-0000-0000-000000000711"
        const val NOW = "2026-09-15T12:00:00Z"
        const val TAG = "BlocksTiming"
    }
}
