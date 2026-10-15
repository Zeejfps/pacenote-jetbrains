package dev.diffdino.pairing.editor

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** A stretch of one draft line, as characters from [start] up to [end], in the colours of a token. */
internal data class ColoredRun(val start: Int, val end: Int, val keys: List<TextAttributesKey>, val fallback: TextAttributes?)

/**
 * The syntax colours of the agent's code for a stop, taken from the file as it would read with the
 * code in it, so a line of markup or the inside of a string is coloured as it will be once taken.
 */
internal object DraftColors {
    /** One list of runs per line of [code]; needs a read action. */
    fun of(project: Project, file: VirtualFile, splice: DraftSplice, code: List<String>): List<List<ColoredRun>> {
        if (code.isEmpty()) return emptyList()
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file)
        highlighter.setText(splice.text)

        val lineStarts = lineStartsOf(splice.text)
        val runs = List(code.size) { ArrayList<ColoredRun>() }
        val first = lineStarts[splice.lineOfCode(0) - 1]
        val iterator = highlighter.createIterator(first)
        while (!iterator.atEnd()) {
            val keys = iterator.textAttributesKeys.toList()
            val fallback = if (keys.isEmpty()) iterator.textAttributes else null
            var from = iterator.start
            val to = iterator.end
            while (from < to) {
                val line = lineIndexOf(lineStarts, from)
                val codeLine = line - splice.keptAbove
                if (codeLine >= code.size) return runs
                val lineStart = lineStarts[line]
                val lineEnd = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else splice.text.length
                val end = minOf(to, lineEnd)
                if (codeLine >= 0 && end > from) runs[codeLine].add(ColoredRun(from - lineStart, end - lineStart, keys, fallback))
                from = if (end < to) lineEnd + 1 else to
            }

            iterator.advance()
        }

        return runs
    }

    private fun lineStartsOf(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for ((index, c) in text.withIndex()) if (c == '\n') starts.add(index + 1)
        return starts.toIntArray()
    }

    private fun lineIndexOf(starts: IntArray, offset: Int): Int {
        val found = starts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }
}
