package dev.pacenote.settings

import java.util.UUID

/** Identifies an agent preset. The built-in presets keep the ids the agents had before presets
 * existed, so a remembered pick still finds its agent. */
@JvmInline
value class AgentPresetId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun new(): AgentPresetId = AgentPresetId(UUID.randomUUID().toString().replace("-", ""))
    }
}

/** The agent CLI a preset runs. */
enum class AgentKind {
    ClaudeCode,
    Codex,
    Gemini;

    /** The CLI's product name, which is not translated. */
    val label: String
        get() = when (this) {
            ClaudeCode -> "Claude Code"
            Codex -> "Codex"
            Gemini -> "Gemini CLI"
        }
}

/** How much a preset's agent may do in a chat without asking. A pairing session always asks,
 * whatever the preset says. */
enum class AgentPermission {
    Ask,
    AcceptEdits,
    Bypass,
}

/** A named way to run an agent: which CLI, what it may do without asking, and the extra arguments
 * it is given. [command] starts the agent's adapter in place of the built-in command, none for that;
 * [environment] is set on its process over the IDE's own, a value naming others as `$NAME`. */
data class AgentPreset(
    val id: AgentPresetId,
    val name: String,
    val kind: AgentKind,
    val permission: AgentPermission,
    val arguments: List<String>,
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
) {
    companion object {
        val ClaudeCode = AgentPreset(AgentPresetId("claude"), "Claude Code", AgentKind.ClaudeCode, AgentPermission.Ask, emptyList())
        val Codex = AgentPreset(AgentPresetId("codex"), "Codex", AgentKind.Codex, AgentPermission.Ask, emptyList())
        val Gemini = AgentPreset(AgentPresetId("gemini"), "Gemini CLI", AgentKind.Gemini, AgentPermission.Ask, emptyList())

        val BuiltIn: List<AgentPreset> = listOf(ClaudeCode, Codex, Gemini)
    }
}
