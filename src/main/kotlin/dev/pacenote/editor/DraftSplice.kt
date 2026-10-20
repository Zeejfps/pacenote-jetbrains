package dev.pacenote.editor

import dev.pacenote.model.DraftPlace

/**
 * A file as it would read with the agent's code in it: the file's lines above the code, the code,
 * and the file's lines from [resumeAt] on. Lines are 1-based throughout.
 */
internal data class DraftSplice(val text: String, val keptAbove: Int, val codeLines: Int, val resumeAt: Int) {
    /** The line of [text] a line of the code, 0-based, is on. */
    fun lineOfCode(codeLine: Int): Int = keptAbove + codeLine + 1

    companion object {
        fun of(file: List<String>, place: DraftPlace, code: List<String>): DraftSplice {
            var (keepAbove, resumeAt) = when (place) {
                is DraftPlace.Replace -> (place.lines.from - 1) to place.lines.to
                is DraftPlace.InsertAfter -> place.line.value to place.line.value
            }
            keepAbove = keepAbove.coerceIn(0, file.size)
            resumeAt = resumeAt.coerceIn(keepAbove, file.size)

            val text = (file.take(keepAbove) + code + file.drop(resumeAt)).joinToString("\n")
            return DraftSplice(text, keepAbove, code.size, resumeAt)
        }
    }
}
