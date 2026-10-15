package dev.diffdino.pairing.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.diffdino.pairing.acp.AcpFileEdit
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftSpan
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.Milestone
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.AgentOpening
import dev.diffdino.pairing.pairing.EditPreview
import dev.diffdino.pairing.pairing.NoticeTone
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.pairing.PairingSession
import dev.diffdino.pairing.pairing.PairingSessions
import dev.diffdino.pairing.pairing.PairingStore
import dev.diffdino.pairing.pairing.RecordingPairingPresentation
import dev.diffdino.pairing.pairing.ScriptedWorkspace
import dev.diffdino.pairing.pairing.ToolApprovalOutcome
import dev.diffdino.pairing.pairing.UiThread
import dev.diffdino.pairing.settings.AgentPreset
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent

/** The whole conversation panel built over a conversation holding one of everything, laid out headless:
 * what is on screen, and that answering a question changes it. */
class ConversationPanelSmokeTest : BasePlatformTestCase() {
    private val ui = UiThread()
    private val sessions = PairingSessions { preset, opening ->
        val conversation = AgentConversation(Path.of("C:/repo"), preset, { goal, transcript, deliver ->
            PairingSession(PairingStore(goal, preset.name, transcript, RecordingPairingPresentation(), ScriptedWorkspace(), ui.scope, deliver), ui.scope)
        }, ui.scope)
        if (opening == AgentOpening.Blank) conversation.openOnFirstWords()
        conversation
    }

    override fun tearDown() {
        try {
            ui.run { sessions.dispose() }
            ui.close()
        } finally {
            super.tearDown()
        }
    }

    private fun flush() {
        repeat(3) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    }

    private fun layout(component: JComponent, width: Int, height: Int) {
        component.setSize(width, height)
        repeat(3) {
            UIUtil.dispatchAllInvocationEvents()
            UIUtil.layoutRecursively(component)
        }
    }

    private fun buttons(root: JComponent, text: String): List<JButton> =
        UIUtil.findComponentsOfType(root, JButton::class.java).filter { it.text == text || it.text.startsWith("$text (") }

    private fun conversationWithEverything(): Pair<AgentConversation, PairingMessage.Approval> = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()
        val transcript = conversation.transcript
        transcript.addFromUser(
            "What does **this** do?",
            CodeQuote.InFile("C:/repo/src/Client.kt", FileLine(3), FileLine(4), "fun fetch() {\n    call()\n}"),
        )
        transcript.addReply("It **fetches**.\n\n```kotlin\nfun fetch() = call()\n```\n\n" + "A long paragraph that wraps. ".repeat(20))
        transcript.addNotice("Refused: not now", NoticeTone.Refused)
        transcript.addNotice("Something broke", NoticeTone.Error)
        val edits = listOf(AcpFileEdit("C:/repo/src/Client.kt", "call()", "retry { call() }"))
        transcript.askPermission("Edit Client.kt", "Edit", EditPreview.of(edits, Path.of("C:/repo")), allowFiles = {}, edits = edits)
        transcript.add(PairingMessage.AllowedEdit("Edit Util.kt", EditPreview.of(edits, Path.of("C:/repo")), edits))
        val session = conversation.beginSession("Add a retry")
        session.store.setRoadmap(listOf(Milestone("Wrap the call", true), Milestone("Add a test", false)))
        session.store.openStop(
            StopTarget("src/Client.kt", "fetch", null), "Retry the call", "Because it **flaps**.",
            DraftRequest("retry()", DraftSpan.After(12)), replace = false,
        )
        conversation to transcript.messages.value.filterIsInstance<PairingMessage.Approval>().single()
    }

    fun testPanelShowsTheSessionStopAndTranscriptAndAnAnsweredApprovalCollapses() {
        val (conversation, approval) = conversationWithEverything()
        val panel = ConversationPanel(conversation, project, Lifetime(testRootDisposable))

        flush()
        layout(panel, 460, 900)

        assertEquals(1, buttons(panel, "Allow").size)
        assertEquals(1, buttons(panel, "Deny").size)
        assertEquals(1, buttons(panel, "Allow edits to this file").size)
        assertTrue(buttons(panel, "Next").isNotEmpty())
        assertTrue(buttons(panel, "Skip").isNotEmpty())
        val panes = UIUtil.findComponentsOfType(panel, WrappingHtmlPane::class.java)
        assertTrue(panes.isNotEmpty())
        assertTrue(panes.filter { it.isShowing || it.width > 0 }.all { it.height > 0 })

        buttons(panel, "Allow").single().doClick()
        flush()
        layout(panel, 460, 900)

        assertEquals(ToolApprovalOutcome.Approved, approval.pending.outcome.value)
        assertTrue(buttons(panel, "Allow").isEmpty())
    }

    fun testHtmlTextIsTallerWhenTheWidthIsNarrower() {
        val pane = WrappingHtmlPane()
        pane.showMarkdown("word ".repeat(200))

        layout(pane, 600, 10)
        val wide = pane.preferredSize.height
        layout(pane, 150, 10)
        val narrow = pane.preferredSize.height

        assertTrue("wide=$wide narrow=$narrow", narrow > wide * 2)
    }

    fun testStreamingNarrationGrowsTheRowItIsIn() {
        val conversation = ui.run { sessions.openChat(AgentPreset.ClaudeCode).also { it.markRunning() } }
        val text = MutableStateFlow("first")
        ui.run { conversation.transcript.add(PairingMessage.Narration(text.asStateFlow())) }
        val panel = ConversationPanel(conversation, project, Lifetime(testRootDisposable))
        flush()
        layout(panel, 400, 600)
        val before = UIUtil.findComponentsOfType(panel, WrappingHtmlPane::class.java).sumOf { it.height }

        text.value = "first\n\n" + "and a lot more text. ".repeat(60)
        Thread.sleep(150)
        flush()
        layout(panel, 400, 600)

        val after = UIUtil.findComponentsOfType(panel, WrappingHtmlPane::class.java).sumOf { it.height }
        assertTrue("before=$before after=$after", after > before)
    }
}
