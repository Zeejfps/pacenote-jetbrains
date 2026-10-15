package dev.diffdino.pairing.ui.settings

import dev.diffdino.pairing.settings.AgentArguments
import dev.diffdino.pairing.settings.AgentArgumentsParse
import dev.diffdino.pairing.settings.AgentArgumentsProblem
import dev.diffdino.pairing.settings.AgentKind
import dev.diffdino.pairing.settings.AgentPermission
import dev.diffdino.pairing.settings.AgentPreset
import dev.diffdino.pairing.settings.AgentPresetId

/** A preset as the settings page edits it, its arguments as typed, which may not parse yet. */
data class PresetDraft(
    val id: AgentPresetId,
    val name: String,
    val kind: AgentKind,
    val permission: AgentPermission,
    val arguments: String,
) {
    companion object {
        fun of(preset: AgentPreset) =
            PresetDraft(preset.id, preset.name, preset.kind, preset.permission, AgentArguments.format(preset.arguments))
    }
}

/** What stops a draft from being saved. */
sealed interface PresetProblem {
    data object NameRequired : PresetProblem

    data class Arguments(val problem: AgentArgumentsProblem) : PresetProblem
}

/** How checking the drafts came out. */
sealed interface PresetsCheck {
    data class Valid(val presets: List<AgentPreset>) : PresetsCheck

    data class Invalid(val problems: Map<AgentPresetId, PresetProblem>) : PresetsCheck
}

/**
 * The agent presets as the settings page edits them, held apart from the saved ones until applied:
 * a list of drafts and the agent the chat opens with. The last preset can't be removed, so there is
 * always an agent to open.
 */
class PresetsEditorModel(saved: List<AgentPreset>, chatAgent: AgentPresetId?) {
    var drafts: List<PresetDraft> = saved.map(PresetDraft::of)
        private set

    var chatAgent: AgentPresetId? = chatAgent
        private set

    fun add(name: String): PresetDraft {
        val created = PresetDraft(AgentPresetId.new(), name, AgentKind.ClaudeCode, AgentPermission.Ask, "")
        drafts = drafts + created
        return created
    }

    /** False when it is the last one, which stays. */
    fun remove(id: AgentPresetId): Boolean {
        if (drafts.size <= 1 || drafts.none { it.id == id }) return false
        drafts = drafts.filter { it.id != id }
        if (chatAgent == id) chatAgent = null
        return true
    }

    fun update(draft: PresetDraft) {
        drafts = drafts.map { if (it.id == draft.id) draft else it }
    }

    fun resetToDefaults() {
        drafts = AgentPreset.BuiltIn.map(PresetDraft::of)
        chatAgent = null
    }

    fun chooseChatAgent(id: AgentPresetId?) {
        chatAgent = id?.takeIf { pick -> drafts.any { it.id == pick } }
    }

    fun check(): PresetsCheck {
        val problems = LinkedHashMap<AgentPresetId, PresetProblem>()
        val presets = ArrayList<AgentPreset>(drafts.size)
        for (draft in drafts) {
            val name = draft.name.trim()
            if (name.isEmpty()) {
                problems[draft.id] = PresetProblem.NameRequired
                continue
            }
            when (val parsed = AgentArguments.parse(draft.arguments, draft.kind)) {
                is AgentArgumentsParse.Ok -> presets.add(AgentPreset(draft.id, name, draft.kind, draft.permission, parsed.arguments))
                is AgentArgumentsParse.Invalid -> problems[draft.id] = PresetProblem.Arguments(parsed.problem)
            }
        }
        return if (problems.isEmpty()) PresetsCheck.Valid(presets) else PresetsCheck.Invalid(problems)
    }

    /** Whether applying would change what is [saved]. Drafts that can't be saved count as changed. */
    fun isModified(saved: List<AgentPreset>, savedChatAgent: AgentPresetId?): Boolean {
        if (chatAgent != null && chatAgent != savedChatAgent) return true
        return when (val checked = check()) {
            is PresetsCheck.Valid -> checked.presets != saved
            is PresetsCheck.Invalid -> true
        }
    }
}
