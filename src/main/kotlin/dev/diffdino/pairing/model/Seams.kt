package dev.diffdino.pairing.model

import kotlinx.coroutines.flow.StateFlow

/** What the pairing loop needs from the editor, for one project. Non-suspending members run on the
 * EDT; suspending ones may be called from any coroutine and switch threads themselves. */
interface PairingPresentation {
    /** Finds the stop's declaration in the file as it is now, unsaved edits included. Moves nothing. */
    suspend fun locate(target: StopTarget): StopPlacement

    /** Draws the agent's code for the stop into its file where it goes, with Accept, Accept & next
     * and Next on it. */
    fun showDraft(location: StopLocation, draft: StopDraft, actions: SuggestionActions)

    /** The stop's file as the user has it now, unsaved edits included; null when it can't be read. */
    suspend fun readText(location: StopLocation): String?

    /** Takes the agent's code off the editor. */
    fun clearDraft()

    /** Puts the agent's code into the stop's open file as one undo step, unsaved. Answers whether
     * it went in. */
    suspend fun takeDraft(location: StopLocation): Boolean

    /** Opens the file and puts the caret on a place a stop found. */
    fun reveal(location: StopLocation)

    /** Puts the caret where the agent's code for a stop goes. */
    fun revealDraft(location: StopLocation, draft: StopDraft)

    /** Where the user's caret is, and what they have selected. */
    val caret: StateFlow<EditorCaret?>

    /** The names the agent's code for the open stop uses that nothing declares yet, each once. */
    val draftNeeds: StateFlow<List<String>>

    /** Writes every unsaved document of the project; answers the paths that failed. */
    fun saveUnsaved(): List<String>

    /** The repository-relative form of an absolute path, or null outside it. Any thread. */
    fun relative(absolutePath: String): String?
}

/** What the pairing loop needs from the working tree. Every member works off the EDT. */
interface PairingWorkspace {
    suspend fun capture(): SnapshotResult<TreeSnapshot>

    suspend fun diff(from: TreeSnapshot, to: TreeSnapshot): SnapshotResult<String>

    /** Makes sure a new file's stop has a file to open: creates it empty, or finds it already
     * there with nothing in it. Refused when it has content. */
    suspend fun ensureEmptyFile(relativePath: String): FileCreation

    /** Deletes a file a stop created, unless something was written into it. */
    suspend fun removeIfEmpty(relativePath: String)
}

/** One highlighted run of lines the agent points at, with its note. */
sealed interface SpotlightRun {
    val note: String

    data class Symbol(val name: String, override val note: String) : SpotlightRun

    /** Lines 1-based and inclusive; [to] null means just [from]. */
    data class Lines(val from: Int, val to: Int?, override val note: String) : SpotlightRun
}

/** Where a show request sends the user. */
sealed interface ShowTarget {
    data class Symbol(val name: String) : ShowTarget
    data class Line(val line: Int) : ShowTarget
}

/** How a request to show the user code came out. */
sealed interface ShowOutcome {
    /** Shown at [line] of [relativePath]. [onScreen] is false when the IDE frame is not in front. */
    data class Shown(val relativePath: String, val line: Int, val onScreen: Boolean) : ShowOutcome

    /** Could not be shown; [message] is what the agent is told. */
    data class Missed(val message: String) : ShowOutcome
}

/** Pointing at code in the editor without a pairing session: open a place, highlight runs with
 * notes, clear them. */
interface CodeSpotlights {
    suspend fun show(path: String, target: ShowTarget): ShowOutcome

    suspend fun spotlight(path: String, runs: List<SpotlightRun>): ShowOutcome

    fun clear()
}
