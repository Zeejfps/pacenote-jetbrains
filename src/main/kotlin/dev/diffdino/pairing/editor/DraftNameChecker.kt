package dev.diffdino.pairing.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil

/** A name in the agent's code that nothing declares: the line of the code it is on, 0-based, and its characters in that line. */
internal data class MissingName(val line: Int, val start: Int, val end: Int, val text: String)

/**
 * What the IDE makes of the names in the agent's code for a stop, asked before the code is in the
 * file: the file as it would read with the code spliced in is parsed on the side, and the code's
 * references are resolved there. A name that resolves nowhere and reads like a code name is missing.
 */
internal object DraftNameChecker {
    /** More than a step's worth; a block past it is checked in part. */
    private const val MAX_NAMES = 200

    private val identifier = Regex("""[\p{L}_$][\p{L}\p{N}_$]*""")

    // The language's own literals and receivers, which nothing declares.
    private val builtIns = setOf(
        "undefined", "null", "NaN", "Infinity", "true", "false", "this", "super", "arguments", "self", "None", "nil", "cls",
    )

    /** The missing names, in the order the code uses them. Needs a read action in smart mode. */
    fun missing(project: Project, original: PsiFile, splice: DraftSplice): List<MissingName> {
        if (splice.codeLines == 0) return emptyList()
        val copy = PsiFileFactory.getInstance(project).createFileFromText(splice.text, original) ?: return emptyList()
        val lineStarts = splice.text.indices.filter { splice.text[it] == '\n' }.map { it + 1 }.let { listOf(0) + it }
        val first = splice.lineOfCode(0) - 1
        val codeStart = lineStarts[first]
        val codeEnd = lineStarts.getOrNull(first + splice.codeLines)?.minus(1) ?: splice.text.length
        val range = TextRange(codeStart, codeEnd)

        val found = LinkedHashMap<Pair<Int, Int>, MissingName>()
        for (element in elementsIn(copy, range)) {
            for (reference in element.references) {
                if (found.size >= MAX_NAMES) return found.values.toList()
                missingName(reference, copy.text, range, lineStarts, splice)?.let { found.putIfAbsent(it.line to it.start, it) }
            }
        }

        return found.values.sortedWith(compareBy({ it.line }, { it.start }))
    }

    private fun elementsIn(file: PsiFile, range: TextRange): List<PsiElement> {
        val start = file.findElementAt(range.startOffset) ?: return emptyList()
        val end = file.findElementAt(maxOf(range.startOffset, range.endOffset - 1)) ?: start
        val root = PsiTreeUtil.findCommonParent(start, end) ?: return emptyList()
        val inside = ArrayList<PsiElement>()
        PsiTreeUtil.processElements(root) { element ->
            if (element.textRange.intersects(range)) inside.add(element)
            true
        }

        return inside
    }

    private fun missingName(
        reference: PsiReference,
        text: CharSequence,
        range: TextRange,
        lineStarts: List<Int>,
        splice: DraftSplice,
    ): MissingName? {
        if (reference.isSoft) return null
        val element = reference.element
        val start = element.textRange.startOffset + reference.rangeInElement.startOffset
        val end = element.textRange.startOffset + reference.rangeInElement.endOffset
        if (start < range.startOffset || end > range.endOffset || end <= start) return null

        val name = text.subSequence(start, end).toString()
        if (!identifier.matches(name) || name in builtIns || isQualified(text, start)) return null
        if (resolves(reference)) return null

        val line = lineStarts.indexOfLast { it <= start }
        return MissingName(line - splice.keptAbove - 1, start - lineStarts[line], end - lineStarts[line], name)
    }

    // A member of something else: whether it exists is that thing's business, and often unknowable.
    private fun isQualified(text: CharSequence, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        if (i < 0) return false
        return text[i] == '.' || (i >= 1 && text[i] == ':' && text[i - 1] == ':') || (i >= 1 && text[i] == '>' && text[i - 1] == '-')
    }

    private fun resolves(reference: PsiReference): Boolean =
        if (reference is PsiPolyVariantReference) reference.multiResolve(false).any { it.isValidResult } else reference.resolve() != null
}
