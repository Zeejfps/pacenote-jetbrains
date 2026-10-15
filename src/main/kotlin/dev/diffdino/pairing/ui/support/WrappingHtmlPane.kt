package dev.diffdino.pairing.ui.support

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import javax.swing.SwingUtilities
import javax.swing.text.View

/**
 * An HTML pane that is as tall as its text is at the width it is given, so rows of it stack in a
 * column without a scroll pane of their own. Text and code follow the editor scheme.
 */
class WrappingHtmlPane : JBHtmlPane(
    JBHtmlPaneStyleConfiguration.builder()
        .colorSchemeProvider { EditorColorsManager.getInstance().globalScheme }
        .enableInlineCodeBackground(true)
        .enableCodeBlocksBackground(true)
        .build(),
    JBHtmlPaneConfiguration(),
) {
    private var knownWidth = 0

    init {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }

    /** Shows [markdown] rendered. */
    fun showMarkdown(markdown: String) {
        text = "<html>" + MarkdownHtml.render(markdown) + "</html>"
        revalidate()
    }

    /** Shows [text] as it is written, line breaks kept, in [color] where given. */
    fun showPlain(text: String, color: Color? = null, small: Boolean = false, bold: Boolean = false, struck: Boolean = false) {
        this.text = MarkdownHtml.plain(text, color?.let(ColorUtil::toHex), small, bold, struck)
        revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val width = if (knownWidth > 0) knownWidth else JBUI.scale(FALLBACK_WIDTH)
        val insets = insets
        val root = getUI().getRootView(this)
        root.setSize((width - insets.left - insets.right).toFloat(), Int.MAX_VALUE.toFloat())
        val height = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        return Dimension(width, height)
    }

    /** How wide the longest line of [text] runs unwrapped, in this pane's font. */
    fun widestLine(text: String): Int {
        val metrics = getFontMetrics(font)
        val widest = text.lineSequence().maxOfOrNull { metrics.stringWidth(it) } ?: 0
        return widest + insets.left + insets.right
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val widthChanged = width != knownWidth
        knownWidth = width
        super.setBounds(x, y, width, height)
        if (widthChanged) SwingUtilities.invokeLater { revalidate() }
    }

    private companion object {
        const val FALLBACK_WIDTH = 320
    }
}
