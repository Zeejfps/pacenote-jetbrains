package dev.diffdino.pairing.editor

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle

/** One line of a draft as it is drawn: its text, its colours, and the stretches of it that name something undeclared. */
internal data class GhostRow(val text: String, val runs: List<ColoredRun>, val missing: List<IntRange>)

/**
 * Draws the agent's code between real lines of an editor, read-only and tinted, with the pills that
 * take it, and tells where those pills are so a click can be matched to one.
 */
internal class DraftBlockRenderer(private val editor: Editor) : EditorCustomElementRenderer {
    var rows: List<GhostRow> = emptyList()
    var hovered: DraftCommand? = null

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = codeMetrics()
        val widest = rows.maxOfOrNull { layout(it.text, fm).last() } ?: 0
        val pills = pillRects(Rectangle(0, 0, 0, 0)).maxOfOrNull { it.second.x + it.second.width } ?: 0
        val visible = editor.scrollingModel.visibleArea.width
        return maxOf(widest + 2 * padding(), pills + padding(), visible, 1)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = headerHeight() + rows.size * editor.lineHeight + JBUI.scale(2)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            val scheme = editor.colorsScheme
            g2.color = ColorUtil.withAlpha(insertedBackground(scheme), TINT_ALPHA)
            g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
            paintPills(g2, targetRegion, scheme)
            paintRows(g2, targetRegion, scheme)
        } finally {
            g2.dispose()
        }
    }

    /** The pill at a point of the editor, given where this block's inlay is. */
    fun pillAt(point: Point, bounds: Rectangle): DraftCommand? =
        pillRects(bounds).firstOrNull { it.second.contains(point) }?.first

    private fun paintRows(g: Graphics2D, region: Rectangle, scheme: EditorColorsScheme) {
        UISettings.setupAntialiasing(g)
        val fm = codeMetrics()
        val top = region.y + headerHeight()
        val originX = region.x + padding()
        for ((index, row) in rows.withIndex()) {
            val xs = layout(row.text, fm)
            val rowTop = top + index * editor.lineHeight
            val baseline = rowTop + (editor.lineHeight - fm.height) / 2 + fm.ascent
            for (segment in segments(row, scheme)) {
                g.font = segment.font
                g.color = segment.color
                var from = segment.start
                while (from < segment.end) {
                    if (row.text[from] == '\t') {
                        from++
                        continue
                    }

                    var to = from
                    while (to < segment.end && row.text[to] != '\t') to++
                    g.drawString(row.text.substring(from, to), originX + xs[from], baseline)
                    from = to
                }
            }

            paintMissing(g, row, xs, originX, rowTop + editor.lineHeight - JBUI.scale(2), scheme)
        }
    }

    private fun paintMissing(g: Graphics2D, row: GhostRow, xs: IntArray, originX: Int, y: Int, scheme: EditorColorsScheme) {
        if (row.missing.isEmpty()) return
        val dash = JBUI.scale(2).toFloat()
        g.color = missingColor(scheme)
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(dash, dash), 0f)
        for (name in row.missing) g.drawLine(originX + xs[name.first], y, originX + xs[name.last + 1], y)
    }

    private data class Segment(val start: Int, val end: Int, val font: Font, val color: Color)

    private fun segments(row: GhostRow, scheme: EditorColorsScheme): List<Segment> {
        val base = scheme.getAttributes(HighlighterColors.TEXT)
        val segments = ArrayList<Segment>()
        var at = 0
        for (run in row.runs.sortedBy { it.start }) {
            val start = run.start.coerceIn(at, row.text.length)
            val end = run.end.coerceIn(start, row.text.length)
            if (start > at) segments.add(segment(at, start, base, scheme))
            if (end > start) segments.add(segment(start, end, attributesOf(run, base, scheme), scheme))
            at = maxOf(at, end)
        }

        if (at < row.text.length) segments.add(segment(at, row.text.length, base, scheme))
        return segments
    }

    private fun attributesOf(run: ColoredRun, base: TextAttributes, scheme: EditorColorsScheme): TextAttributes {
        var merged = base
        for (key in run.keys) scheme.getAttributes(key)?.let { merged = TextAttributes.merge(merged, it) }
        return run.fallback?.let { TextAttributes.merge(merged, it) } ?: merged
    }

    private fun segment(start: Int, end: Int, attributes: TextAttributes, scheme: EditorColorsScheme): Segment {
        val type = when (attributes.fontType) {
            Font.BOLD -> EditorFontType.BOLD
            Font.ITALIC -> EditorFontType.ITALIC
            Font.BOLD or Font.ITALIC -> EditorFontType.BOLD_ITALIC
            else -> EditorFontType.PLAIN
        }
        return Segment(start, end, scheme.getFont(type), attributes.foregroundColor ?: scheme.defaultForeground)
    }

    private fun paintPills(g: Graphics2D, region: Rectangle, scheme: EditorColorsScheme) {
        GraphicsUtil.setupAAPainting(g)
        val foreground = scheme.defaultForeground
        val background = scheme.defaultBackground
        val bold = pillFont().deriveFont(Font.BOLD)
        val fm = editor.contentComponent.getFontMetrics(bold)
        for ((command, rect) in pillRects(region)) {
            g.color = ColorUtil.mix(background, foreground, if (command == hovered) PILL_HOVER_MIX else PILL_MIX)
            g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, rect.height, rect.height)
            val baseline = rect.y + (rect.height - fm.height) / 2 + fm.ascent
            g.font = bold
            g.color = foreground
            g.drawString(command.label, rect.x + pillPadding(), baseline)
            val hint = hintOf(command)
            if (hint.isNotEmpty()) {
                g.font = pillFont()
                g.color = ColorUtil.mix(background, foreground, HINT_MIX)
                g.drawString(hint, rect.x + pillPadding() + fm.stringWidth(command.label) + pillGap(), baseline)
            }
        }
    }

    // Where each pill is, from the left of the header; the origin is that of the block.
    private fun pillRects(region: Rectangle): List<Pair<DraftCommand, Rectangle>> {
        val bold = editor.contentComponent.getFontMetrics(pillFont().deriveFont(Font.BOLD))
        val plain = editor.contentComponent.getFontMetrics(pillFont())
        val height = headerHeight() - JBUI.scale(4)
        var x = region.x + padding()
        return DraftCommand.entries.map { command ->
            val hint = hintOf(command)
            val hintWidth = if (hint.isEmpty()) 0 else pillGap() + plain.stringWidth(hint)
            val width = 2 * pillPadding() + bold.stringWidth(command.label) + hintWidth
            val rect = Rectangle(x, region.y + JBUI.scale(2), width, height)
            x += width + pillSpacing()
            command to rect
        }
    }

    private fun hintOf(command: DraftCommand): String = KeymapUtil.getFirstKeyboardShortcutText(command.actionId)

    private fun pillFont(): Font = JBUI.Fonts.smallFont()

    private fun headerHeight(): Int = editor.lineHeight + JBUI.scale(4)

    private fun codeMetrics(): FontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))

    // Where each character starts, from the left of the text, with tabs running to the editor's tab stops.
    private fun layout(text: String, fm: FontMetrics): IntArray {
        val tab = maxOf(1, editor.settings.getTabSize(editor.project) * fm.charWidth(' '))
        val xs = IntArray(text.length + 1)
        for (i in text.indices) {
            xs[i + 1] = if (text[i] == '\t') (xs[i] / tab + 1) * tab else xs[i] + fm.charWidth(text[i])
        }

        return xs
    }

    private fun insertedBackground(scheme: EditorColorsScheme): Color =
        scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor ?: JBColor(Color(0xE6F4EA), Color(0x2B3A2F))

    private fun missingColor(scheme: EditorColorsScheme): Color =
        scheme.getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)?.let { it.effectColor ?: it.errorStripeColor }
            ?: JBColor(Color(0xE6A700), Color(0xD9A343))

    private fun padding() = JBUI.scale(8)

    private fun pillPadding() = JBUI.scale(8)

    private fun pillGap() = JBUI.scale(6)

    private fun pillSpacing() = JBUI.scale(6)

    private companion object {
        const val TINT_ALPHA = 0.65
        const val PILL_MIX = 0.1
        const val PILL_HOVER_MIX = 0.22
        const val HINT_MIX = 0.55
    }
}
