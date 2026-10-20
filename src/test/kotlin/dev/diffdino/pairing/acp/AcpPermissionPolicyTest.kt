package dev.diffdino.pairing.acp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ACP write guard, over permission requests as the three adapters really send them (captured
 * by the spike): file writes put to the user, reads and shell allowed, the plugin's own MCP tools
 * allowed however each adapter names them, anything else left to the user. */
class AcpPermissionPolicyTest {
    private val own = "diffdino"

    private fun decide(json: String, announced: Map<String, String> = emptyMap()): AcpPermissionDecision {
        val request = AcpPermissionPolicy.parse(Json.parseToJsonElement(json), announced) ?: throw AssertionError("did not parse")
        return AcpPermissionPolicy.decide(request, own)
    }

    private fun assertSelected(decision: AcpPermissionDecision, optionId: String, verdict: AcpPermissionVerdict) {
        assertEquals(AcpPermissionDecision.Select(optionId, verdict), decision)
    }

    private fun assertAsked(decision: AcpPermissionDecision) = assertEquals(AcpPermissionDecision.AskUser, decision)

    private val claudeOptions =
        """[{"optionId":"allow-once","name":"Yes","kind":"allow_once"},{"optionId":"allow-with-updates","name":"Yes, always","kind":"allow_always"},{"optionId":"reject","name":"No","kind":"reject_once"}]"""

