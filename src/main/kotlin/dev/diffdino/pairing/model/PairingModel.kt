package dev.diffdino.pairing.model

/** Where a stop sends the user: a repo-relative file and a declaration in it, named rather than
 * numbered so it survives edits. A declaration that doesn't exist yet names the one it goes after. */
data class StopTarget(val path: String, val symbol: String, val after: String?)

/** One stop on the loop, numbered from 1 in the order the agent sent them. */
data class PairingStop(val number: Int, val target: StopTarget, val title: String, val reason: String)

/** A coarse step of the roadmap, as the agent sent it. */
data class Milestone(val title: String, val done: Boolean)

/** How a roadmap entry compares with the roadmap before the agent's last revision. */
enum class RoadmapChange { Kept, Added, Removed }

data class RoadmapEntry(val title: String, val done: Boolean, val change: RoadmapChange)

/** What a pairing session is doing. */
sealed interface PairingPhase {
    /** The agent is being started and has not yet sent anything. */
    data object Starting : PairingPhase

    /** The agent is driving. [waiting] is true once it has handed the move to the user. */
    data class Running(val waiting: Boolean) : PairingPhase

    /** The agent stopped calling back without ending the session. */
    data class Disconnected(val reason: String) : PairingPhase

    /** Finished, by the agent (with its summary) or by the user. */
    data class Ended(val summary: String?) : PairingPhase

    /** The agent could not be started, or died. */
    data class Failed(val reason: String) : PairingPhase
}

/** Where a stop landed in the editor. */
sealed interface StopLocation {
    val absolutePath: String

    /** On the declaration's name. [lastLine] is where the declaration ends; [lines] are the file's,
     * as it was found. */
    data class OnSymbol(
        override val absolutePath: String,
        val at: TextPosition,
        val lineText: String,
        val lastLine: FileLine,
        val lines: List<String>,
    ) : StopLocation

    /** A declaration still to be written: the end of the one it goes after. */
    data class Insertion(
        override val absolutePath: String,
        val at: TextPosition,
        val after: String,
        val lines: List<String>,
    ) : StopLocation

    /** The file doesn't exist yet, or has nothing in it: the agent's code is its first block. */
    data class NewFile(override val absolutePath: String) : StopLocation
}

/** Why a stop could not be placed; the agent is told so and the stop doesn't open. */
sealed interface StopMiss {
    data class NoSuchSymbol(val path: String, val symbol: String, val known: List<String>) : StopMiss
    data class NoSuchAfter(val path: String, val after: String, val known: List<String>) : StopMiss
    data class OutsideRepository(val path: String) : StopMiss
    data class Unreadable(val path: String, val reason: String) : StopMiss

    companion object {
        /** A miss as the agent is told it, with what it could name instead. */
        fun describe(miss: StopMiss): String = when (miss) {
            is NoSuchSymbol ->
                "'${miss.symbol}' is not declared in ${miss.path}. Name a declaration that exists, or pass 'after' with the " +
                    "declaration the new one goes after. Declared there: ${listed(miss.known)}"
            is NoSuchAfter ->
                "'${miss.after}' (the declaration to go after) is not declared in ${miss.path}. Declared there: ${listed(miss.known)}"
            is OutsideRepository -> "${miss.path} is outside the repository."
            is Unreadable -> "${miss.path} could not be read: ${miss.reason}"
        }

        private fun listed(known: List<String>) = if (known.isEmpty()) "(nothing found)" else known.joinToString(", ")
    }
}

sealed interface StopPlacement {
    data class Placed(val location: StopLocation) : StopPlacement
    data class Missed(val miss: StopMiss) : StopPlacement
}

/** Where the agent asked its code for a stop to go, before it is checked against the file. */
sealed interface DraftSpan {
    /** In place of the stop's declaration, after the one it names, or as the new file. */
    data object Declaration : DraftSpan

    /** In place of these lines, 1-based and inclusive. */
    data class Lines(val from: Int, val to: Int) : DraftSpan

    /** In after this line, 1-based. */
    data class After(val line: Int) : DraftSpan
}

/** The agent's code for a stop, as it sent it. */
data class DraftRequest(val code: String, val span: DraftSpan)

/** Where the agent's code for a stop goes in the file. */
sealed interface DraftPlace {
    data class Replace(val lines: LineSpan) : DraftPlace

    /** After this line; line 0 means at the top of the file. */
    data class InsertAfter(val line: FileLine) : DraftPlace
}

/** The agent's code for one stop (one block) and where it goes. */
data class StopDraft(val code: String, val place: DraftPlace)

/** What the buttons on a draft in the editor do: put it in, put it in and move on, or move on. */
data class SuggestionActions(val accept: () -> Unit, val acceptAndNext: () -> Unit, val next: () -> Unit)

/** Where the agent's code for the open stop stands. */
sealed interface DraftState {
    /** Shown in the editor, not taken. */
    data object Offered : DraftState

    /** Taken into the file, which read [fileText] right after; null where it could not be read. */
    data class Taken(val fileText: String?) : DraftState
}

/** What the user did with the agent's code by the time they moved on. */
enum class DraftOutcome { NotAccepted, AcceptedAsIs, AcceptedThenEdited }

/** A working tree captured as a git tree object. */
@JvmInline
value class TreeSnapshot(val treeId: String)

sealed interface SnapshotResult<out T> {
    data class Ok<T>(val value: T) : SnapshotResult<T>
    data class Failed(val reason: String) : SnapshotResult<Nothing>
}

/** How making sure of an empty file went. */
sealed interface FileCreation {
    data object Created : FileCreation
    data object AlreadyEmpty : FileCreation
    data class Refused(val reason: String) : FileCreation
}
