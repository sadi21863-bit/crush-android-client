package com.crush.mobile.di

import android.app.Application
import android.os.Looper
import com.crush.mobile.project.ProjectRef
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * V2-0 carry-over "container-level applyBinding/bindingSnapshot test".
 *
 * The Phase 2a contract is one value: a binding and the instance key it
 * resolved to are published TOGETHER, so a collector can never pair project A
 * with project B's engine state. The pure token codec was already pinned by
 * [EngineBindingTokenTest]; this class exercises the real container setters and
 * the real flow, including the same-key label update that historically could
 * have returned before the snapshot moved.
 *
 * No DataStore seed and no persistence assertion here: `DataStore.edit` does not
 * complete while Robolectric's paused main looper is blocked, which hung the
 * first version of this class (thread dump: `runBlocking` under
 * `settleColdStartOnLegacy`). The cold-boot token round trip is the codec test's
 * job; the container's default binding is already the legacy pair.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the manifest's CrushMobileApp builds its own AppContainer
// in onCreate, and that container's DataStore read has a continuation queued on
// the main looper. Blocking this test's main thread on DataStore then deadlocks
// against it (thread dump: settleColdStartOnLegacy waiting on a BlockingCoroutine).
@Config(application = Application::class)
class AppContainerBindingTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var container: AppContainer

    @Before
    fun settleColdStartOnLegacy() {
        container = AppContainer(context)
        idleMain()
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun expectedKey(binding: AppContainer.EngineBinding): String = when (binding) {
        is AppContainer.EngineBinding.Legacy -> ""
        is AppContainer.EngineBinding.ChatPool -> "chat"
        is AppContainer.EngineBinding.Project -> container.instanceKeyFor(binding.ref.id)
    }

    @Test
    fun `the initial snapshot is the legacy pair`() {
        val snapshot = container.bindingSnapshot.value

        assertEquals(AppContainer.EngineBinding.Legacy, snapshot.binding)
        assertEquals("", snapshot.instanceKey)
        assertEquals("", container.activeInstanceKey.value)
        assertEquals(AppContainer.EngineBinding.Legacy, container.activeBinding.value)
    }

    @Test
    fun `binding the chat pool moves the binding and key together`() {
        container.setActiveChatPool()

        val snapshot = container.bindingSnapshot.value
        assertEquals(AppContainer.EngineBinding.ChatPool, snapshot.binding)
        assertEquals("chat", snapshot.instanceKey)
        assertEquals("chat", container.activeInstanceKey.value)
        assertEquals(AppContainer.EngineBinding.ChatPool, container.activeBinding.value)
    }

    @Test
    fun `a same-key project label update still moves the snapshot`() {
        val id = UUID.randomUUID()
        val project = ProjectRef(id, "Alpha", File("/tmp/alpha"))
        container.setActiveProject(project)

        val first = container.bindingSnapshot.value
        assertEquals(container.instanceKeyFor(id), first.instanceKey)

        // Same id => same instance key. applyBinding must update the snapshot
        // BEFORE the same-key early return, or the UI keeps the stale label.
        val renamed = project.copy(label = "Alpha renamed")
        container.setActiveProject(renamed)

        val second = container.bindingSnapshot.value
        assertEquals(container.instanceKeyFor(id), second.instanceKey)
        assertTrue(
            "the snapshot must carry the new label",
            (second.binding as AppContainer.EngineBinding.Project).ref.label == "Alpha renamed",
        )
        assertEquals(container.instanceKeyFor(id), container.activeInstanceKey.value)
    }

    @Test
    fun `every published snapshot pairs its binding with its own key`() {
        val seen = Collections.synchronizedList(mutableListOf<BindingSnapshot>())
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            container.bindingSnapshot.collect { seen += it }
        }

        val project = ProjectRef(UUID.randomUUID(), "Project", File("/tmp/project"))
        container.setActiveChatPool()
        container.setActiveProject(project)
        container.setActiveProject(project.copy(label = "Project renamed"))
        container.setActiveProject(null)

        collector.cancel()

        assertTrue("expected every transition to publish", seen.size >= 5)
        seen.forEach { snapshot ->
            assertEquals(
                "snapshot paired ${snapshot.binding} with the wrong key",
                expectedKey(snapshot.binding),
                snapshot.instanceKey,
            )
        }
        assertEquals(AppContainer.EngineBinding.Legacy, container.bindingSnapshot.value.binding)
        assertEquals("", container.bindingSnapshot.value.instanceKey)
    }
}
