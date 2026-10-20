package dev.pacenote.pairing

import dev.pacenote.acp.AcpEnvironment
import dev.pacenote.model.CodeQuote
import dev.pacenote.model.PairingPresentation
import dev.pacenote.model.PairingWorkspace
import dev.pacenote.settings.AgentPreset
import dev.pacenote.settings.ApprovalPreferences
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/** How the user starting a session came out. */
sealed interface PairingStart {
    data class Started(val conversation: AgentConversation) : PairingStart

    /** A live session already runs for the project. */
    data class AlreadyRunning(val conversation: AgentConversation) : PairingStart
}

/** How the agent starting a session came out. */
sealed interface AgentPairingStart {
    data class Started(val store: PairingStore) : AgentPairingStart

    /** A live session already runs for the project. */
    data class AlreadyRunning(val store: PairingStore) : AgentPairingStart

    /** No conversation with an agent is open for the project. */
    data object NoConversation : AgentPairingStart
}

/** Where the pairing tools find the project's live session. On the conversation's thread only. */
interface PairingSessionAccess {
    fun storeFor(): PairingStore?

    /** Opens a session the agent asked for, in the project's conversation. */
    fun startByAgent(goal: String): AgentPairingStart
}

/**
 * The project's agent conversation, at most one. A conversation stays until the user closes it,
 * across the pairing sessions run in it; hiding its panel leaves it running. Confined to one thread.
 */
class PairingSessions(
    private val create: (AgentPreset, AgentOpening) -> AgentConversation,
) : PairingSessionAccess {
    private val _conversation = MutableStateFlow<AgentConversation?>(null)
    private val _shown = MutableStateFlow<AgentConversation?>(null)
    private var hidden = false

    /** The project's conversation, its agent there or gone. */
    val conversation: StateFlow<AgentConversation?> = _conversation.asStateFlow()

    /** The project's conversation while its panel is shown. */
    val shown: StateFlow<AgentConversation?> = _shown.asStateFlow()

    /** The project's conversation while its agent is still there. */
    val liveConversation: AgentConversation? get() = _conversation.value?.takeIf { !it.isGone }

    override fun storeFor(): PairingStore? = _conversation.value?.session?.value?.store

    /** The user starts a session: in the project's conversation while its agent is there, which
     * keeps the agent it has, or in a new one with [harness]. */
    fun start(goal: String, harness: AgentPreset): PairingStart {
        val existing = liveConversation
        if (existing != null) {
            showPanel()
            if (existing.isPairing) return PairingStart.AlreadyRunning(existing)
            existing.beginSession(goal.trim())
            return PairingStart.Started(existing)
        }

        return PairingStart.Started(open(harness, AgentOpening.Pairing(goal.trim())))
    }

    /** The user asks the agent something, with code they sent along: in the project's conversation
     * while its agent is there, or in a new one with [harness]. */
    fun ask(text: String, quote: CodeQuote?, harness: AgentPreset): AgentConversation {
        val existing = liveConversation
        if (existing != null) {
            showPanel()
            existing.say(text, quote)
            return existing
        }

        return open(harness, AgentOpening.Chat(text.trim(), quote))
    }

    /** A new conversation with [preset] that waits for the user to say something, in place of the
     * project's conversation if it has one. */
    fun openChat(preset: AgentPreset): AgentConversation = open(preset, AgentOpening.Blank)

    /** A fresh conversation with the same agent in place of the project's, so the agent starts again
     * with none of what was said. Only with no pairing session running. */
    fun restart(): AgentConversation? {
        val conversation = _conversation.value ?: return null
        if (!canRestart(conversation)) return null
        return openChat(conversation.harness)
    }

    private fun open(harness: AgentPreset, opening: AgentOpening): AgentConversation {
        _conversation.value?.dispose()
        val conversation = create(harness, opening)
        _conversation.value = conversation
        hidden = false
        refresh()
        return conversation
    }

    override fun startByAgent(goal: String): AgentPairingStart {
        val conversation = liveConversation ?: return AgentPairingStart.NoConversation
        val running = conversation.session.value
        if (running != null && running.store.isLive) return AgentPairingStart.AlreadyRunning(running.store)
        showPanel()
        return AgentPairingStart.Started(conversation.startSession(goal.trim()).store)
    }

    /** Puts the project's conversation back on screen. */
    fun showPanel() {
        if (!hidden) return
        hidden = false
        refresh()
    }

    /** Takes the project's conversation off screen; its agent keeps running. */
    fun hidePanel() {
        if (_conversation.value == null || hidden) return
        hidden = true
        refresh()
    }

    val isPanelShown: Boolean get() = _conversation.value != null && !hidden

    /** Ends the project's conversation (its session, if one runs, and its agent) and takes it off
     * screen. */
    fun close() {
        val conversation = _conversation.value ?: return
        _conversation.value = null
        hidden = false
        conversation.dispose()
        refresh()
    }

    private fun refresh() {
        val shown = _conversation.value?.takeIf { !hidden }
        if (_shown.value !== shown) _shown.value = shown
    }

    fun dispose() {
        _conversation.value?.dispose()
        _conversation.value = null
        refresh()
    }

    companion object {
        /** Whether a conversation can start over: no pairing session running in it. */
        fun canRestart(conversation: AgentConversation): Boolean = !conversation.isPairing

        /** The factory the plugin runs conversations with: the editor as the surface of their
         * sessions, git for the snapshots, and an ACP driver, opened on the first goal. [scope] is
         * confined to [ui]. */
        fun factory(
            root: Path,
            presentation: () -> PairingPresentation,
            workspace: PairingWorkspace,
            endpoints: AgentEndpoints,
            environment: () -> AcpEnvironment,
            approvals: ApprovalPreferences,
            scope: CoroutineScope,
            ui: CoroutineContext,
        ): (AgentPreset, AgentOpening) -> AgentConversation = { preset, opening ->
            val conversation = AgentConversation(root, preset, { goal, transcript, deliver ->
                val store = PairingStore(goal, preset.name, transcript, presentation(), workspace, scope, deliver)
                PairingSession(store, scope)
            }, scope)
            val first = when (opening) {
                is AgentOpening.Pairing -> {
                    conversation.startSession(opening.goal)
                    AgentPrompt(PairingInstructions.opening(opening.goal, root.toString()))
                }
                is AgentOpening.Chat -> {
                    conversation.transcript.addFromUser(opening.text, opening.quote)
                    AgentPrompt(PairingInstructions.chatOpening(root.toString()) + "\n\n" + opening.text, opening.quote)
                }
                AgentOpening.Blank -> {
                    conversation.openOnFirstWords()
                    null
                }
            }
            conversation.attach(AcpPairingDriver.start(conversation, first, preset, endpoints, environment, approvals, ui))
            conversation
        }
    }
}
