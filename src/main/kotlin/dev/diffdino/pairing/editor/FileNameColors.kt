package dev.diffdino.pairing.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project

/** How a name is used, as far as the characters around it tell. */
internal enum class NameUse { Call, MemberCall, Member, Plain }

/** The colours the IDE gave a name: its scheme keys, lowest layer first, or attributes when it gave no key. */
internal data class NameColor(val keys: List<TextAttributesKey>, val fallback: TextAttributes?)

/**
 * The colours the IDE's own highlighting gave the names in a file, which a lexer can't know: calls,
 * parameters, members, types. They are read off the highlights already on the file, so any language
 * the IDE colours is covered, and the agent's code wears them where it uses the same names. A name
 * the file colours more than one way is told apart by how it is used, and else takes the commonest.
 */
internal class FileNameColors private constructor(
    private val byUse: Map<Pair<String, NameUse>, NameColor>,
    private val byName: Map<String, NameColor>,
) {
    fun of(name: String, use: NameUse): NameColor? = byUse[name to use] ?: byName[name]

    companion object {
        val NONE = FileNameColors(emptyMap(), emptyMap())

        /** A name as most languages spell one, not part of a longer one. */
        val NAME = Regex("""(?<![\p{L}\p{N}_$])[\p{L}_$][\p{L}\p{N}_$]*""")

        /** The names coloured on [document], in it and in every editor on it. Needs a read action. */
        fun of(project: Project, document: Document): FileNameColors {
            val models = listOfNotNull(DocumentMarkupModel.forDocument(document, project, false)) +
                EditorFactory.getInstance().getEditors(document, project).map { it.markupModel }
            val text = document.immutableCharSequence
            val scheme = EditorColorsManager.getInstance().globalScheme
            val byRange = HashMap<Pair<Int, Int>, MutableList<RangeHighlighter>>()
            for (highlighter in models.flatMap { it.allHighlighters.asList() }.distinct()) {
                if (!highlighter.isValid || highlighter.layer !in HighlighterLayer.SYNTAX..HighlighterLayer.ADDITIONAL_SYNTAX) continue
                val start = highlighter.startOffset
                val end = highlighter.endOffset
                if (end > text.length || !NAME.matches(text.subSequence(start, end))) continue
                byRange.getOrPut(start to end) { ArrayList() }.add(highlighter)
            }

            val counts = HashMap<Pair<String, NameUse>, MutableMap<NameColor, Int>>()
            for ((range, highlighters) in byRange) {
                val color = colorOf(highlighters.sortedBy { it.layer }, scheme) ?: continue
                val name = text.subSequence(range.first, range.second).toString()
                counts.getOrPut(name to useAt(text, range.first, range.second)) { HashMap() }.merge(color, 1, Int::plus)
            }

            val byUse = counts.mapValues { (_, colors) -> colors.maxBy { it.value }.key }
            val byName = byUse.entries.groupBy({ it.key.first }, { it.value })
                .filterValues { it.distinct().size == 1 }
                .mapValues { it.value.first() }
            return FileNameColors(byUse, byName)
        }

        /** How the name from [start] to [end] of [text] is used: called, reached through something else, both, or neither. */
        fun useAt(text: CharSequence, start: Int, end: Int): NameUse {
            var after = end
            while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
            val called = after < text.length && text[after] == '('
            var before = start - 1
            while (before >= 0 && (text[before] == ' ' || text[before] == '\t')) before--
            val member = before >= 0 && (text[before] == '.' || (before >= 1 && text[before - 1] == ':' && text[before] == ':') ||
                (before >= 1 && text[before - 1] == '-' && text[before] == '>'))
            return when {
                called && member -> NameUse.MemberCall
                called -> NameUse.Call
                member -> NameUse.Member
                else -> NameUse.Plain
            }
        }

        // Null for highlights that don't change how the name reads.
        private fun colorOf(highlighters: List<RangeHighlighter>, scheme: EditorColorsScheme): NameColor? {
            val keys = highlighters.mapNotNull { it.textAttributesKey }
            if (keys.isNotEmpty()) {
                return NameColor(keys, null).takeIf { keys.any { key -> scheme.getAttributes(key)?.let(::colors) == true } }
            }

            val attributes = highlighters.firstNotNullOfOrNull { it.getTextAttributes(scheme)?.takeIf(::colors) } ?: return null
            return NameColor(emptyList(), attributes)
        }

        private fun colors(attributes: TextAttributes): Boolean = attributes.foregroundColor != null || attributes.fontType != 0
    }
}
