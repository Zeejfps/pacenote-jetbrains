package dev.diffdino.pairing.ui.action

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.model.FileLine

/** How turning an editor's selection into a quote came out. */
sealed interface SelectionQuoteResult {
    data class Quoted(val quote: CodeQuote.InFile) : SelectionQuoteResult

    data object NoSelection : SelectionQuoteResult

    /** The document is not a file on disk, so the agent could not find it. */
    data object NotAFile : SelectionQuoteResult

    data class TooLong(val lines: Int) : SelectionQuoteResult
}

object SelectionQuote {
    /** The selection of [editor] as a quote of its file. */
    fun of(editor: Editor): SelectionQuoteResult {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return SelectionQuoteResult.NoSelection
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val path = file?.toNioPathOrNull()?.toString()
        return of(editor.document, selection.selectionStart, selection.selectionEnd, path)
    }

    /** The characters from [start] to [end] of [document], quoted as lines of the file at [absolutePath].
     * A selection that ends at the start of a line doesn't take that line. */
    fun of(document: Document, start: Int, end: Int, absolutePath: String?): SelectionQuoteResult {
        if (end <= start) return SelectionQuoteResult.NoSelection
        if (absolutePath == null) return SelectionQuoteResult.NotAFile
        val first = document.getLineNumber(start) + 1
        val last = document.getLineNumber(end - 1) + 1
        val count = last - first + 1
        if (count > CodeQuote.MAX_LINES) return SelectionQuoteResult.TooLong(count)
        return SelectionQuoteResult.Quoted(CodeQuote.InFile(absolutePath, FileLine(first), FileLine(last), document.getText(TextRange(start, end))))
    }
}
