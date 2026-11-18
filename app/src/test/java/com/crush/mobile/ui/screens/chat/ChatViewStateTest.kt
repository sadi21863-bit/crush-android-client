package com.crush.mobile.ui.screens.chat

import com.crush.mobile.data.api.LiveZenModel
import com.crush.mobile.data.crush.Session
import com.crush.mobile.data.crush.SubAgentReport
import com.crush.mobile.domain.file.FileChangeSet
import com.crush.mobile.project.ProjectRef
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2A-01 (V2 plan §6.3): the Chat projection is deterministic, total, and
 * Android-free. Plain JUnit, no Robolectric runner.
 */
class ChatViewStateTest {

    @Test
    fun `projection is deterministic for equal inputs`() {
        val tool = ToolActivity(
            id = "call-1",
            name = "bash",
            args = "{}",
            // A finish flag without a result is still RUNNING: the projection
            // must not settle a call the app never saw finish (T2A-01).
            state = ToolState.RUNNING,
            output = "",
        )
        val report = SubAgentReport(label = "scout", output = "done", failed = false, model = "m1")
        val state = ChatUiState(
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    role = "assistant",
                    text = "hello",
                    tools = listOf(tool),
                    reports = listOf(report),
                ),
            ),
            models = listOf(LiveZenModel("m1", "Model One", isFree = true)),
            selectedModel = "m1",
            input = "hello",
            engineReady = true,
        )
        val inputs = ChatHostInputs(
            snapshotReady = true,
            providerDisplayName = "OpenCode Zen",
        )

        val first = state.toViewState(inputs)
        assertEquals(first, state.toViewState(inputs))
        assertEquals(tool, first.transcript.toolsByCallId["call-1"])
        assertTrue(first.transcript.toolsByCallId.getValue("call-1").isRunning)
        assertEquals(listOf(report), first.transcript.reports)
    }

    @Test
    fun `the projection is total over empty and missing inputs`() {
        val states = listOf(
            ChatUiState(),
            ChatUiState(messages = emptyList(), sessions = emptyList(), models = emptyList()),
            ChatUiState(selectedModel = "", modelsError = "fetch failed"),
            ChatUiState(
                messages = listOf(ChatMessage("m", "user", "hi")),
                activeSessionId = "gone",
                sessions = emptyList(),
            ),
            ChatUiState(fileChanges = null, pendingPermission = null, pendingQuestion = null),
            ChatUiState(handoffSheet = null, handoffProjects = emptyList(), handoffResumeHint = null),
        )
        val hosts = listOf(
            ChatHostInputs(),
            ChatHostInputs(snapshotReady = true),
            ChatHostInputs(
                binding = BindingKind.ChatPool,
                instanceKey = "chat",
                engineStateKey = "chat",
                snapshotReady = true,
            ),
            ChatHostInputs(
                binding = BindingKind.Project,
                projectLabel = null,
                instanceKey = "p1",
                engineStateKey = "",
                snapshotReady = true,
            ),
        )

        states.forEach { state ->
            hosts.forEach { host ->
                val view = state.toViewState(host)
                assertEquals(state.phase, view.run.phase)
                assertEquals(host.binding, view.binding.kind)
                assertEquals(
                    view.composer.canType && view.composer.canSend,
                    view.composer.canSubmitViaIme,
                )
            }
        }

        val empty = ChatUiState().toViewState(ChatHostInputs())
        assertEquals("New chat", empty.session.label)
        assertEquals(EngineReadiness.Starting, empty.run.readiness)
        assertTrue(empty.transcript.messages.isEmpty())
        assertTrue(empty.transcript.toolsByCallId.isEmpty())
        assertTrue(empty.transcript.reports.isEmpty())
        assertNull(empty.transcript.fileChanges)
        assertNull(empty.transcript.fileChangesLabel)
        assertNull(empty.model.selected)
        assertFalse(empty.composer.canSend)
        assertTrue(empty.composer.canType) // prewarm off: the field opens the engine
    }

    @Test
    fun `an absent selected model keeps its id and is not an error`() {
        val selected = ChatUiState(
            selectedModel = "ghost-model",
            models = emptyList(),
            modelsError = null,
        ).toViewState(ChatHostInputs()).model.selected!!

        assertEquals("ghost-model", selected.id)
        assertEquals("ghost-model", selected.displayName)
        assertFalse(selected.presentInCatalogue)
        assertEquals(FreeMarker.Hidden, selected.freeMarker)
        assertNull(selected.supportsImages)
    }

    @Test
    fun `a present selected model uses the catalogue name and image fact`() {
        val selected = ChatUiState(
            selectedModel = "m1",
            models = listOf(LiveZenModel("m1", "Display One", isFree = true, supportsImages = true)),
        ).toViewState(ChatHostInputs()).model.selected!!

        assertEquals("m1", selected.id)
        assertEquals("Display One", selected.displayName)
        assertTrue(selected.presentInCatalogue)
        assertTrue(selected.supportsImages == true)
    }

    @Test
    fun `null file changes are not rendered as zero changes`() {
        val none = ChatUiState(fileChanges = null).toViewState(ChatHostInputs())
        assertNull(none.transcript.fileChanges)
        assertNull(none.transcript.fileChangesLabel)

        val honest = ChatUiState(
            fileChanges = FileChangeSet(
                sessionId = null,
                diffs = emptyList(),
                unreadable = listOf("gone.txt"),
                snapshotUnavailable = true,
                capturedAtMs = 0,
            ),
        ).toViewState(ChatHostInputs())
        assertTrue(honest.transcript.fileChangesLabel!!.contains("unreadable"))
        assertTrue(honest.transcript.fileChangesLabel.contains("no before-image"))
    }

    @Test
    fun `the session list projects the unavailable query state`() {
        // Finding 11: the sheet needs this fact to avoid rendering an
        // unqueried empty list as "No chats yet".
        val view = ChatUiState(sessionsUnavailable = true).toViewState(ChatHostInputs())
        assertTrue(view.session.list.unavailable)
        assertFalse(view.session.list.loading)
        assertTrue(view.session.list.items.isEmpty())
    }

    @Test
    fun `session label follows the active title then falls back honestly`() {
        val active = ChatUiState(
            sessions = listOf(Session(id = "s1", title = "Plan chat")),
            activeSessionId = "s1",
        ).toViewState(ChatHostInputs())
        assertEquals("Plan chat", active.session.label)
        assertTrue(active.session.list.items.single().isActive)

        assertEquals(
            "Chat",
            ChatUiState(messages = listOf(ChatMessage("m", "user", "hi"))).toViewState(ChatHostInputs()).session.label,
        )
        assertEquals("New chat", ChatUiState().toViewState(ChatHostInputs()).session.label)
    }

    @Test
    fun `a question view carries its title and drops the raw payload`() {
        val question = PendingQuestion(
            id = "q1",
            raw = JsonParser.parseString("""{"id":"q1","question":"Choose a path"}"""),
        )
        val view = ChatUiState(pendingQuestion = question).toViewState(ChatHostInputs())

        assertEquals("q1", view.gate.question!!.id)
        assertEquals("Choose a path", view.gate.question.title)
    }

    @Test
    fun `handoff resume state and destinations survive projection`() {
        val hint = HandoffResumeHint(
            handoffId = "h1",
            targetSessionId = "s2",
            targetLabel = "Project B",
        )
        val destination = ProjectRef(
            id = UUID.randomUUID(),
            label = "Project B",
            repoDir = File("build/test-tmp/project-b"),
        )
        val view = ChatUiState(
            handoffProjects = listOf(destination),
            handoffNotice = "not dispatched",
            handoffResumeHint = hint,
        ).toViewState(ChatHostInputs())

        assertNull(view.handoff.sheet)
        assertEquals(listOf(destination), view.handoff.destinations)
        assertEquals("not dispatched", view.handoff.notice)
        assertEquals(hint, view.handoff.resume)
    }

    @Test
    fun `the projection source imports no Android or forbidden packages`() {
        val source = findSource()
        val imports = source.readLines().filter { it.trimStart().startsWith("import ") }
        val forbidden = listOf("android.", "androidx.compose.", "java.io", "java.text", "java.util.TimeZone")

        forbidden.forEach { prefix ->
            assertFalse(
                "ChatViewState.kt must not import $prefix: $imports",
                imports.any { it.contains(prefix) },
            )
        }
        assertFalse(
            "the projection must not read the wall clock",
            source.readText().contains("System.currentTimeMillis"),
        )
    }

    private fun findSource(): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val fromModule = File(dir, "src/main/java/com/crush/mobile/ui/screens/chat/ChatViewState.kt")
            if (fromModule.isFile) return fromModule
            val fromRoot = File(dir, "app/src/main/java/com/crush/mobile/ui/screens/chat/ChatViewState.kt")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile
        }
        error("ChatViewState.kt not found above ${System.getProperty("user.dir")}")
    }
}
