package dev.diffdino.pairing.acp

import dev.diffdino.pairing.settings.AgentArgumentsProblem
import dev.diffdino.pairing.settings.AgentKind
import dev.diffdino.pairing.settings.AgentPermission
import dev.diffdino.pairing.settings.AgentPreset
import dev.diffdino.pairing.settings.AgentPresetId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Presets as the adapters take them: Claude Code's flags in the session's `_meta` and its model in
 * the environment, the other adapters' on their command line, and each in its asking mode. */
class AcpHarnessTest {
    private fun ready(preset: AgentPreset): AcpHarness = when (val launch = AcpHarness.forPreset(preset)) {
        is AcpLaunch.Ready -> launch.harness
        is AcpLaunch.Invalid -> throw AssertionError("Invalid: ${launch.problem}")
    }

    @Test
    fun claudeFlags_travelInTheSessionMeta_andTheModelInTheEnvironment() {
        val harness = ready(AgentPreset(
            AgentPresetId("c"), "Claude", AgentKind.ClaudeCode, AgentPermission.AcceptEdits,
            listOf("--model", "opus", "--verbose", "--max-turns=5")))

        assertEquals(
            Json.parseToJsonElement("""{"claudeCode":{"options":{"model":"opus","extraArgs":{"verbose":null,"max-turns":"5"}}}}"""),
            harness.sessionMeta)
        assertEquals(mapOf("ANTHROPIC_MODEL" to "opus"), harness.environment)
        assertEquals(listOf("-y", "@agentclientprotocol/claude-agent-acp"), harness.args)
        assertEquals("default", harness.modeFor(pairing = true))
        assertEquals("acceptEdits", harness.modeFor(pairing = false))
    }

    @Test
    fun claudeWithoutFlags_sendsNoMeta() {
        assertNull(ready(AgentPreset.ClaudeCode).sessionMeta)
    }

    @Test
    fun aClaudeArgumentThatIsNotAFlag_isRefused() {
        val launch = AcpHarness.forPreset(AgentPreset.ClaudeCode.copy(arguments = listOf("opus")))

        assertEquals(AcpLaunch.Invalid(AgentArgumentsProblem.NotAFlag("opus")), launch)
    }

    @Test
    fun codexArguments_goOnItsCommandLine_andItAsksInReadOnly() {
        val harness = ready(AgentPreset.Codex.copy(arguments = listOf("--profile", "work"), permission = AgentPermission.Bypass))

        assertEquals(listOf("-y", "@agentclientprotocol/codex-acp", "--profile", "work"), harness.args)
        assertEquals("read-only", harness.askingMode)
        assertEquals("full-access", harness.modeFor(pairing = false))
    }

    @Test
    fun aCommand_isFoundOnPath_withItsWindowsExtension() {
        val directory = Files.createTempDirectory("acp-path")
        try {
            Files.createFile(directory.resolve("npx.cmd"))
            val environment = AcpEnvironment(mapOf("Path" to directory.toString()), windows = true)

            assertEquals(directory.resolve("npx.cmd").toString(), environment.resolveCommand("npx"))
            assertNull(environment.resolveCommand("gemini"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
