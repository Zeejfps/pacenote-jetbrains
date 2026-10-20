package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.acp.AcpEnvironment
import dev.diffdino.pairing.acp.AcpFileEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/** One line of what an edit the user is asked about would change. */
sealed interface EditPreviewLine {
    /** The file the lines after it are in, where the edit spans more than one. */
    data class File(val path: String) : EditPreviewLine

    data class Context(val text: String) : EditPreviewLine

    data class Removed(val text: String) : EditPreviewLine

    data class Added(val text: String) : EditPreviewLine

    /** The rest of the change, past what the card shows. */
    data class Elided(val lines: Int) : EditPreviewLine
}

object EditPreview {
    const val MAX_LINES = 16

    /** The lines each edit changes, with one line around them, cut off at [MAX_LINES]. */
    fun of(edits: List<AcpFileEdit>, root: Path): List<EditPreviewLine> {
        val lines = ArrayList<EditPreviewLine>()
        val headed = edits.map { it.path }.distinct().size > 1
        var file: String? = null
        for (edit in edits) {
            if (headed && edit.path != file) {
                file = edit.path
                lines.add(EditPreviewLine.File(if (isAbsolute(edit.path)) AgentPrompt.relativeTo(root, edit.path) else edit.path))
            }
            changed(edit, lines)
        }

        if (lines.size <= MAX_LINES) return lines
        val shown = lines.take(MAX_LINES).toMutableList()
        shown.add(EditPreviewLine.Elided(lines.drop(MAX_LINES).count { it !is EditPreviewLine.File }))
        return shown
    }

    private fun changed(edit: AcpFileEdit, lines: MutableList<EditPreviewLine>) {
        val before = edit.oldText?.let(::split) ?: emptyList()
        val after = split(edit.newText)
        var prefix = 0
        while (prefix < before.size && prefix < after.size && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.size - prefix && suffix < after.size - prefix &&
            before[before.size - 1 - suffix] == after[after.size - 1 - suffix]
        ) suffix++

        if (prefix > 0) lines.add(EditPreviewLine.Context(before[prefix - 1]))
        for (i in prefix until before.size - suffix) lines.add(EditPreviewLine.Removed(before[i]))
        for (i in prefix until after.size - suffix) lines.add(EditPreviewLine.Added(after[i]))
        if (suffix > 0) lines.add(EditPreviewLine.Context(after[after.size - suffix]))
    }

    private fun split(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').split('\n')

    private fun isAbsolute(path: String): Boolean = try {
        Path.of(path).isAbsolute
    } catch (_: java.nio.file.InvalidPathException) {
        false
    }
}

/** The files the user let the agent edit without asking, for as long as it runs. Confined to the
 * conversation's thread. */
class EditAllowances(private val root: Path) {
    private val files = HashSet<String>()

    fun cover(paths: List<String>): Boolean = paths.isNotEmpty() && paths.all { full(it) in files }

    fun grant(paths: List<String>) {
        for (path in paths) files.add(full(path))
    }

    private fun full(path: String): String {
        val normalized = root.resolve(path).toAbsolutePath().normalize().toString()
        return if (AcpEnvironment.isWindows) normalized.lowercase() else normalized
    }
}

/** What an [Allowance] lets through from then on. */
sealed interface AllowanceScope {
    /** Edits to the files the call would edit. */
    data object SameFiles : AllowanceScope

    /** Every tool of the MCP server the call is to, [server] as read. */
    data class McpServer(val server: String) : AllowanceScope
}

/** The answer to a call that covers its like as well: approves it, and every later call within
 * [scope] while the agent runs. */
class Allowance(private val pending: PendingToolApproval, val scope: AllowanceScope, private val grant: () -> Unit) {
    private val _granted = MutableStateFlow(false)

    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    fun allow() {
        if (!pending.isPending) return
        grant()
        _granted.value = true
        pending.approve()
    }
}
