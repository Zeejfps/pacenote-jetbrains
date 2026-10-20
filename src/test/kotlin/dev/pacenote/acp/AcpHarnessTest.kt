package dev.pacenote.acp

import dev.pacenote.settings.AgentArgumentsProblem
import dev.pacenote.settings.AgentKind
import dev.pacenote.settings.AgentPermission
import dev.pacenote.settings.AgentPreset
import dev.pacenote.settings.AgentPresetId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
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

    @Test
    fun aPresetsCommand_replacesTheBuiltInOne_withItsArgumentsAfter() {
        val harness = ready(AgentPreset.Codex.copy(
            command = listOf("C:/tools/node/npx.cmd", "-y", "@agentclientprotocol/codex-acp@0.3.1"),
            arguments = listOf("--profile", "work"),
        ))

        assertEquals("C:/tools/node/npx.cmd", harness.command)
        assertEquals(listOf("-y", "@agentclientprotocol/codex-acp@0.3.1", "--profile", "work"), harness.args)
        assertEquals(listOf("npx", "-y", "@agentclientprotocol/codex-acp"), AcpHarness.builtInCommand(AgentKind.Codex))
    }

    @Test
    fun aPresetsEnvironment_goesOnTheProcess_underClaudesModel() {
        val harness = ready(AgentPreset.ClaudeCode.copy(
            command = listOf("claude-agent-acp"),
            arguments = listOf("--model", "opus"),
            environment = mapOf("HTTPS_PROXY" to "http://proxy:8080", "ANTHROPIC_MODEL" to "sonnet"),
        ))

        assertEquals("claude-agent-acp", harness.command)
        assertEquals(emptyList<String>(), harness.args)
        assertEquals(mapOf("HTTPS_PROXY" to "http://proxy:8080", "ANTHROPIC_MODEL" to "opus"), harness.environment)
    }

    @Test
    fun anEnvironmentSetOverTheIdes_replacesANameWhateverItsCase_onWindows() {
        val ide = AcpEnvironment(mapOf("Path" to "C:\\Windows", "HOME" to "C:\\Users\\me"), windows = true)

        val launched = ide.with(mapOf("PATH" to "C:\\node;%Path%", "NODE_HOME" to "\${HOME}\\node", "TOKEN" to "\$MISSING"))

        assertEquals(mapOf("HOME" to "C:\\Users\\me", "PATH" to "C:\\node;C:\\Windows", "NODE_HOME" to "C:\\Users\\me\\node", "TOKEN" to ""),
            launched.variables)
    }

    @Test
    fun percentReferences_areLeftAsTheyAre_elsewhere() {
        val ide = AcpEnvironment(mapOf("PATH" to "/usr/bin"), windows = false)

        assertEquals("/opt/node/bin:/usr/bin %PATH%", ide.with(mapOf("PATH" to "/opt/node/bin:\$PATH %PATH%")).variables["PATH"])
    }

    @Test
    fun theCommand_isLookedUpOnThePathThePresetSets() {
        val directory = Files.createTempDirectory("acp-preset-path")
        try {
            Files.createFile(directory.resolve("claude-agent-acp"))
            val launched = AcpEnvironment(mapOf("PATH" to "/nowhere"), windows = false)
                .with(mapOf("PATH" to "$directory${File.pathSeparator}\$PATH"))

            assertEquals(directory.resolve("claude-agent-acp").toString(), launched.resolveCommand("claude-agent-acp"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
