package dev.pacenote.pairing

import dev.pacenote.acp.AcpFileEdit
import dev.pacenote.model.CodeQuote
import dev.pacenote.model.DraftOutcome
import dev.pacenote.model.DraftState
import dev.pacenote.model.EditorCaret
import dev.pacenote.model.PairingPhase
import dev.pacenote.model.PairingStop
import dev.pacenote.model.StopDraft
import dev.pacenote.model.StopLocation
import dev.pacenote.model.TreeSnapshot
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.nio.file.Path

/** The stop the user is on, and what it has come to. [createdFile]: the empty file the stop
 * created, taken back out if the user moves on without writing anything into it. */
data class OpenStop(
    val stop: PairingStop,
    val location: StopLocation,
    val baseline: TreeSnapshot,
    val draft: StopDraft,
    val draftState: DraftState,
    val createdFile: String?,
)

/** What the stop card is busy with, if anything. */
enum class StopActivity { Idle, Checking, Accepting }

/** How a request to open a stop came out. */
sealed interface StopOpening {
    data class Opened(val stop: OpenStop) : StopOpening

    data class Refused(val message: String) : StopOpening
}

/** Something the user did in a session, delivered to the agent as its next turn. [stop] is the
 * number of the stop it was about, or 0 when none was open. */
sealed interface PairingAction {
    val stop: Int

    /** The user finished the stop: their diff since it was shown, what they did with the agent's
     * code. Anything they wanted to say about it they said in the conversation. */
    data class Done(override val stop: Int, val diff: String, val draft: DraftOutcome, val problems: List<String>) : PairingAction

    /** The user said something to the agent (a question, or what they did instead) with where
     * their caret was. */
    data class Message(override val stop: Int, val text: String, val caret: EditorCaret?) : PairingAction

    /** The user passed on the stop without changing anything for it. */
    data class Skipped(override val stop: Int) : PairingAction

    /** The user ended the session. */
    data class Ended(override val stop: Int) : PairingAction
}

enum class NoticeTone { Info, Refused, Error }

/** One entry of the conversation with the agent, kept whole across a pairing session's stops. */
sealed interface PairingMessage {
    /** A pairing session began here. */
    data class SessionStarted(val goal: String) : PairingMessage

    /** A pairing session finished, and how. */
    data class SessionOver(val outcome: PairingPhase) : PairingMessage

    /** What the user said, with the code they sent along, if any. */
    data class FromUser(val text: String, val quote: CodeQuote? = null) : PairingMessage

    /** The agent's prose, growing as it streams. */
    class Narration(val text: StateFlow<String>) : PairingMessage

    /** Something the plugin did or refused on the session's behalf. */
    data class Notice(val text: String, val tone: NoticeTone) : PairingMessage

    /** A tool call the write guard left to the user, waiting on their answer: what an edit would
     * change, and the answer that covers its like from then on, where it has one. */
    class Approval(
        val pending: PendingToolApproval,
        val preview: List<EditPreviewLine>,
        val allowance: Allowance?,
        /** The edits the call would make, whole, for a viewer that shows more than [preview] does. */
        val edits: List<AcpFileEdit> = emptyList(),
    ) : PairingMessage

    /** An edit to a file the user allowed, let through without asking, and what it changed. */
    data class AllowedEdit(
        val title: String,
        val preview: List<EditPreviewLine>,
        val edits: List<AcpFileEdit> = emptyList(),
    ) : PairingMessage
}

/** The file's URI with the lines as a fragment, the way editors link a range. */
val CodeQuote.InFile.uri: URI
    get() = URI(Path.of(absolutePath).toUri().toString() + "#L${start.value}:${end.value}")

/** The quote's lines as a reader is told them. */
val CodeQuote.InFile.lines: String
    get() = if (start == end) "line ${start.value}" else "lines ${start.value}-${end.value}"

/** The quote as markdown for a prompt: where it is, then the code. [pathOf] turns an absolute path
 * into the one the reader is shown. */
fun CodeQuote.toMarkdown(pathOf: (String) -> String): String = when (this) {
    is CodeQuote.InFile -> {
        val fence = if (text.contains("```")) "````" else "```"
        "Selected in `${pathOf(absolutePath)}`, $lines:\n\n$fence\n$text\n$fence"
    }
}
