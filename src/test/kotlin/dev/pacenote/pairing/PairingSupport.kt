package dev.pacenote.pairing

import dev.pacenote.model.EditorCaret
import dev.pacenote.model.FileCreation
import dev.pacenote.model.FileLine
import dev.pacenote.model.PairingPresentation
import dev.pacenote.model.PairingWorkspace
import dev.pacenote.model.SnapshotResult
import dev.pacenote.model.StopDraft
import dev.pacenote.model.StopLocation
import dev.pacenote.model.StopPlacement
import dev.pacenote.model.StopTarget
import dev.pacenote.model.SuggestionActions
import dev.pacenote.model.TextPosition
import dev.pacenote.model.TreeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

/** One thread standing in for the EDT: everything the store and the conversation own runs on it. */
class UiThread : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "test-ui").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Runs [block] on the thread, blocking the test until it finishes. */
    fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking(dispatcher, block)

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }
}

/** Waits, letting the thread's other work run, until [condition] holds. */
suspend fun waitFor(what: String, condition: () -> Boolean) {
    try {
        withTimeout(10_000) { while (!condition()) delay(5) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out waiting for $what", e)
    }
}

/** Stands in for the editor: answers stops as the test scripts and records what it was asked. */
class RecordingPairingPresentation : PairingPresentation {
    val calls = mutableListOf<String>()
    val caretState = MutableStateFlow<EditorCaret?>(null)
    val draftNeedsState = MutableStateFlow<List<String>>(emptyList())

    var answer: (StopTarget) -> StopPlacement = { target ->
        StopPlacement.Placed(StopLocation.OnSymbol(
            "C:/repo/" + target.path, TextPosition.at(10, 4), "void " + target.symbol + "()", FileLine(14), FILE_LINES))
    }

    val drafts = mutableListOf<StopDraft>()

    /** The stop's file as the fake editor has it. */
    var fileText: String? = "file"

    var actions: SuggestionActions? = null
        private set

    /** Whether the editor manages to put the agent's code in. */
    var takeSucceeds = true

    val saveProblems = mutableListOf<String>()

    override val caret: StateFlow<EditorCaret?> = caretState
    override val draftNeeds: StateFlow<List<String>> = draftNeedsState

    override suspend fun locate(target: StopTarget): StopPlacement {
        calls.add("show ${target.path}#${target.symbol}")
        return answer(target)
    }

    /** How many of the next draws find the file changed since the stop was found. */
    var changedDraws = 0

    override fun showDraft(location: StopLocation, draft: StopDraft, actions: SuggestionActions): Boolean {
        if (changedDraws > 0) {
            changedDraws--
            calls.add("file changed")
            return false
        }

        this.actions = actions
        calls.add("show draft")
        drafts.add(draft)
        return true
    }

    override suspend fun readText(location: StopLocation): String? = fileText

    override fun clearDraft() {
        calls.add("clear draft")
    }

    override suspend fun takeDraft(location: StopLocation): Boolean {
        calls.add("take draft")
        return takeSucceeds
    }

    override fun reveal(location: StopLocation) {
        calls.add("reveal")
    }

    override fun revealDraft(location: StopLocation, draft: StopDraft) {
        calls.add("reveal draft")
    }

    override fun saveUnsaved(): List<String> {
        calls.add("save")
        return saveProblems
    }

    override fun relative(absolutePath: String): String? =
        if (absolutePath.startsWith("C:/repo/")) absolutePath.removePrefix("C:/repo/") else null

    companion object {
        /** The stop's file: 40 lines reading "line 1" to "line 40". */
        val FILE_LINES: List<String> = (1..40).map { "line $it" }
    }
}

/** A working tree the test moves by hand: each capture is the tree named by [current], and a diff
 * between two names is scripted. Answers from another thread, as git would. */
class ScriptedWorkspace : PairingWorkspace {
    @Volatile
    var current = "t0"

    val diffs = HashMap<Pair<String, String>, String>()

    @Volatile
    var captureFailure: String? = null

    @Volatile
    var captures = 0
        private set

    /** Files as the fake working tree holds them. */
    val files = HashMap<String, String>()

    override suspend fun capture(): SnapshotResult<TreeSnapshot> {
        captures++
        return withContext(Dispatchers.Default) {
            captureFailure?.let { SnapshotResult.Failed(it) } ?: SnapshotResult.Ok(TreeSnapshot(current))
        }
    }

    override suspend fun diff(from: TreeSnapshot, to: TreeSnapshot): SnapshotResult<String> = withContext(Dispatchers.Default) {
        SnapshotResult.Ok(if (from == to) "" else diffs[from.treeId to to.treeId] ?: "diff ${from.treeId}..${to.treeId}")
    }

    override suspend fun ensureEmptyFile(relativePath: String): FileCreation = withContext(Dispatchers.Default) {
        synchronized(files) {
            val content = files[relativePath]
            when {
                content == null -> {
                    files[relativePath] = ""
                    FileCreation.Created
                }
                content.isEmpty() -> FileCreation.AlreadyEmpty
                else -> FileCreation.Refused("$relativePath has content.")
            }
        }
    }

    override suspend fun removeIfEmpty(relativePath: String) {
        synchronized(files) {
            if (files[relativePath]?.isEmpty() == true) files.remove(relativePath)
        }
    }
}
