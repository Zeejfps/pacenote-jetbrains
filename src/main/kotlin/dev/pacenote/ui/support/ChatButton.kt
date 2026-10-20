package dev.pacenote.ui.support

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ui.ClientProperty
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton

/** A button as wide as its label, the platform's filled default one while [primary] and its plain one otherwise. */
class ChatButton(text: String, primary: Boolean = false) : JButton(text) {
    var primary: Boolean = primary
        set(value) {
            field = value
            ClientProperty.put(this, DarculaButtonUI.DEFAULT_STYLE_KEY, value)
            revalidate()
            repaint()
        }

    init {
        isFocusable = false
        ClientProperty.put(this, DarculaButtonUI.DEFAULT_STYLE_KEY, primary)
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val labelFont = if (primary) font.deriveFont(Font.BOLD) else font
        val fitted = getFontMetrics(labelFont).stringWidth(text) + insets.left + insets.right
        return Dimension(minOf(size.width, maxOf(fitted, JBUI.scale(MIN_WIDTH))), size.height)
    }

    private companion object {
        const val MIN_WIDTH = 36
    }
}
