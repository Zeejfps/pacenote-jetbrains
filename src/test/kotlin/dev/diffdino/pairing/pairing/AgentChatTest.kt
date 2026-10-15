package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.settings.AgentPreferences
import dev.diffdino.pairing.settings.AgentPreset
import dev.diffdino.pairing.settings.AgentPresetId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** The agent chat's way in: the first press asks which agent, the pick is remembered, and later
 * presses show and hide the conversation without ending it. */
class AgentChatTest {
    private val ui = UiThread()
    private val preferences = object : AgentPreferences {
        override val presets: List<AgentPreset> = AgentPreset.BuiltIn
        override var chatAgent: AgentPresetId? = null

        override fun rememberChatAgent(id: AgentPresetId) {
            chatAgent = id
        }
    }
    private val opened = mutableListOf<Triple<AgentPreset, AgentOpening, AgentConversation>>()
    private val sessions = PairingSessions { preset, opening ->
        val conversation = AgentConversation(Path.of("C:/repo"), preset, { _, _, _ -> throw IllegalStateException("No sessions here.") }, ui.scope)
        opened.add(Triple(preset, opening, conversation))
        conversation
    }
    private val chat = AgentChat(sessions, preferences)

    @After
    fun tearDown() {
        ui.run { sessions.dispose() }
        ui.close()
    }

    @Test
    fun theFirstPress_asksForAnAgent_andOpensNothing() = ui.run {
        assertEquals(AgentChatPress.NeedsAgent, chat.press())
        assertTrue(opened.isEmpty())
        assertNull(sessions.shown.value)
    }

    @Test
    fun pickingAnAgent_opensABlankConversationOnScreen_andRemembersIt() = ui.run {
        val conversation = chat.open(AgentPreset.Codex)

        val (preset, opening, created) = opened.single()
        assertSame(created, conversation)
        assertEquals(AgentPreset.Codex, preset)
        assertEquals(AgentOpening.Blank, opening)
        assertSame(conversation, sessions.shown.value)
        assertEquals(AgentPreset.Codex, chat.remembered)
    }

    @Test
    fun pressingAgain_hidesAndShowsTheConversation_withoutEndingIt() = ui.run {
        val conversation = chat.open(AgentPreset.ClaudeCode)

        assertEquals(AgentChatPress.Existing, chat.press())
        assertNull(sessions.shown.value)
        assertSame(conversation, sessions.conversation.value)
        assertFalse(sessions.isPanelShown)

        assertEquals(AgentChatPress.Existing, chat.press())
        assertSame(conversation, sessions.shown.value)
        assertEquals(1, opened.size)
    }

    @Test
    fun revealingAHiddenConversation_showsIt_andRevealingAgainKeepsItUp() = ui.run {
        val conversation = chat.open(AgentPreset.ClaudeCode)
        sessions.hidePanel()

        chat.reveal()
        chat.reveal()

        assertSame(conversation, sessions.shown.value)
    }

    @Test
    fun withAnAgentRemembered_aPress_opensAConversationWithIt() = ui.run {
        preferences.rememberChatAgent(AgentPreset.Gemini.id)

        val pressed = chat.press() as AgentChatPress.Opened

        assertEquals(AgentPreset.Gemini, pressed.conversation.harness)
    }

    @Test
    fun askingInAHiddenConversation_putsItBackOnScreen() = ui.run {
        val conversation = chat.open(AgentPreset.ClaudeCode)
        sessions.hidePanel()

        sessions.ask("explain this", null, conversation.harness)

        assertSame(conversation, sessions.shown.value)
    }

    @Test
    fun pickingAnotherAgent_replacesTheConversation_andTheMenuMarksIt() = ui.run {
        val first = chat.open(AgentPreset.ClaudeCode)

        val second = chat.open(AgentPreset.Codex)

        assertNotSame(first, second)
        assertSame(second, sessions.shown.value)
        assertEquals(AgentPreset.Codex.name, chat.agentMenu().single { it.checked }.label)
    }

    @Test
    fun restarting_opensABlankConversationWithTheSameAgent_inPlaceOfTheOldOne() = ui.run {
        val first = chat.open(AgentPreset.ClaudeCode)
        first.say("remember this")
        sessions.hidePanel()

        val restarted = sessions.restart()

        assertNotNull(restarted)
        assertNotSame(first, restarted)
        assertEquals(first.harness, restarted!!.harness)
        assertEquals(AgentOpening.Blank, opened.last().second)
        assertTrue(restarted.transcript.messages.value.isEmpty())
        assertSame(restarted, sessions.shown.value)
    }

    @Test
    fun restarting_withNoConversation_opensNothing() = ui.run {
        assertNull(sessions.restart())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun askingWithNoAgentPicked_asksForOne_andOpensNothing() = ui.run {
        assertEquals(AgentChatAsk.NeedsAgent, chat.ask("Explain this selection.", null))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun askingWithAnAgentRemembered_opensAConversationOnTheQuestion() = ui.run {
        preferences.rememberChatAgent(AgentPreset.Codex.id)

        val asked = chat.ask("What could break here?", null) as AgentChatAsk.Asked

        val (preset, opening, conversation) = opened.single()
        assertSame(conversation, asked.conversation)
        assertEquals(AgentPreset.Codex, preset)
        assertEquals("What could break here?", (opening as AgentOpening.Chat).text)
        assertSame(conversation, sessions.shown.value)
    }

    @Test
    fun askingWhileAConversationIsHidden_saysItThere_andShowsIt() = ui.run {
        val conversation = chat.open(AgentPreset.ClaudeCode)
        conversation.markRunning()
        sessions.hidePanel()

        val asked = chat.ask("Suggest a fix for this.", null) as AgentChatAsk.Asked

        assertSame(conversation, asked.conversation)
        assertEquals(1, opened.size)
        val said = conversation.transcript.messages.value.last() as PairingMessage.FromUser
        assertEquals("Suggest a fix for this.", said.text)
        assertSame(conversation, sessions.shown.value)
    }

    @Test
    fun pickingFromTheMenu_carriesOnWithWhatWasAsked() = ui.run {
        var carried: AgentConversation? = null

        chat.agentMenu { carried = it }.single { it.label == AgentPreset.Codex.name }.select()

        assertNotNull(carried)
        assertSame(carried, sessions.shown.value)
    }

    @Test
    fun pickingTheSameAgent_keepsTheConversation() = ui.run {
        val first = chat.open(AgentPreset.ClaudeCode)
        sessions.hidePanel()

        assertSame(first, chat.open(AgentPreset.ClaudeCode))
        assertSame(first, sessions.shown.value)
        assertEquals(1, opened.size)
    }
}
