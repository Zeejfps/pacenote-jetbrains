package dev.diffdino.pairing.model

/** A 1-based line number in a file: what the gutter shows and what a reader cites. */
@JvmInline
value class FileLine(val value: Int) : Comparable<FileLine> {
    override fun compareTo(other: FileLine): Int = value.compareTo(other.value)
}

/** A point between two characters of a document: a 1-based line and a 0-based column into that
 * line's own characters, its terminator excluded. */
data class TextPosition(val line: FileLine, val column: Int) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int =
        compareValuesBy(this, other, { it.line }, { it.column })

    companion object {
        fun at(line: Int, column: Int) = TextPosition(FileLine(line), column)
    }
}

/** A run of file lines, first to last, 1-based and inclusive. */
data class LineSpan(val from: Int, val to: Int) {
    fun contains(line: Int): Boolean = line in from..to
}

/** Where the user's caret is, and what they have selected. */
data class EditorCaret(val path: String, val at: TextPosition, val selectedText: String)

/** Code the user sent along with a message. */
sealed interface CodeQuote {
    val text: String

    /** Selected in a file: its first and last line, both 1-based and inclusive. */
    data class InFile(val absolutePath: String, val start: FileLine, val end: FileLine, override val text: String) : CodeQuote

    companion object {
        const val MAX_LINES = 1000
    }
}
