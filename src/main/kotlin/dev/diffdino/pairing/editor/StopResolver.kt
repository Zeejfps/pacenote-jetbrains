package dev.diffdino.pairing.editor

import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopMiss
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.TextPosition

/**
 * Finds where a stop's named declaration is in a file's current text: through the outline where the
 * IDE has one, and by the name as a whole word where it doesn't or the outline misses it. Never by
 * line number, so a stop survives whatever the user typed since the agent last looked.
 */
internal object StopResolver {
    private const val KNOWN_LIMIT = 40

    /** [text] is the file's text, or null when there is no such file yet; a file with nothing in it counts as new. */
    fun resolve(absolutePath: String, text: String?, outline: List<OutlineNode>?, target: StopTarget): StopPlacement {
        if (text == null || text.isBlank()) return StopPlacement.Placed(StopLocation.NewFile(absolutePath))
        val lines = text.split('\n').map { it.trimEnd('\r') }

        // A declaration still to be written is often already called in the file: with an outline to
        // go by and a place to put it, a bare mention of its name is not where it is.
        val byText = outline == null || target.after.isNullOrEmpty()
        find(target.symbol, lines, outline, byText)?.let { found ->
            val line = found.at.line.value
            return StopPlacement.Placed(
                StopLocation.OnSymbol(
                    absolutePath,
                    found.at,
                    lineAt(lines, line),
                    FileLine(found.endLine.coerceIn(line, lines.size)),
                    lines,
                ),
            )
        }

        val after = target.after?.takeIf { it.isNotEmpty() }
            ?: return StopPlacement.Missed(StopMiss.NoSuchSymbol(target.path, target.symbol, known(outline)))

        val anchor = find(after, lines, outline, byText = true)
            ?: return StopPlacement.Missed(StopMiss.NoSuchAfter(target.path, after, known(outline)))

        // The end of the declaration it follows: Enter from there is where the new one starts.
        val line = anchor.endLine.coerceIn(1, lines.size)
        return StopPlacement.Placed(
            StopLocation.Insertion(absolutePath, TextPosition.at(line, lineAt(lines, line).length), after, lines),
        )
    }

    private data class Found(val at: TextPosition, val endLine: Int)

    private data class Declaration(val path: String, val node: OutlineNode, val parentEnd: Int)

    private fun find(symbol: String, lines: List<String>, outline: List<OutlineNode>?, byText: Boolean): Found? {
        val wanted = normalize(symbol)
        if (wanted.isEmpty()) return null

        if (outline != null) {
            val named = declarations(outline)
            for (ignoreCase in listOf(false, true)) {
                val match = named.firstOrNull { matches(it.path, wanted, ignoreCase) } ?: continue
                return Found(match.node.nameAt, lastLineOf(match.node, match.parentEnd, lines))
            }
        }

        if (!byText) return null
        val name = lastSegment(wanted)
        for ((index, line) in lines.withIndex()) {
            val column = wholeWord(line, name)
            if (column >= 0) return Found(TextPosition.at(index + 1, column), index + 1)
        }

        return null
    }

    // Each declaration under its containment path — Class.method — which is how a model names a
    // symbol, with the last line of what contains it.
    private fun declarations(outline: List<OutlineNode>): List<Declaration> {
        val named = ArrayList<Declaration>()
        fun walk(nodes: List<OutlineNode>, parent: String?, parentEnd: Int) {
            for (node in nodes) {
                val path = if (parent == null) node.name else "$parent.${node.name}"
                named.add(Declaration(path, node, parentEnd))
                walk(node.children, path, node.endLine.value)
            }
        }

        walk(outline, null, Int.MAX_VALUE)
        return named
    }

    // A member can't end on its container's last line unless it starts there; nor on blank lines.
    private fun lastLineOf(node: OutlineNode, parentEnd: Int, lines: List<String>): Int {
        val nameLine = node.nameAt.line.value
        var end = node.endLine.value
        if (end == parentEnd && end > nameLine) end--
        while (end > nameLine && lineAt(lines, end).isBlank()) end--
        return end
    }

    private fun matches(path: String, wanted: String, ignoreCase: Boolean): Boolean =
        path.equals(wanted, ignoreCase) ||
            (path.length > wanted.length && path.endsWith(wanted, ignoreCase) && path[path.length - wanted.length - 1] == '.')

    // Parameter lists and generic arguments go: they are how a model decorates a name, and the
    // outline's own parameter text rarely spells them the same way.
    private fun normalize(symbol: String): String {
        var trimmed = symbol.trim()
        val cut = trimmed.indexOfFirst { it == '(' || it == '<' }
        if (cut >= 0) trimmed = trimmed.substring(0, cut)
        return trimmed.replace("::", ".").trim('.', ' ')
    }

    private fun lastSegment(symbol: String): String = symbol.substringAfterLast('.')

    private fun wholeWord(line: String, word: String): Int {
        var from = 0
        while (from <= line.length - word.length) {
            val at = line.indexOf(word, from)
            if (at < 0) return -1
            val end = at + word.length
            if ((at == 0 || !isWordChar(line[at - 1])) && (end == line.length || !isWordChar(line[end]))) return at
            from = at + 1
        }

        return -1
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun lineAt(lines: List<String>, line: Int): String = lines.getOrElse(line - 1) { "" }

    private fun known(outline: List<OutlineNode>?): List<String> =
        outline?.let { declarations(it).take(KNOWN_LIMIT).map { declaration -> declaration.path } }.orEmpty()
}
