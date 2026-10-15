package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.LineSpan
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/** A run of lines the agent points at, and what it says about it. */
internal data class SpotlitRun(val lines: LineSpan, val note: String?)

/**
 * Runs of lines lit up in one file: a soft band behind each run and, after the first line of it, a
 * numbered pin with the agent's note. The runs are anchored to the document, so they follow edits.
 * UI thread only.
 */
internal class LitSpotlights(
    project: Project,
    private val document: Document,
    runs: List<SpotlitRun>,
    parent: Disposable,
) : Disposable {
    private val markup = DocumentMarkupModel.forDocument(document, project, true)
    private val bands: List<RangeHighlighter>
    private val notes = runs.map { it.note }

    init {
        Disposer.register(parent, this)
        bands = runs.map { run ->
            val start = document.lineStart(FileLine(run.lines.from))
            val end = document.lineEnd(FileLine(maxOf(run.lines.from, run.lines.to)))
            markup.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, bandAttributes(), HighlighterTargetArea.LINES_IN_RANGE)
        }

        EditorTracker(project, document, this) { editor, scope -> pin(editor, scope) }
    }

    /** The lines each run covers now, after whatever edits since. */
    fun lines(): List<LineSpan> = bands.map { band ->
        val first = document.lineOf(band.startOffset).value
        val last = document.lineOf(band.endOffset).value
        LineSpan(first, maxOf(first, last))
    }

    private fun pin(editor: Editor, scope: Disposable) {
        for ((index, band) in bands.withIndex()) {
            val at = document.lineEnd(document.lineOf(band.startOffset))
            val inlay = editor.inlayModel.addAfterLineEndElement(at, true, PinRenderer(editor, index + 1, notes[index], band))
            inlay?.let { Disposer.register(scope, it) }
        }
    }

    override fun dispose() {
        bands.forEach(markup::removeHighlighter)
    }

    private fun bandAttributes() = TextAttributes(null, BAND, null, null, Font.PLAIN)

    private companion object {
        val BAND = JBColor(Color(255, 213, 79, 70), Color(255, 213, 79, 40))
    }
}

/** A pin with its number and the agent's note, drawn after the end of a run's first line. */
private class PinRenderer(
    private val editor: Editor,
    private val number: Int,
    private val note: String?,
    private val band: RangeHighlighter,
) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val noteWidth = note?.let { JBUI.scale(NOTE_GAP) + noteMetrics().stringWidth(it) } ?: 0
        return JBUI.scale(PIN_GAP) + pinWidth() + noteWidth
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        if (!band.isValid) return
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val height = editor.lineHeight - JBUI.scale(4)
            val x = targetRegion.x + JBUI.scale(PIN_GAP)
            val y = targetRegion.y + JBUI.scale(2)
            g2.font = pinFont()
            g2.color = PIN
            g2.fillRoundRect(x, y, pinWidth(), height, height, height)
            g2.color = PIN_TEXT
            val fm = g2.fontMetrics
            val label = number.toString()
            g2.drawString(label, x + (pinWidth() - fm.stringWidth(label)) / 2, y + (height - fm.height) / 2 + fm.ascent)
            if (note != null) {
                g2.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                g2.color = editor.colorsScheme.defaultForeground
                val noteFm = g2.fontMetrics
                g2.drawString(note, x + pinWidth() + JBUI.scale(NOTE_GAP), targetRegion.y + (editor.lineHeight - noteFm.height) / 2 + noteFm.ascent)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun pinFont(): Font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)

    private fun pinWidth(): Int = maxOf(editor.lineHeight - JBUI.scale(4), editor.contentComponent.getFontMetrics(pinFont()).stringWidth(number.toString()) + JBUI.scale(10))

    private fun noteMetrics() = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC))

    private companion object {
        const val PIN_GAP = 12
        const val NOTE_GAP = 6
        val PIN = JBColor(Color(0xD9A400), Color(0xC79A2B))
        val PIN_TEXT = JBColor(Color(0x2B2300), Color(0x1E1A0B))
    }
}
