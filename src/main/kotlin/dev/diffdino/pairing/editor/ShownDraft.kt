package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.SuggestionActions
import org.jetbrains.concurrency.CancellablePromise

/**
 * One draft drawn over its file: the agent's code as a block where it goes in every editor open on
 * the file, the lines it replaces tinted, the lines the user has already typed taken out of it, and
 * the names in it that nothing declares marked. Everything is anchored to the document, so it
 * follows edits. UI thread only.
 */
internal class ShownDraft(
    private val project: Project,
    private val file: VirtualFile,
    private val document: Document,
    location: StopLocation,
    val draft: StopDraft,
    val actions: SuggestionActions,
    parent: Disposable,
    private val onNeeds: (List<String>) -> Unit,
) : Disposable {
    val path: String = location.absolutePath

    private val lines = draftLinesOf(draft.code)
    private val splice = DraftSplice.of(fileLinesOf(location), draft.place, lines)
    private val place = draft.place
    private val hang: Hang
    private val anchor: RangeMarker?
    private val replaced: RangeMarker?
    private val below: RangeMarker
    private val tint: RangeHighlighter?
    private val blocks = HashMap<Editor, DraftBlock>()
    private var colors: List<List<ColoredRun>> = emptyList()
    private var missing: List<MissingName> = emptyList()
    private var lastAnchorLine = 0
    private var refreshQueued = false
    private var disposed = false
    private var namesJob: CancellablePromise<*>? = null

    init {
        Disposer.register(parent, this)
        val anchorLine = when (place) {
            is DraftPlace.Replace -> document.clampLine(FileLine(place.lines.to))
            is DraftPlace.InsertAfter -> place.line.value.coerceIn(0, document.fileLineCount)
        }

        val lastLine = document.fileLineCount
        hang = if (anchorLine < lastLine) Hang.AboveLine else Hang.BelowLine
        val belowOffset = if (anchorLine < lastLine) document.lineStart(FileLine(anchorLine + 1)) else document.textLength
        below = document.createRangeMarker(belowOffset, belowOffset).apply {
            isGreedyToLeft = false
            isGreedyToRight = true
        }

        when (place) {
            is DraftPlace.Replace -> {
                val from = document.clampLine(FileLine(place.lines.from))
                val start = document.lineStart(FileLine(from))
                val end = document.lineEnd(FileLine(maxOf(from, anchorLine)))
                replaced = document.createRangeMarker(start, end)
                anchor = replaced
                tint = if (document.getText(TextRange(start, end)).isBlank()) null else {
                    DocumentMarkupModel.forDocument(document, project, true).addRangeHighlighter(
                        DiffColors.DIFF_DELETED, start, end, HighlighterLayer.SELECTION - 1, HighlighterTargetArea.LINES_IN_RANGE,
                    )
                }
            }

            is DraftPlace.InsertAfter -> {
                replaced = null
                tint = null
                anchor = if (anchorLine >= 1) {
                    document.createRangeMarker(document.lineStart(FileLine(anchorLine)), document.lineEnd(FileLine(anchorLine)))
                } else {
                    null
                }
            }
        }

        lastAnchorLine = anchorLine
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleRefresh()
            },
            this,
        )
        project.messageBus.connect(this).subscribe(
            DumbService.DUMB_MODE,
            object : DumbService.DumbModeListener {
                override fun exitDumbMode() = checkNames()
            },
        )
        EditorTracker(project, document, this) { editor, scope ->
            val block = DraftBlock(editor, { it.run(actions) }, scope)
            blocks[editor] = block
            Disposer.register(scope) { blocks.remove(editor) }
            block.show(currentRows(), below.startOffset, hang)
        }
        color()
        checkNames()
    }

    /** Whether the draft is on screen in [editor]. */
    fun isShownIn(editor: Editor): Boolean = blocks[editor]?.isShowing == true

    /** Draws what is left of the draft where it hangs now. */
    fun refresh() {
        val rows = currentRows()
        for (block in blocks.values) block.show(rows, below.startOffset, hang)
    }

    /** Puts the draft into the file as one undoable edit; false when there is nothing to put or the file can't be written. */
    fun take(): Boolean {
        val edit = pendingEdit() ?: return false
        if (!ReadonlyStatusHandler.ensureDocumentWritable(project, document)) return false
        val editor = blocks.keys.firstOrNull()
        WriteCommandAction.runWriteCommandAction(project, "Accept DiffDino Draft", null, {
            document.replaceString(edit.start, edit.end, edit.text)
            editor?.caretModel?.moveToOffset(minOf(edit.start + edit.text.length, document.textLength))
        })
        editor?.scrollingModel?.scrollToCaret(ScrollType.MAKE_VISIBLE)
        return true
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    private fun pendingEdit(): Edit? {
        val text = lines
        return when (place) {
            is DraftPlace.InsertAfter -> {
                val left = remaining()
                if (left.remaining.isEmpty()) return null
                val joined = left.remaining.joinToString("\n") { text[it] }
                if (left.lastTyped == 0) {
                    Edit(0, 0, "$joined\n")
                } else {
                    val end = document.lineEnd(FileLine(left.lastTyped))
                    Edit(end, end, "\n$joined")
                }
            }

            is DraftPlace.Replace -> {
                val (first, last) = replacedLines()
                if (text.isEmpty()) deletion(first, last) else Edit(document.lineStart(first), document.lineEnd(last), text.joinToString("\n"))
            }
        }
    }

    // Whole lines, so none is left behind blank: with the break after them, or at the end of the file
    // the one before them.
    private fun deletion(first: FileLine, last: FileLine): Edit = when {
        last.value < document.fileLineCount -> Edit(document.lineStart(first), document.lineStart(FileLine(last.value + 1)), "")
        first.value > 1 -> Edit(document.lineEnd(FileLine(first.value - 1)), document.lineEnd(last), "")
        else -> Edit(0, document.textLength, "")
    }

    private fun replacedLines(): Pair<FileLine, FileLine> {
        val marker = replaced?.takeIf { it.isValid }
        if (marker == null) {
            val lines = (place as? DraftPlace.Replace)?.lines
            val first = document.clampLine(FileLine(lines?.from ?: 1))
            return FileLine(first) to FileLine(document.clampLine(FileLine(maxOf(first, lines?.to ?: first))))
        }

        val first = document.lineOf(marker.startOffset)
        return first to FileLine(maxOf(first.value, document.lineOf(marker.endOffset).value))
    }

    private fun remaining(): GhostLines {
        val anchorLine = anchor?.takeIf { it.isValid }?.let { document.lineOf(it.endOffset).value }
            ?: if (anchor == null) 0 else lastAnchorLine
        lastAnchorLine = anchorLine
        val belowLine = when (hang) {
            Hang.AboveLine -> document.lineOf(below.startOffset).value
            Hang.BelowLine -> document.fileLineCount + 1
        }
        return GhostMatch.remaining(lines, anchorLine, belowLine, document.fileLineCount) { document.lineText(FileLine(it)) }
    }

    // Null when nothing is left to show: what the user has typed is all of it.
    private fun currentRows(): List<GhostRow>? {
        val left = remaining().remaining
        if (left.isEmpty() && place !is DraftPlace.Replace) return null
        val missingByLine = missing.groupBy { it.line }
        return left.map { index ->
            GhostRow(
                lines[index],
                colors.getOrElse(index) { emptyList() },
                missingByLine[index].orEmpty().map { it.start until it.end },
            )
        }
    }

    private fun scheduleRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ApplicationManager.getApplication().invokeLater(
            {
                refreshQueued = false
                refresh()
            },
            ModalityState.any(),
            { disposed },
        )
    }

    private fun color() {
        if (lines.isEmpty()) return
        ReadAction.nonBlocking<List<List<ColoredRun>>> { DraftColors.of(project, file, splice, lines) }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                colors = it
                refresh()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun checkNames() {
        if (lines.isEmpty()) return
        namesJob?.cancel()
        namesJob = ReadAction.nonBlocking<List<MissingName>> {
            PsiManager.getInstance(project).findFile(file)?.let { DraftNameChecker.missing(project, it, splice) }.orEmpty()
        }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                missing = it
                refresh()
                onNeeds(it.map { name -> name.text }.distinct())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() {
        disposed = true
        namesJob?.cancel()
        tint?.let { DocumentMarkupModel.forDocument(document, project, true).removeHighlighter(it) }
        listOfNotNull(below, anchor, replaced).distinct().forEach { it.dispose() }
        blocks.clear()
    }

    private fun fileLinesOf(location: StopLocation): List<String> = when (location) {
        is StopLocation.OnSymbol -> location.lines
        is StopLocation.Insertion -> location.lines
        is StopLocation.NewFile -> emptyList()
    }
}
