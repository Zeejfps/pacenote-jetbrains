package dev.pacenote.pairing

import dev.pacenote.acp.AcpToolCallStatus
import dev.pacenote.acp.AcpToolKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A tool the agent called and hasn't heard back from: by the title it gave, since [startedAt]. */
data class AgentStep(val id: String, val title: String, val kind: AgentStepKind, val startedAt: Long)

/** What sort of thing a step does, as far as the agent says. */
enum class AgentStepKind { Run, Read, Edit, Search, Fetch, Think, Other }

/** What the agent last did that wasn't a step. */
enum class AgentDoing { Working, Thinking, Writing }

/**
 * What the agent is doing in the turn in flight, as far as its updates tell: the steps still
 * running, oldest first, else what it last did, since [since]. [lastHeard] is when anything last
 * came from it; [stopping] once the user asked it to stop. Times are the tracker's clock, in millis.
 */
data class AgentActivity(
    val steps: List<AgentStep>,
    val doing: AgentDoing,
    val since: Long,
    val lastHeard: Long,
    val stopping: Boolean,
) {
    /** The step to show: the one started last. */
    val current: AgentStep? get() = steps.lastOrNull()
}

/**
 * Follows the agent's updates through a turn into an [AgentActivity], null between turns. Steps
 * are tool calls from when they are announced to when they complete or fail; a turn that ends
 * drops whatever was still running. Confined to the conversation's thread.
 */
class AgentActivityTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val _activity = MutableStateFlow<AgentActivity?>(null)

    val activity: StateFlow<AgentActivity?> = _activity.asStateFlow()

    fun beginTurn() {
        val now = clock()
        _activity.value = AgentActivity(emptyList(), AgentDoing.Working, now, now, stopping = false)
    }

    fun endTurn() {
        _activity.value = null
    }

    /** A tool call was announced, or changed title or status. */
    fun toolCall(id: String, title: String?, kind: AcpToolKind?, status: AcpToolCallStatus?) {
        val activity = _activity.value ?: return
        val now = clock()
        val known = activity.steps.firstOrNull { it.id == id }
        val done = status == AcpToolCallStatus.Completed || status == AcpToolCallStatus.Failed
        _activity.value = when {
            done && known != null -> {
                val left = activity.steps - known
                activity.copy(steps = left, doing = AgentDoing.Working, since = left.lastOrNull()?.startedAt ?: now, lastHeard = now)
            }
            done -> activity.copy(lastHeard = now)
            known != null -> activity.copy(
                steps = activity.steps.map {
                    if (it.id != id) it
                    else it.copy(title = title?.takeIf(String::isNotBlank) ?: it.title, kind = kind?.let(::kindOf) ?: it.kind)
                },
                lastHeard = now,
            )
            else -> activity.copy(
                steps = activity.steps + AgentStep(id, title?.takeIf(String::isNotBlank) ?: "", kindOf(kind), now),
                since = now,
                lastHeard = now,
            )
        }
    }

    fun thought() = did(AgentDoing.Thinking)

    fun wrote() = did(AgentDoing.Writing)

    /** The user asked the agent to stop; the turn ends when it does. */
    fun stopping() {
        _activity.value = _activity.value?.copy(stopping = true)
    }

    private fun did(doing: AgentDoing) {
        val activity = _activity.value ?: return
        val now = clock()
        _activity.value = activity.copy(doing = doing, since = if (activity.doing == doing) activity.since else now, lastHeard = now)
    }

    private fun kindOf(kind: AcpToolKind?): AgentStepKind = when (kind) {
        AcpToolKind.Execute -> AgentStepKind.Run
        AcpToolKind.Read -> AgentStepKind.Read
        AcpToolKind.Edit, AcpToolKind.Delete, AcpToolKind.Move -> AgentStepKind.Edit
        AcpToolKind.Search -> AgentStepKind.Search
        AcpToolKind.Fetch -> AgentStepKind.Fetch
        AcpToolKind.Think -> AgentStepKind.Think
        AcpToolKind.SwitchMode, AcpToolKind.Other, null -> AgentStepKind.Other
    }
}
