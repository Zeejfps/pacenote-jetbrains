package dev.pacenote.pairing

import dev.pacenote.acp.AcpSessionUpdate
import dev.pacenote.model.CodeQuote
import dev.pacenote.model.PairingPhase
import dev.pacenote.settings.AgentPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What the conversation's agent is doing, apart from any pairing session. */
sealed interface AgentPhase {
    /** The agent is being started. */
    data object Starting : AgentPhase

    /** The agent is up. [busy] while a turn is in flight. */
    data class Running(val busy: Boolean) : AgentPhase

    /** The agent could not be started, or exited. */
    data class Gone(val reason: String) : AgentPhase
}

/** A turn for the agent: prose, and code the user sent along with it. */
data class AgentPrompt(val text: String, val quote: CodeQuote? = null) {
    /** The whole turn as markdown, for an agent that takes prose alone. */
    fun toMarkdown(root: Path): String = if (quote == null) text else text + "\n\n" + quote.toMarkdown { relativeTo(root, it) }

    companion object {
        /** A path as the agent is told it: relative to the project, or as it is outside it. */
        fun relativeTo(root: Path, absolutePath: String): String = try {
            val relative = root.toAbsolutePath().normalize().relativize(Path.of(absolutePath).toAbsolutePath().normalize())
            val text = relative.toString()
            if (text.startsWith("..") || relative.isAbsolute) absolutePath else text.replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            absolutePath
        } catch (_: InvalidPathException) {
            absolutePath
        }
    }
}

/** What a new conversation opens with. */
sealed interface AgentOpening {
    /** A pairing session on this goal. */
    data class Pairing(val goal: String) : AgentOpening

    /** Something the user asked, with code they sent along. */
    data class Chat(val text: String, val quote: CodeQuote?) : AgentOpening

    /** Nothing yet: the agent starts and waits for the user's first words. */
    data object Blank : AgentOpening
}

/** Runs a conversation's agent. */
interface AgentDriver {
    /** Gives the agent a turn: something the user said, or that a session began. Queued behind a
     * turn already in flight. */
    fun tell(prompt: AgentPrompt)

    /** Asks the agent to stop the turn in flight; the turn ends when it does. Returns at once. */
    fun stopTurn()

    /** Stops the agent. Returns at once; the process goes in the background. */
    fun dispose()
}

/** One pairing session: the loop and the commands the panel gives it. The agent that drives it
 * belongs to the conversation the session runs in. */
class PairingSession(val store: PairingStore, private val scope: CoroutineScope) {
    /** Next: finishes the open stop with the file as it is. */
    fun next() {
        scope.launch { store.done() }
    }

    fun skip() = store.skip()

    /** Puts the agent's code in and stays on the stop. */
    fun accept() {
        scope.launch { store.accept() }
    }

    fun acceptAndNext() {
        scope.launch { store.acceptAndNext() }
    }

    /** Ends the session; the conversation goes on. */
    fun end() = store.endByUser()

    /** Puts the caret back where the open stop's code goes. */
    fun revealStop() = store.revealStop()

    /** Ends the session and lets it go. */
    fun dispose() {
        store.endByUser()
        store.dispose()
    }
}

