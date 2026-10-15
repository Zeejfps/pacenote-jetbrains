package dev.diffdino.pairing.ui.chat

import dev.diffdino.pairing.pairing.EditPreviewLine

/** One line of a diff preview as the card draws it. */
sealed interface PreviewRow {
    /** The file the rows after it are in. */
    data class FileHeader(val path: String) : PreviewRow

    data class Code(val kind: Kind, val text: String) : PreviewRow {
        enum class Kind(val marker: String) {
            Context(" "),
            Removed("-"),
            Added("+"),
        }
    }

    /** What the preview leaves out. */
    data class More(val lines: Int) : PreviewRow
}

object EditPreviewRows {
    fun of(lines: List<EditPreviewLine>): List<PreviewRow> = lines.map { line ->
        when (line) {
            is EditPreviewLine.File -> PreviewRow.FileHeader(line.path)
            is EditPreviewLine.Context -> PreviewRow.Code(PreviewRow.Code.Kind.Context, line.text)
            is EditPreviewLine.Removed -> PreviewRow.Code(PreviewRow.Code.Kind.Removed, line.text)
            is EditPreviewLine.Added -> PreviewRow.Code(PreviewRow.Code.Kind.Added, line.text)
            is EditPreviewLine.Elided -> PreviewRow.More(line.lines)
        }
    }

    /** Lines of plain text, as rows with no marks: a command, or code someone quoted. [maxLines] and
     * the rest are left out and counted. */
    fun plain(text: String, maxLines: Int): List<PreviewRow> {
        val lines = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
        val shown = lines.take(maxLines).map { PreviewRow.Code(PreviewRow.Code.Kind.Context, it) }
        return if (lines.size > maxLines) shown + PreviewRow.More(lines.size - maxLines) else shown
    }
}
