package dev.pacenote.acp

import dev.pacenote.settings.AgentArguments
import dev.pacenote.settings.AgentArgumentsProblem
import dev.pacenote.settings.AgentKind
import dev.pacenote.settings.AgentPermission
import dev.pacenote.settings.AgentPreset
import dev.pacenote.settings.ClaudeFlag
import dev.pacenote.settings.ClaudeFlags
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Identifies an ACP harness preset. Persisted, so the values never change meaning. */
@JvmInline
value class AcpHarnessId(val value: String) {
    override fun toString(): String = value
}

/**
 * An agent the plugin can run over the Agent Client Protocol: the adapter command to start, and the
 * session mode in which that adapter hands writes to the client as permission requests instead of
 * deciding them itself. A harness with no such mode can't be guarded, and is refused. Some adapters
 * also take tools away outright through [sessionMeta], sent as `_meta` on `session/new`: a CLI's
 * own allow rules decide without asking the client, and a tool that isn't there can't be allowed.
 * Between pairing sessions a chat may run in a looser [chatMode]; a pairing session always runs in
 * the asking mode. [environment] is set on the adapter's process, over the IDE's own.
 */
data class AcpHarness(
    val id: AcpHarnessId,
    val label: String,
    val command: String,
    val args: List<String>,
    val askingMode: String,
    val sessionMeta: JsonObject? = null,
    val chatMode: String? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    /** The mode a turn runs in: the asking mode while a pairing session is live, else the chat's own. */
    fun modeFor(pairing: Boolean): String = if (pairing) askingMode else chatMode ?: askingMode

    companion object {
        val ClaudeCode = AcpHarness(
            AcpHarnessId("claude"), "Claude Code", "npx", listOf("-y", "@agentclientprotocol/claude-agent-acp"), "default")

        val Codex = AcpHarness(
            AcpHarnessId("codex"), "Codex", "npx", listOf("-y", "@agentclientprotocol/codex-acp"), "read-only")

        val Gemini = AcpHarness(
            AcpHarnessId("gemini"), "Gemini CLI", "gemini", listOf("--experimental-acp"), "default")

        /** The harness that runs [preset]. Claude Code's adapter takes no CLI arguments of its own,
         * so its flags travel in the session's `_meta`, which the adapter hands to the CLI; the
         * other adapters take them on their command line. */
        fun forPreset(preset: AgentPreset): AcpLaunch {
            val agent = of(preset.kind)
            val chatMode = modeOf(preset.kind, preset.permission)
            val id = AcpHarnessId(preset.id.value)
            return when (preset.kind) {
                AgentKind.ClaudeCode -> when (val flags = AgentArguments.claudeFlags(preset.arguments)) {
                    is ClaudeFlags.Invalid -> AcpLaunch.Invalid(flags.problem)
                    is ClaudeFlags.Ok -> AcpLaunch.Ready(agent.copy(
                        id = id,
                        label = preset.name,
                        chatMode = chatMode,
                        sessionMeta = claudeMeta(flags.flags),
                        environment = claudeEnvironment(flags.flags),
                    ))
                }
                AgentKind.Codex, AgentKind.Gemini -> AcpLaunch.Ready(agent.copy(
                    id = id, label = preset.name, chatMode = chatMode, args = agent.args + preset.arguments,
                ))
            }
        }

        private fun of(kind: AgentKind): AcpHarness = when (kind) {
            AgentKind.ClaudeCode -> ClaudeCode
            AgentKind.Codex -> Codex
            AgentKind.Gemini -> Gemini
        }

        private fun modeOf(kind: AgentKind, permission: AgentPermission): String = when (kind) {
            AgentKind.ClaudeCode -> when (permission) {
                AgentPermission.Ask -> "default"
                AgentPermission.AcceptEdits -> "acceptEdits"
                AgentPermission.Bypass -> "bypassPermissions"
            }
            AgentKind.Codex -> when (permission) {
                AgentPermission.Ask -> "read-only"
                AgentPermission.AcceptEdits -> "auto"
                AgentPermission.Bypass -> "full-access"
            }
            AgentKind.Gemini -> when (permission) {
                AgentPermission.Ask -> "default"
                AgentPermission.AcceptEdits -> "autoEdit"
                AgentPermission.Bypass -> "yolo"
            }
        }

        // The adapter settles the session's model from ANTHROPIC_MODEL, else the model in the user's
        // Claude settings, and re-applies it over the SDK's --model; only the variable outranks settings.
        private fun claudeEnvironment(flags: List<ClaudeFlag>): Map<String, String> {
            val environment = HashMap<String, String>()
            for (flag in flags)
                if (flag.name == "model" && flag.value != null) environment["ANTHROPIC_MODEL"] = flag.value
            return environment
        }

        // The SDK takes model and effort as options of their own and passes each other flag to the
        // CLI as --name [value]; a null value is a flag with none.
        private fun claudeMeta(flags: List<ClaudeFlag>): JsonObject? {
            if (flags.isEmpty()) return null
            val options = LinkedHashMap<String, JsonElement>()
            val extra = LinkedHashMap<String, JsonElement>()
            for (flag in flags) {
                if ((flag.name == "model" || flag.name == "effort") && flag.value != null) options[flag.name] = JsonPrimitive(flag.value)
                else extra[flag.name] = flag.value?.let(::JsonPrimitive) ?: JsonNull
            }

            if (extra.isNotEmpty()) options["extraArgs"] = JsonObject(extra)
            return buildJsonObject {
                put("claudeCode", buildJsonObject { put("options", JsonObject(options)) })
            }
        }
    }
}

/** Whether a preset can be run, and the harness that runs it. */
sealed interface AcpLaunch {
    data class Ready(val harness: AcpHarness) : AcpLaunch

    data class Invalid(val problem: AgentArgumentsProblem) : AcpLaunch
}