/**
 * A project's agent and what the user and it said, across pairing sessions and between them: once
 * a session ends the agent stays, to be asked for a commit or a push, or to start the next session.
 * Confined to the thread of [scope]'s dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversation(
    /** The project's directory, which the agent works in. */
    val root: Path,
    val harness: AgentPreset,
    private val newSession: (goal: String, transcript: AgentTranscript, deliver: (PairingAction) -> Unit) -> PairingSession,
    scope: CoroutineScope,
) {
    private val own = scope + SupervisorJob(scope.coroutineContext[Job])
    private val _phase = MutableStateFlow<AgentPhase>(AgentPhase.Starting)
    private val _session = MutableStateFlow<PairingSession?>(null)
    private var driver: AgentDriver? = null
    private var sessionWatch: Job? = null
    private var toldOpening = true
    private var disposed = false
    private val tracker = AgentActivityTracker()

    val transcript = AgentTranscript()

    /** What the agent is doing in the turn in flight; null between turns. */
    val activity: StateFlow<AgentActivity?> get() = tracker.activity

    /** Whether the user stopped the turn in flight, which hasn't ended yet. */
    val isStopping: Boolean get() = tracker.activity.value?.stopping == true

    val phase: StateFlow<AgentPhase> = _phase.asStateFlow()

    /** The pairing session running in the conversation, if one is. */
    val session: StateFlow<PairingSession?> = _session.asStateFlow()

    /** True while the agent is at work and nothing it writes has landed yet. */
    val isComposing: StateFlow<Boolean> = combine(
        transcript.openNarration,
        _session.flatMapLatest { it?.store?.phase ?: flowOf(null) },
        _phase,
    ) { narration, sessionPhase, phase -> composing(narration == null, sessionPhase, phase) }
        .stateIn(own, SharingStarted.Eagerly, false)

    /** True while the agent is at work on something the user waits for, whatever it has written so far. */
    val isWorking: StateFlow<Boolean> = combine(
        _session.flatMapLatest { it?.store?.phase ?: flowOf(null) },
        _phase,
    ) { sessionPhase, phase -> composing(true, sessionPhase, phase) }
        .stateIn(own, SharingStarted.Eagerly, false)

    /** Whether what the user types in the panel reaches the agent. */
    val takesChat: StateFlow<Boolean> = _phase.map { it !is AgentPhase.Gone }.stateIn(own, SharingStarted.Eagerly, true)

    val isPairing: Boolean get() = _session.value?.store?.isLive == true

    val isGone: Boolean get() = _phase.value is AgentPhase.Gone

    fun attach(driver: AgentDriver) {
        this.driver = driver
    }

    /** For a conversation opened blank: what the agent is told of the plugin goes with the user's
     * first words, or with the first session they start. */
    fun openOnFirstWords() {
        toldOpening = false
    }

    /** Opens a pairing session in the conversation. Only while none is live. */
    fun startSession(goal: String): PairingSession {
        check(!isPairing) { "A pairing session is already running." }
        transcript.add(PairingMessage.SessionStarted(goal))
        val session = newSession(goal, transcript, ::deliver)
        sessionWatch?.cancel()
        _session.value = session
        if (_phase.value is AgentPhase.Running) session.store.markRunning()
        // Launched: the store tells the agent the session ended after the phase changes, and must
        // still be whole then.
        sessionWatch = own.launch {
            session.store.phase.first { !PairingStore.isLive(it) }
            retire(session)
        }
        return session
    }

    // What the user did in the session reaches the agent as its next turn, queued behind one in flight.
    private fun deliver(action: PairingAction) {
        driver?.tell(AgentPrompt(PairingInstructions.move(action) { AgentPrompt.relativeTo(root, it) }))
    }

    /** The user starts a session in a conversation already under way: the agent is told. */
    fun beginSession(goal: String): PairingSession {
        val session = startSession(goal)
        driver?.tell(AgentPrompt(
            if (toldOpening) PairingInstructions.resumed(goal, root.toString())
            else PairingInstructions.opening(goal, root.toString())))
        toldOpening = true
        return session
    }

    private fun retire(session: PairingSession) {
        if (disposed || _session.value !== session) return
        sessionWatch = null
        transcript.add(PairingMessage.SessionOver(session.store.phase.value))
        _session.value = null
        session.dispose()
    }

    /** Something the user said, with code they sent along: to the session's loop while one runs,
     * else straight to the agent. */
    fun say(text: String, quote: CodeQuote? = null) {
        if (disposed || text.isBlank() || isGone) return
        val session = _session.value
        if (session != null && session.store.isLive) {
            session.store.say(text, quote)
            return
        }

        val said = text.trim()
        transcript.addFromUser(said, quote)
        driver?.tell(AgentPrompt(if (toldOpening) said else PairingInstructions.chatOpening(root.toString()) + "\n\n" + said, quote))
        toldOpening = true
    }

    // ── the driver's side ────────────────────────────────────────────────────────────────────

    fun markRunning() {
        if (_phase.value !is AgentPhase.Starting) return
        _phase.value = AgentPhase.Running(false)
        _session.value?.store?.markRunning()
    }

    fun beginTurn() {
        tracker.beginTurn()
        transcript.beginAgentTurn()
        if (_phase.value is AgentPhase.Running) _phase.value = AgentPhase.Running(true)
        _session.value?.store?.markTurnStarted()
    }

    fun endTurn() {
        tracker.endTurn()
        transcript.closeNarration()
        if (_phase.value is AgentPhase.Running) _phase.value = AgentPhase.Running(false)
        _session.value?.store?.markTurnEnded()
    }

    /** Something the agent sent while its turn runs: a step it took, reasoning, or prose. */
    fun heard(update: AcpSessionUpdate) {
        when (update) {
            is AcpSessionUpdate.ToolCall -> tracker.toolCall(update.id, update.title, update.kind, update.status)
            is AcpSessionUpdate.ThoughtChunk -> tracker.thought()
            is AcpSessionUpdate.MessageChunk -> tracker.wrote()
            is AcpSessionUpdate.Plan, is AcpSessionUpdate.Other -> {}
        }
    }

    /** The user stops the agent's turn: a question it was waiting on is withdrawn, and the agent
     * waits for what the user says next. */
    fun stopTurn() {
        val activity = tracker.activity.value
        if (disposed || activity == null || activity.stopping) return
        tracker.stopping()
        for (message in transcript.messages.value) (message as? PairingMessage.Approval)?.pending?.cancel()
        driver?.stopTurn()
    }

    /** The agent could not be started, or died; a live session fails with it. */
    fun fail(reason: String) {
        if (!lose(reason)) return
        val session = _session.value
        if (session != null && session.store.isLive) session.store.fail(reason)
        else transcript.addNotice(reason, NoticeTone.Error)
    }

    /** The agent went away without failing. */
    fun disconnect(reason: String) {
        if (!lose(reason)) return
        val session = _session.value
        if (session != null && session.store.isLive) session.store.markDisconnected(reason)
        else transcript.addNotice(reason, NoticeTone.Info)
    }

    private fun lose(reason: String): Boolean {
        if (disposed || _phase.value is AgentPhase.Gone) return false
        transcript.closeNarration()
        tracker.endTurn()
        _phase.value = AgentPhase.Gone(reason)
        return true
    }

    /** Ends the session, if one runs, and stops the agent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        sessionWatch?.cancel()
        _session.value?.dispose()
        _session.value = null
        own.cancel()
        driver?.dispose()
    }

    private companion object {
        fun composing(noNarration: Boolean, sessionPhase: PairingPhase?, phase: AgentPhase): Boolean {
            if (!noNarration) return false
            return if (sessionPhase != null) when (sessionPhase) {
                PairingPhase.Starting -> true
                is PairingPhase.Running -> !sessionPhase.waiting
                is PairingPhase.Disconnected, is PairingPhase.Ended, is PairingPhase.Failed -> false
            } else when (phase) {
                AgentPhase.Starting -> true
                is AgentPhase.Running -> phase.busy
                is AgentPhase.Gone -> false
            }
        }
    }
}
