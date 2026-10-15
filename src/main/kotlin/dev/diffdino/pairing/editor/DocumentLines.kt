package dev.diffdino.pairing.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.TextPosition

// The model's 1-based lines over a Document's 0-based ones, so no caller does that arithmetic.

/** The lines of the file as the model counts them: an empty document has one empty line. */
internal val Document.fileLineCount: Int get() = maxOf(1, lineCount)

internal fun Document.clampLine(line: FileLine): Int = line.value.coerceIn(1, fileLineCount)

internal fun Document.lineOf(offset: Int): FileLine = FileLine(getLineNumber(offset.coerceIn(0, textLength)) + 1)

internal fun Document.lineStart(line: FileLine): Int = getLineStartOffset(clampLine(line) - 1)

internal fun Document.lineEnd(line: FileLine): Int = getLineEndOffset(clampLine(line) - 1)

internal fun Document.lineText(line: FileLine): String =
    if (line.value in 1..fileLineCount) getText(TextRange(lineStart(line), lineEnd(line))) else ""

internal fun Document.offsetOf(position: TextPosition): Int {
    val start = lineStart(position.line)
    val end = lineEnd(position.line)
    return (start + position.column.coerceAtLeast(0).toLong()).coerceAtMost(end.toLong()).toInt()
}

internal fun Document.positionOf(offset: Int): TextPosition {
    val line = lineOf(offset)
    return TextPosition(line, offset.coerceIn(0, textLength) - lineStart(line))
}
