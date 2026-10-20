package dev.pacenote.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a question was settled. Pending is the only state with an answer still to give. */
enum class ToolApprovalOutcome { Pending, Approved, Denied, Cancelled }

/**
 * One tool call waiting on the user: what it is, the arguments it would run with, and the answer.
 * It lives in the transcript rather than in a view, so hiding the view and showing it again finds
 * the same question still waiting. Every way out of Pending is one-way: the first answer wins, and
 * a turn that ends first withdraws the question instead of leaving live buttons behind it.
 */
class PendingToolApproval(val toolName: String, val arguments: String) {
    private val answer = CompletableDeferred<Boolean>()
    private val _outcome = MutableStateFlow(ToolApprovalOutcome.Pending)

    val outcome: StateFlow<ToolApprovalOutcome> = _outcome.asStateFlow()

    val isPending: Boolean get() = _outcome.value == ToolApprovalOutcome.Pending

    fun approve() = settle(ToolApprovalOutcome.Approved)

    fun deny() = settle(ToolApprovalOutcome.Denied)

    /** Withdraws the question: the turn ended before it was answered. */
    fun cancel() = settle(ToolApprovalOutcome.Cancelled)

    /** Suspends until the question is answered: true when approved. */
    suspend fun await(): Boolean = answer.await()

    private fun settle(outcome: ToolApprovalOutcome) {
        if (!_outcome.compareAndSet(ToolApprovalOutcome.Pending, outcome)) return
        answer.complete(outcome == ToolApprovalOutcome.Approved)
    }
}
