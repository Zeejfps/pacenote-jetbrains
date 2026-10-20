package dev.pacenote.ui.chat

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

/** Rows of code in the editor font: removed and added lines on the diff colours of the editor scheme. */
class EditPreviewPanel(private val rows: List<PreviewRow>, private val marked: Boolean = true) : JComponent() {
    init {
        isOpaque = false
    }

    private fun codeFont(): Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

    private fun rowHeight(): Int = getFontMetrics(codeFont()).height + JBUI.scale(2)

    private fun textOf(row: PreviewRow): String = when (row) {
        is PreviewRow.FileHeader -> row.path
        is PreviewRow.Code -> (if (marked) row.kind.marker + " " else "") + row.text.replace("\t", "    ")
        is PreviewRow.More -> PacenoteBundle.message("preview.more.lines", row.lines)
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(codeFont())
        val widest = rows.maxOfOrNull { metrics.stringWidth(textOf(it)) } ?: 0
        return Dimension(widest + 2 * JBUI.scale(PAD), rows.size * rowHeight() + JBUI.scale(4))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val scheme = EditorColorsManager.getInstance().globalScheme
        g2.font = codeFont()
        val metrics = g2.fontMetrics
        val height = rowHeight()
        val pad = JBUI.scale(PAD)
        var y = JBUI.scale(2)
        for (row in rows) {
            val background = when (row) {
                is PreviewRow.Code -> when (row.kind) {
                    PreviewRow.Code.Kind.Added -> ChatColors.addedLine
                    PreviewRow.Code.Kind.Removed -> ChatColors.removedLine
                    PreviewRow.Code.Kind.Context -> null
                }
                is PreviewRow.FileHeader, is PreviewRow.More -> null
            }
            if (background != null) {
                g2.color = background
                g2.fillRect(0, y, width, height)
            }
            g2.color = when (row) {
                is PreviewRow.Code -> scheme.defaultForeground
                is PreviewRow.FileHeader, is PreviewRow.More -> ChatColors.secondaryText
            }
            if (row is PreviewRow.FileHeader) g2.font = codeFont().deriveFont(Font.BOLD)
            g2.drawString(textOf(row), pad, y + (height + metrics.ascent - metrics.descent) / 2)
            if (row is PreviewRow.FileHeader) g2.font = codeFont()
            y += height
        }
    }

    companion object {
        private const val PAD = 6

        /** [rows] in a frame that scrolls sideways when a line is wider than the card. */
        fun framed(rows: List<PreviewRow>, marked: Boolean = true): JComponent = JBScrollPane(
            EditPreviewPanel(rows, marked),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        ).apply {
            border = JBUI.Borders.customLine(ChatColors.border, 1)
            viewport.isOpaque = false
            isOpaque = false
        }
    }
}
