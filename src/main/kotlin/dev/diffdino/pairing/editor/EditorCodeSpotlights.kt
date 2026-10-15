package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiManager
import dev.diffdino.pairing.model.CodeSpotlights
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.LineSpan
import dev.diffdino.pairing.model.ShowOutcome
import dev.diffdino.pairing.model.ShowTarget
import dev.diffdino.pairing.model.SpotlightRun
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopMiss
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Points the user at code without a pairing session: opens a place in this project's editor and
 * lights runs of lines up with notes, one file at a time. Never switches projects.
 */
class EditorCodeSpotlights(private val project: Project) : CodeSpotlights, Disposable {
    private val scope: Disposable get() = this
    private val places = RepoFilePlaces(project)
    private var lit: LitSpotlights? = null

    /** The runs lit up now, as the lines they cover, or empty when none are. */
    internal val litLines: List<LineSpan> get() = lit?.lines().orEmpty()

    override suspend fun show(path: String, target: ShowTarget): ShowOutcome {
        val source = when (val read = read(path)) {
            is Read.Failed -> return ShowOutcome.Missed(read.message)
            is Read.Found -> read
        }

        val line = when (target) {
            is ShowTarget.Symbol -> when (val found = declaration(source, path, target.name)) {
                is Declaration.Found -> found.lines.from
                is Declaration.Failed -> return ShowOutcome.Missed(found.message)
            }

            is ShowTarget.Line -> {
                if (target.line > source.lines.size) {
                    return ShowOutcome.Missed("$path has ${source.lines.size} lines; there is no line ${target.line}.")
                }

                maxOf(1, target.line)
            }
        }

        return display(source, path, line, emptyList())
    }

    override suspend fun spotlight(path: String, runs: List<SpotlightRun>): ShowOutcome {
        val source = when (val read = read(path)) {
            is Read.Failed -> return ShowOutcome.Missed(read.message)
            is Read.Found -> read
        }

        if (runs.isEmpty()) return ShowOutcome.Missed("Pass at least one run to light: a symbol, or lines.")
        val resolved = ArrayList<SpotlitRun>(runs.size)
        for ((index, run) in runs.withIndex()) {
            val note = run.note.trim().ifEmpty { null }
            when (val span = lines(source, path, run)) {
                is Declaration.Found -> resolved.add(SpotlitRun(span.lines, note))
                is Declaration.Failed -> return ShowOutcome.Missed("spotlights[$index]: ${span.message}")
            }
        }

        return display(source, path, resolved.first().lines.from, resolved)
    }

    override fun clear() {
        lit?.let(Disposer::dispose)
        lit = null
    }

    private suspend fun display(source: Read.Found, path: String, line: Int, runs: List<SpotlitRun>): ShowOutcome =
        withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, source.file), true)
                ?: return@withContext ShowOutcome.Missed("$path can't be opened as text.")
            val document = editor.document
            clear()
            if (runs.isNotEmpty()) lit = LitSpotlights(project, document, runs, scope)
            editor.caretModel.moveToOffset(document.offsetOf(TextPosition(FileLine(line), 0)))
            editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            ShowOutcome.Shown(places.relative(source.absolute) ?: path, line, onScreen())
        }

    private fun onScreen(): Boolean =
        WindowManager.getInstance().getFrame(project)?.let { it.isShowing && it.isActive } ?: false

    private sealed interface Read {
        data class Found(val absolute: String, val file: VirtualFile, val lines: List<String>, val outline: List<OutlineNode>?) : Read

        data class Failed(val message: String) : Read
    }

    private suspend fun read(path: String): Read = withContext(Dispatchers.Default) {
        val absolute = places.absolute(path) ?: return@withContext Read.Failed("$path is outside the repository.")
        val unavailable = Read.Failed("$path is not a file in the repository, or is too large to open.")
        val file = places.fileAt(absolute, refresh = true) ?: return@withContext unavailable
        if (places.whyUnreadable(file) != null) return@withContext unavailable
        constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@constrainedReadAction unavailable
            val outline = PsiManager.getInstance(project).findFile(file)?.let { DeclarationOutline.of(it, document) }
            Read.Found(absolute, file, document.text.split('\n'), outline)
        }
    }

    private sealed interface Declaration {
        data class Found(val lines: LineSpan) : Declaration

        data class Failed(val message: String) : Declaration
    }

    // A declaration's lines, found by name in the file as the user has it.
    private fun declaration(source: Read.Found, path: String, symbol: String): Declaration {
        val text = source.lines.joinToString("\n")
        return when (val placement = StopResolver.resolve(source.absolute, text, source.outline, StopTarget(path, symbol, null))) {
            is StopPlacement.Missed -> Declaration.Failed(StopMiss.describe(placement.miss))
            is StopPlacement.Placed -> when (val location = placement.location) {
                is StopLocation.OnSymbol -> Declaration.Found(LineSpan(location.at.line.value, location.lastLine.value))
                is StopLocation.NewFile -> Declaration.Failed("$path does not exist, or is empty.")
                is StopLocation.Insertion -> error("A declaration looked up by name has no insertion point.")
            }
        }
    }

    private fun lines(source: Read.Found, path: String, run: SpotlightRun): Declaration = when (run) {
        is SpotlightRun.Symbol -> declaration(source, path, run.name)
        is SpotlightRun.Lines -> {
            val last = run.to ?: run.from
            when {
                run.from < 1 -> Declaration.Failed("'from' must be 1 or more.")
                last < run.from -> Declaration.Failed("'to' ($last) is before 'from' (${run.from}).")
                last > source.lines.size -> Declaration.Failed("$path has ${source.lines.size} lines; there is no line $last.")
                else -> Declaration.Found(LineSpan(run.from, last))
            }
        }
    }

    override fun dispose() = Unit
}