    @Test
    fun claudeWrite_isPutToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","name":"Write","title":"Write plan.md","kind":"edit"},"options":$claudeOptions}"""))

    @Test
    fun claudeShell_isAllowedOnce_soTheAgentRunsTheTests() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"dotnet test","kind":"execute"},"options":$claudeOptions}"""),
        "allow-once", AcpPermissionVerdict.Allowed)

    @Test
    fun claudeOwnMcpTool_isAllowedOnce_soNothingIsWrittenIntoTheRepository() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","name":"mcp__diffdino__pairing_stop","title":"mcp__diffdino__pairing_stop","kind":"other",
           "_meta":{"claudeCode":{"toolName":"mcp__diffdino__pairing_stop","mcpServer":{"name":"diffdino","source":"dynamic"}}}},"options":$claudeOptions}"""),
        "allow-once", AcpPermissionVerdict.Allowed)

    @Test
    fun claudeOtherMcpTool_isLeftToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","name":"mcp__github__create_issue","title":"mcp__github__create_issue","kind":"other"},"options":$claudeOptions}"""))

    @Test
    fun claudeMcpTool_namedOnlyByItsTitle_isKnownByItsServer() {
        val request = AcpPermissionPolicy.parse(Json.parseToJsonElement(
            """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"mcp__claude_ai_Claude_Docs__read","kind":"other","rawInput":{"id":"d1"}},"options":$claudeOptions}"""),
            emptyMap())!!

        assertEquals("claude_ai_Claude_Docs", request.mcpServer)
        assertEquals("d1", McpToolName.arguments(request.input).removePrefix("id: "))
        assertAsked(AcpPermissionPolicy.decide(request, own))
    }

    @Test
    fun codexMcpApproval_forTheAnnouncedOwnServer_isAllowed() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"exec-1","kind":"execute","status":"pending"},"_meta":{"is_mcp_tool_approval":true},
           "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"},{"optionId":"allow_session","name":"Allow for this session","kind":"allow_always"},
                      {"optionId":"allow_always","name":"Always allow","kind":"allow_always"},{"optionId":"cancel","name":"Cancel","kind":"reject_once"}]}""",
        mapOf("exec-1" to "diffdino")),
        "allow_once", AcpPermissionVerdict.Allowed)

    @Test
    fun codexMcpApproval_forAnotherServer_isLeftToTheUser_notTakenForAShellCommand() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"exec-1","kind":"execute","status":"pending"},"_meta":{"is_mcp_tool_approval":true},
           "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Cancel","kind":"reject_once"}]}""",
        mapOf("exec-1" to "filesystem")))

    @Test
    fun codexEdit_isPutToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"exec-2","kind":"edit","status":"pending","title":"Edit files"},
           "options":[{"optionId":"allow_once","name":"Yes, proceed","kind":"allow_once"},{"optionId":"cancel","name":"No","kind":"reject_once"}]}"""))

    @Test
    fun codexRead_isAllowedOnce() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"exec-3","kind":"read","status":"pending","title":"Read file"},
           "options":[{"optionId":"allow_once","name":"Yes, proceed","kind":"allow_once"},{"optionId":"cancel","name":"No","kind":"reject_once"}]}"""),
        "allow_once", AcpPermissionVerdict.Allowed)

    @Test
    fun geminiOwnMcpTool_isRecognisedByItsServerOption_andAllowedOnce() = assertSelected(decide(
        """{"sessionId":"s","options":[{"optionId":"proceed_always_server","name":"Always Allow diffdino","kind":"allow_always"},
            {"optionId":"proceed_always_tool","name":"Always Allow pairing_stop","kind":"allow_always"},
            {"optionId":"proceed_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Reject","kind":"reject_once"}],
           "toolCall":{"toolCallId":"call_1","status":"pending","title":"{}","content":[],"locations":[],"kind":"other"}}"""),
        "proceed_once", AcpPermissionVerdict.Allowed)

    @Test
    fun delete_isPutToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t","kind":"delete"},"options":[{"optionId":"ok","name":"Yes","kind":"allow_once"}]}"""))

    @Test
    fun formatter_isAllowedOnce() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"`dotnet format`","kind":"execute","rawInput":{"command":"dotnet format"}},"options":$claudeOptions}"""),
        "allow-once", AcpPermissionVerdict.Allowed)

    @Test
    fun requestWithoutToolCall_doesNotParse() =
        assertNull(AcpPermissionPolicy.parse(Json.parseToJsonElement("""{"sessionId":"s","options":[]}"""), emptyMap()))

    @Test
    fun announcedServer_readsCodexAndClaudeMarkers() {
        assertEquals("diffdino", AcpPermissionPolicy.announcedServer(Json.parseToJsonElement(
            """{"sessionUpdate":"tool_call","toolCallId":"x","rawInput":{"server":"diffdino","tool":"pairing_stop"},"_meta":{"is_mcp_tool_call":true}}""")))
        assertEquals("diffdino", AcpPermissionPolicy.announcedServer(Json.parseToJsonElement(
            """{"sessionUpdate":"tool_call","toolCallId":"x","_meta":{"claudeCode":{"mcpServer":{"name":"diffdino"}}}}""")))
        assertNull(AcpPermissionPolicy.announcedServer(Json.parseToJsonElement("""{"sessionUpdate":"tool_call","toolCallId":"x"}""")))
    }

    @Test
    fun anMcpApproval_namingNoServer_isAskedOfTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"exec-9","kind":"execute","status":"pending"},"_meta":{"is_mcp_tool_approval":true},
           "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Cancel","kind":"reject_once"}]}"""))

    @Test
    fun claudePush_isPutToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"`git push origin main`","kind":"execute","rawInput":{"command":"git push origin main"}},"options":$claudeOptions}"""))

    @Test
    fun codexPush_inAnArgv_isPutToTheUser() = assertAsked(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"Run command","kind":"execute","rawInput":{"command":["bash","-lc","git add -A && git commit -m wip && git push"]}},"options":$claudeOptions}"""))

    @Test
    fun commit_isAllowedOnce() = assertSelected(decide(
        """{"sessionId":"s","toolCall":{"toolCallId":"t1","title":"`git commit -m \"Add retry\"`","kind":"execute","rawInput":{"command":"git commit -m \"Add retry\""}},"options":$claudeOptions}"""),
        "allow-once", AcpPermissionVerdict.Allowed)

    @Test
    fun pushes_findsGitPush() {
        val cases = listOf(
            "git push" to true,
            "git -C ../repo push --force-with-lease" to true,
            "git --no-pager -c push.default=current push" to true,
            "cd src && GIT push" to true,
            "git.exe push origin HEAD" to true,
            "git status && git log --oneline -5" to false,
            "git commit -m \"push the retry\"" to false,
            "git stash push -m wip" to false,
            "legit push" to false,
        )
        for ((command, pushes) in cases) assertEquals(command, pushes, AcpPermissionPolicy.pushes(command))
    }

    private fun parse(toolCall: String): AcpPermissionRequest =
        AcpPermissionPolicy.parse(Json.parseToJsonElement("""{"sessionId":"s","toolCall":$toolCall,"options":[]}"""), emptyMap())
            ?: throw AssertionError("did not parse")

    @Test
    fun diffContent_isTheEdit() {
        val request = parse(
            """{"toolCallId":"t1","title":"Edit a.md","kind":"edit","locations":[{"path":"C:/r/a.md"}],
               "content":[{"type":"diff","path":"C:/r/a.md","oldText":"x","newText":"y"}]}""")
        assertEquals(listOf(AcpFileEdit("C:/r/a.md", "x", "y")), request.edits)
        assertEquals(listOf("C:/r/a.md"), request.paths)
    }

    @Test
    fun claudeEditInput_isTheEdit_whereNoDiffIsSent() {
        val request = parse(
            """{"toolCallId":"t1","title":"Edit a.md","kind":"edit","rawInput":{"file_path":"C:/r/a.md","old_string":"x","new_string":"y"}}""")
        assertEquals(listOf(AcpFileEdit("C:/r/a.md", "x", "y")), request.edits)
        assertEquals(listOf("C:/r/a.md"), request.paths)
    }

    @Test
    fun claudeWriteInput_replacesTheWholeFile() {
        val request = parse("""{"toolCallId":"t1","title":"Write a.md","kind":"edit","rawInput":{"file_path":"C:/r/a.md","content":"all"}}""")
        assertEquals(listOf(AcpFileEdit("C:/r/a.md", null, "all")), request.edits)
    }

    @Test
    fun claudeMultiEditInput_isEachEdit() {
        val request = parse(
            """{"toolCallId":"t1","title":"Edit a.md","kind":"edit","rawInput":{"file_path":"a.md",
               "edits":[{"old_string":"1","new_string":"2"},{"old_string":"3","new_string":"4"}]}}""")
        assertEquals(listOf(AcpFileEdit("a.md", "1", "2"), AcpFileEdit("a.md", "3", "4")), request.edits)
    }

    @Test
    fun shellCall_hasNoEdits() {
        val request = parse("""{"toolCallId":"t1","title":"ls","kind":"execute","rawInput":{"command":"ls"}}""")
        assertTrue(request.edits.isEmpty())
        assertTrue(request.paths.isEmpty())
    }
}
