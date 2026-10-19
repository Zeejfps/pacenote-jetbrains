package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.mcp.AgentTool
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.settings.AgentPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** A conversation outliving its sessions: the user's moves are pushed to the agent as its next
 * turn, a session that ends leaves a mark in the transcript, and the pairing tools reach the live
 * session from another thread. */
class AgentConversationTest {
    private val ui = UiThread()
    private val presentation = RecordingPairingPresentation()
    private val workspace = ScriptedWorkspace()
    private val told = mutableListOf<AgentPrompt>()
    private val driver = object : AgentDriver {
        override fun tell(prompt: AgentPrompt) {
            told.add(prompt)
        }

        override fun dispose() {}
    }
    private val sessions = PairingSessions { preset, opening ->
        val conversation = AgentConversation(Path.of("C:/repo"), preset, { goal, transcript, deliver ->
            PairingSession(PairingStore(goal, preset.name, transcript, presentation, workspace, ui.scope, deliver), ui.scope)
        }, ui.scope)
        if (opening == AgentOpening.Blank) conversation.openOnFirstWords()
        conversation.attach(driver)
        conversation
    }

    @After
    fun tearDown() {
        ui.run { sessions.dispose() }
        ui.close()
    }

    private fun tools(): Map<String, AgentTool> = PairingTools.createAll(sessions, ui.dispatcher).associateBy { it.name }

    private suspend fun call(tool: String, args: String) =
        withContext(Dispatchers.Default) { tools().getValue(tool).invoke(Json.parseToJsonElement(args).jsonObject) }

    @Test
    fun theFirstWords_ofABlankConversation_carryTheOpening() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)

        conversation.say("What does this do?")
        conversation.say("And this?")

        assertTrue(told[0].text.startsWith(PairingInstructions.chatOpening(Path.of("C:/repo").toString())))
        assertTrue(told[0].text.endsWith("What does this do?"))
        assertEquals("And this?", told[1].text)
    }

    @Test
    fun theAgent_startsASession_andTheUsersMovesArePushedAsItsNextTurns() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()

        val started = call("pairing_start", """{"goal":"Add a retry"}""")
        assertFalse(started.content, started.isError)
        val opened = call("pairing_stop",
            """{"path":"src/Client.cs","symbol":"Fetch","title":"Retry","reason":"Because.","code":"retry();","after_line":12}""")
        assertFalse(opened.content, opened.isError)
        assertEquals("on_symbol", (Json.parseToJsonElement(opened.content).jsonObject["placed"] as JsonPrimitive).content)

        val session = conversation.session.value!!
        session.skip()

        assertTrue(told.last().text.startsWith("DiffDino pairing: the user skipped stop 1"))
    }

    @Test
    fun aStopPlacedByQuotedLines_echoesTheLinesItReplaces() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()
        call("pairing_start", """{"goal":"Add a retry"}""")

        val opened = call("pairing_stop",
            """{"path":"src/Client.cs","symbol":"Fetch","title":"Retry","reason":"Because.","code":"retry();","replaces":"line 12"}""")

        assertFalse(opened.content, opened.isError)
        val reply = Json.parseToJsonElement(opened.content).jsonObject
        assertEquals("lines 12-12", (reply["code_replaces"] as JsonPrimitive).content)
        assertEquals("    12\tline 12", (reply["replaced_text"] as JsonPrimitive).content)
    }

    @Test
    fun aStopTakesOneWayOfPlacingItsCode() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()
        call("pairing_start", """{"goal":"Add a retry"}""")

        val opened = call("pairing_stop",
            """{"path":"src/Client.cs","symbol":"Fetch","title":"Retry","reason":"Because.","code":"x","replaces":"line 12","after_line":3}""")

        assertTrue(opened.isError)
        assertEquals("Pass one of replaces, lines and after_line.", opened.content)
    }

    @Test
    fun aSessionThatEnds_leavesItsOutcomeInTheTranscript_andTheConversationGoesOn() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()
        val session = conversation.beginSession("Add a retry")

        session.end()
        waitFor("the session to retire") { conversation.session.value == null }

        val over = conversation.transcript.messages.value.last() as PairingMessage.SessionOver
        assertEquals(PairingPhase.Ended(null), over.outcome)
        assertTrue(told.last().text.startsWith("DiffDino pairing: the user ended the session"))
        assertFalse(conversation.isGone)
        assertNull(sessions.storeFor())
    }

    @Test
    fun theAgentDying_failsTheLiveSession() = ui.run {
        val conversation = sessions.openChat(AgentPreset.ClaudeCode)
        conversation.markRunning()
        val session = conversation.beginSession("Add a retry")

        conversation.fail("Claude Code exited.")

        assertEquals(PairingPhase.Failed("Claude Code exited."), session.store.phase.value)
        assertTrue(conversation.phase.value is AgentPhase.Gone)
    }

    @Test
    fun aToolCall_withNoSession_saysSo() = ui.run {
        sessions.openChat(AgentPreset.ClaudeCode)

        val result = call("pairing_say", """{"text_md":"hi"}""")

        assertTrue(result.isError)
        assertTrue(result.content.contains("No pairing session"))
    }

    @Test
    fun aRoadmapWithoutTitles_isRefusedBeforeReachingTheSession() = ui.run {
        val result = call("pairing_roadmap", """{"milestones":[{"done":true}]}""")

        assertEquals("milestones[0].title must be a non-empty string.", result.content)
    }
}
