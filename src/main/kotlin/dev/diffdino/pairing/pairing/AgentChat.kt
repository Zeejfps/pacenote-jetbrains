package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.settings.AgentPreferences
import dev.diffdino.pairing.settings.AgentPreset

/** How pressing the agent chat came out. */
sealed interface AgentChatPress {
    /** The project already had a conversation, which was shown or hidden. */
    data object Existing : AgentChatPress

    /** A conversation was opened with the agent picked before. */
    data class Opened(val conversation: AgentConversation) : AgentChatPress

    /** No agent has been picked yet: the caller asks which. */
    data object NeedsAgent : AgentChatPress
}

/** How asking the agent something came out. */
sealed interface AgentChatAsk {
    data class Asked(val conversation: AgentConversation) : AgentChatAsk

    /** No agent is talking and none has been picked: the caller asks which. */
    data object NeedsAgent : AgentChatAsk
}

/** One agent to pick from: [select] opens or shows its conversation. */
data class AgentMenuItem(val label: String, val enabled: Boolean, val checked: Boolean, val select: () -> Unit)

/**
 * The way into the project's conversation with an agent: shows and hides it, and opens one with the
 * agent last picked when there is none. Confined to the conversation's thread.
 */
class AgentChat(private val sessions: PairingSessions, private val preferences: AgentPreferences) {
    /** The presets to pick an agent from. */
    val presets: List<AgentPreset> get() = preferences.presets

    /** The preset picked last, if it is still there. */
    val remembered: AgentPreset?
        get() = preferences.chatAgent?.let { id -> presets.firstOrNull { it.id == id } }

    /** The preset to start with when there is no way to ask: the one picked last, else the first on
     * offer. */
    val default: AgentPreset get() = remembered ?: presets.firstOrNull() ?: AgentPreset.ClaudeCode

    /** Shows or hides the project's conversation, or opens one with the agent picked last. */
    fun press(): AgentChatPress = go(hideShown = true)

    /** Shows the project's conversation, or opens one with the agent picked last. */
    fun reveal(): AgentChatPress = go(hideShown = false)

    private fun go(hideShown: Boolean): AgentChatPress {
        if (sessions.conversation.value != null) {
            if (hideShown && sessions.isPanelShown) sessions.hidePanel() else sessions.showPanel()
            return AgentChatPress.Existing
        }

        val preset = remembered ?: return AgentChatPress.NeedsAgent
        return AgentChatPress.Opened(open(preset))
    }

    /** Shows the project's conversation with [preset], opening one in place of a conversation with
     * another agent, and remembers the pick. */
    fun open(preset: AgentPreset): AgentConversation {
        preferences.rememberChatAgent(preset.id)
        val existing = sessions.conversation.value
        if (existing != null && (runs(existing, preset) || existing.isPairing)) {
            sessions.showPanel()
            return existing
        }

        return sessions.openChat(preset)
    }

    /** Says something to the project's agent, with code the user sent along: the one talking now,
     * else the one picked last. */
    fun ask(text: String, quote: CodeQuote?): AgentChatAsk {
        val harness = sessions.liveConversation?.harness ?: remembered ?: return AgentChatAsk.NeedsAgent
        return AgentChatAsk.Asked(sessions.ask(text, quote, harness))
    }

    /** The agents to pick from, the one talking now or picked last marked. Another agent can't take
     * over while a pairing session runs. [then] follows a pick, with the conversation it shows. */
    fun agentMenu(then: ((AgentConversation) -> Unit)? = null): List<AgentMenuItem> {
        val current = sessions.conversation.value
        val marked = current?.takeIf { !it.isGone }?.harness?.id ?: remembered?.id
        val locked = current?.isPairing == true
        return presets.map { preset ->
            AgentMenuItem(
                preset.name,
                enabled = !locked || preset.id == marked,
                checked = preset.id == marked,
                select = {
                    val conversation = open(preset)
                    then?.invoke(conversation)
                },
            )
        }
    }

    private fun runs(conversation: AgentConversation, preset: AgentPreset): Boolean =
        !conversation.isGone && conversation.harness.id == preset.id
}
