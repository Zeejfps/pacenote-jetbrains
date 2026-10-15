package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.model.DraftOutcome
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftState
import dev.diffdino.pairing.model.EditorCaret
import dev.diffdino.pairing.model.FileCreation
import dev.diffdino.pairing.model.Milestone
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.model.PairingPresentation
import dev.diffdino.pairing.model.PairingStop
import dev.diffdino.pairing.model.PairingWorkspace
import dev.diffdino.pairing.model.RoadmapChange
import dev.diffdino.pairing.model.RoadmapEntry
import dev.diffdino.pairing.model.SnapshotResult
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopMiss
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.SuggestionActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * One pairing session's loop: the goal, the roadmap, the stop the user is on, and the hand-off
 * between the agent and the user. The agent proposes a stop and ends its turn; the user edits and
 * presses Next, and their diff since the stop was shown goes to the agent as its next turn.
 *
 * Nothing waits on the user: an agent with a stop open costs nothing until they move. The moves go
 * out in the order the user made them, so a Done is never lost behind a question asked after it.
 * One open stop ever: a stop proposed while another is open is refused unless it says it replaces
 * it. Every member is confined to the thread of [scope]'s dispatcher.
 */
class PairingStore(
    val goal: String,
    /** Who drives the session, as the panel names it. */
    val harness: String,
    private val transcript: AgentTranscript,
    private val presentation: PairingPresentation,
    private val workspace: PairingWorkspace,
    private val scope: CoroutineScope,
    private val deliver: (PairingAction) -> Unit,
) {
    private val lifetime = scope + SupervisorJob(scope.coroutineContext[Job])

    private val _phase = MutableStateFlow<PairingPhase>(PairingPhase.Starting)
    private val _roadmap = MutableStateFlow<List<RoadmapEntry>>(emptyList())
    private val _stop = MutableStateFlow<OpenStop?>(null)
    private val _activity = MutableStateFlow(StopActivity.Idle)

    private var milestones: List<Milestone> = emptyList()
    private var stopsSent = 0

    // An agent call is opening a stop across suspensions; a second one meanwhile would work from
    // the state the first is about to replace.
    private var changingStop = false
    private var turn = AgentTurn.Unseen
    private var handedOver = false
    private var disposed = false

    val phase: StateFlow<PairingPhase> = _phase.asStateFlow()

    /** The agent's current roadmap, with what its last revision added or dropped. */
    val roadmap: StateFlow<List<RoadmapEntry>> = _roadmap.asStateFlow()

    /** The stop the user is on, or null between stops. */
    val stop: StateFlow<OpenStop?> = _stop.asStateFlow()

    /** What Next or Accept is busy with. */
    val activity: StateFlow<StopActivity> = _activity.asStateFlow()

    /** Where the user is in the editor. */
    val caret: StateFlow<EditorCaret?> get() = presentation.caret

    val draftNeeds: StateFlow<List<String>> get() = presentation.draftNeeds

    /** The repository-relative form of an absolute path, or null outside it. Any thread. */
    fun relative(absolutePath: String): String? = presentation.relative(absolutePath)

    /** Whether the session can still take moves from either side. */
    val isLive: Boolean get() = isLive(_phase.value)

    /** Whether the agent has left the user something to act on or read since its turn began: a
     * stop, or a reply. A turn that ends without either left the user nothing. */
    val hasHandedOver: Boolean get() = _stop.value != null || handedOver

    // ── the agent's side ─────────────────────────────────────────────────────────────────────

    /** The agent is up and driving. */
    fun markRunning() {
        if (_phase.value is PairingPhase.Starting) _phase.value = PairingPhase.Running(false)
    }

    /** The agent's turn began. From here the user's turn is marked by the turn ending, not by what
     * the agent sends along the way. */
    fun markTurnStarted() {
        turn = AgentTurn.Running
        handedOver = false
        setWaiting(false)
    }

    /** The agent's turn ended: whatever comes next is the user's. */
    fun markTurnEnded() {
        turn = AgentTurn.Over
        setWaiting(true)
    }

    /** Replaces the roadmap, marking what the revision added and what it dropped. */
    fun setRoadmap(milestones: List<Milestone>) {
        check(!disposed) { "The pairing session is over." }
        _roadmap.value = RoadmapDiff.compare(this.milestones, milestones)
        this.milestones = milestones
    }

    /** Opens a stop: places it and the agent's code for it in the editor, and captures the working
     * tree it starts from. Refused while another stop is open, unless [replace]. */
    suspend fun openStop(target: StopTarget, title: String, reason: String, draft: DraftRequest, replace: Boolean): StopOpening {
        check(!disposed) { "The pairing session is over." }
        if (!isLive) return StopOpening.Refused("The session has ended.")
        val open = _stop.value
        if (open != null && !replace)
            return StopOpening.Refused(
                "Stop ${open.stop.number} (\"${open.stop.title}\") is still open. End your turn: the user's move on it " +
                    "comes to you as your next message. Or pass replace: true to take it back and open this one instead.")
        if (_activity.value != StopActivity.Idle)
            return StopOpening.Refused("The user is finishing the open stop right now. End your turn: their move comes to you next.")
        if (changingStop) return StopOpening.Refused("Another pairing call is still changing the stop. Wait for it to return.")

        changingStop = true
        try {
            return openStopLocked(target, title, reason, draft, replace)
        } finally {
            changingStop = false
        }
    }

    private suspend fun openStopLocked(
        target: StopTarget, title: String, reason: String, request: DraftRequest, replace: Boolean,
    ): StopOpening {
        val location = when (val placement = presentation.locate(target)) {
            is StopPlacement.Placed -> placement.location
            is StopPlacement.Missed -> return StopOpening.Refused(StopMiss.describe(placement.miss))
        }

        val draft = when (val placement = DraftPlacing.place(location, request)) {
            is DraftPlacement.Placed -> placement.draft
            is DraftPlacement.Refused -> return StopOpening.Refused(placement.message)
        }

        var created: String? = null
        if (location is StopLocation.NewFile) {
            when (val ensured = workspace.ensureEmptyFile(target.path)) {
                FileCreation.Created -> created = target.path
                FileCreation.AlreadyEmpty -> created = if (_stop.value?.createdFile == target.path) target.path else null
                is FileCreation.Refused -> return StopOpening.Refused(ensured.reason)
            }
        }

        val baseline = when (val captured = workspace.capture()) {
            is SnapshotResult.Ok -> captured.value
            is SnapshotResult.Failed -> {
                takeBack(created)
                return StopOpening.Refused("The working tree could not be read: ${captured.reason}")
            }
        }

        if (!isLive) {
            takeBack(created)
            return StopOpening.Refused("The session has ended.")
        }

        if (_activity.value != StopActivity.Idle || (_stop.value != null && !replace)) {
            takeBack(created)
            return StopOpening.Refused("The user moved on while the stop was being placed. End your turn: their move comes to you next.")
        }

        val previous = _stop.value?.createdFile
        if (previous != null && previous != created) takeBack(previous)
        val stop = PairingStop(++stopsSent, target, title, reason)
        val opened = OpenStop(stop, location, baseline, draft, DraftState.Offered, created)
        _stop.value = opened
        presentation.revealDraft(location, draft)
        presentation.showDraft(location, draft, SuggestionActions(
            accept = { lifetime.launch { accept() } },
            acceptAndNext = { lifetime.launch { acceptAndNext() } },
            next = { lifetime.launch { done() } },
        ))
        transcript.closeNarration()
        handOver()
        return StopOpening.Opened(opened)
    }

    /** The agent finished: the stop closes, the summary stays up. */
    fun end(summary: String?) {
        if (!isLive) return
        finish(PairingPhase.Ended(summary?.takeIf { it.isNotBlank() }))
    }

    /** The agent could not be started, or died. */
    fun fail(reason: String) {
        if (!isLive) return
        finish(PairingPhase.Failed(reason))
    }

    /** The agent went quiet without ending; the user can only close the session. */
    fun markDisconnected(reason: String) {
        if (!isLive) return
        finish(PairingPhase.Disconnected(reason))
    }

    /** A whole reply from the agent, said through `pairing_say`. */
    fun addReply(markdown: String) {
        if (disposed) return
        transcript.addReply(markdown)
        handOver()
    }

    fun addNotice(text: String, tone: NoticeTone) {
        if (!disposed) transcript.addNotice(text, tone)
    }

    // ── the user's side ──────────────────────────────────────────────────────────────────────

    /** Finishes the open stop (Next): saves what was typed and hands the agent the diff since the
     * stop was shown. */
    suspend fun done() {
        val open = _stop.value
        if (disposed || open == null || _activity.value != StopActivity.Idle) return
        _activity.value = StopActivity.Checking
        close(open.stop.number)
    }

    /** Puts the agent's code for the open stop into the file as one undo step, and stays on the
     * stop: the user may still change it before Next. Answers whether the code is in. */
    suspend fun accept(): Boolean {
        val open = _stop.value
        if (disposed || open == null || _activity.value != StopActivity.Idle) return false
        if (open.draftState is DraftState.Taken) return true

        _activity.value = StopActivity.Accepting
        presentation.revealDraft(open.location, open.draft)
        val taken = presentation.takeDraft(open.location)
        val text = if (taken) presentation.readText(open.location) else null

        _activity.value = StopActivity.Idle
        if (!stillOn(open.stop.number)) return false
        if (!taken) {
            addNotice("The agent's code could not be put into ${open.stop.target.path}. Open the file and accept again.", NoticeTone.Error)
            return false
        }

        _stop.value = _stop.value?.copy(draftState = DraftState.Taken(text))
        presentation.clearDraft()
        return true
    }

    /** Accepts the agent's code and goes straight on, as Next does. */
    suspend fun acceptAndNext() {
        if (accept()) done()
    }

    // Saves, takes the diff since the stop's baseline, and hands it over as Done.
    private suspend fun close(number: Int) {
        val problems = presentation.saveUnsaved().toMutableList()
        val baseline = _stop.value?.baseline ?: return idle()

        var diff = ""
        when (val after = workspace.capture()) {
            is SnapshotResult.Ok -> {
                if (!stillOn(number)) return idle()
                when (val compared = workspace.diff(baseline, after.value)) {
                    is SnapshotResult.Ok -> diff = compared.value
                    is SnapshotResult.Failed -> problems.add("The diff could not be taken: ${compared.reason}")
                }
                if (!stillOn(number)) return idle()
            }
            is SnapshotResult.Failed -> {
                if (!stillOn(number)) return idle()
                problems.add("The working tree could not be read: ${after.reason}")
            }
        }

        val outcome = outcome(_stop.value ?: return idle())
        if (!stillOn(number)) return idle()

        _activity.value = StopActivity.Idle
        _stop.value = null
        presentation.clearDraft()
        deliverAction(PairingAction.Done(number, diff, outcome, problems))
    }

    private fun idle() {
        _activity.value = StopActivity.Idle
    }

    // Accepted code the user changed afterwards is worth the agent's attention: the diff shows how
    // they would rather it read.
    private suspend fun outcome(open: OpenStop): DraftOutcome = when (val state = open.draftState) {
        DraftState.Offered -> DraftOutcome.NotAccepted
        is DraftState.Taken -> {
            if (state.fileText == null) DraftOutcome.AcceptedAsIs
            else {
                val now = presentation.readText(open.location)
                if (now == null || now == state.fileText) DraftOutcome.AcceptedAsIs else DraftOutcome.AcceptedThenEdited
            }
        }
    }

    private fun stillOn(number: Int): Boolean = !disposed && _stop.value?.stop?.number == number

    /** Passes on the open stop without the diff. */
    fun skip() {
        val open = _stop.value
        if (disposed || open == null || _activity.value != StopActivity.Idle) return
        _stop.value = null
        presentation.clearDraft()
        takeBack(open.createdFile)
        deliverAction(PairingAction.Skipped(open.stop.number))
    }

    /** Says something to the agent, with code the user sent along. It joins the conversation the
     * agent keeps for the session, so "I went with an event instead" said here is in its mind when
     * it reads the diff Next hands it. */
    fun say(text: String, quote: CodeQuote? = null) {
        if (disposed || !isLive || text.isBlank()) return
        val said = text.trim()
        transcript.addFromUser(said, quote)
        val told = if (quote == null) said else said + "\n\n" + quote.toMarkdown { relative(it) ?: it }
        deliverAction(PairingAction.Message(stopNumber, told, presentation.caret.value))
    }

    /** The user ends the session, and the agent is told. */
    fun endByUser() {
        if (!isLive) return
        val at = stopNumber
        finish(PairingPhase.Ended(null))
        deliver(PairingAction.Ended(at))
    }

    /** Puts the caret back where the open stop's code goes. */
    fun revealStop() {
        val open = _stop.value ?: return
        presentation.revealDraft(open.location, open.draft)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        lifetime.cancel()
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private val stopNumber: Int get() = _stop.value?.stop?.number ?: 0

    private fun finish(phase: PairingPhase) {
        takeBack(_stop.value?.createdFile)
        _stop.value = null
        presentation.clearDraft()
        _activity.value = StopActivity.Idle
        transcript.closeNarration()
        _phase.value = phase
    }

    // A file a stop created for the user to fill goes again if they moved on and left it empty,
    // even when the session is being let go.
    private fun takeBack(createdFile: String?) {
        if (createdFile != null) scope.launch(NonCancellable) { workspace.removeIfEmpty(createdFile) }
    }

    private fun deliverAction(action: PairingAction) {
        setWaiting(false)
        deliver(action)
    }

    // The agent gave the user something. Where its turns are seen, the turn ending is what hands
    // over; an agent whose turns aren't reported has nothing else to go on.
    private fun handOver() {
        handedOver = true
        if (turn != AgentTurn.Running) setWaiting(true)
    }

    private fun setWaiting(waiting: Boolean) {
        val running = _phase.value as? PairingPhase.Running ?: return
        if (running.waiting != waiting) _phase.value = running.copy(waiting = waiting)
    }

    private enum class AgentTurn {
        /** No turn has been reported: the agent runs where its turns can't be seen. */
        Unseen,
        Running,
        Over,
    }

    companion object {
        fun isLive(phase: PairingPhase): Boolean = when (phase) {
            PairingPhase.Starting, is PairingPhase.Running -> true
            is PairingPhase.Disconnected, is PairingPhase.Ended, is PairingPhase.Failed -> false
        }
    }
}

/** A roadmap revision against the one before it. */
object RoadmapDiff {
    fun compare(before: List<Milestone>, after: List<Milestone>): List<RoadmapEntry> {
        val entries = ArrayList<RoadmapEntry>(after.size + before.size)
        val first = before.isEmpty()
        for (milestone in after)
            entries.add(RoadmapEntry(milestone.title, milestone.done,
                if (first || contains(before, milestone.title)) RoadmapChange.Kept else RoadmapChange.Added))
        for (dropped in before)
            if (!dropped.done && !contains(after, dropped.title))
                entries.add(RoadmapEntry(dropped.title, false, RoadmapChange.Removed))
        return entries
    }

    private fun contains(milestones: List<Milestone>, title: String): Boolean =
        milestones.any { it.title.trim().equals(title.trim(), ignoreCase = true) }
}
