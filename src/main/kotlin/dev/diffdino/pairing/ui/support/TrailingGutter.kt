package dev.diffdino.pairing.ui.support

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** [content] with room beside it on the trailing edge, where the other side of the conversation sits. */
class TrailingGutter(content: JComponent, gutter: Int = GUTTER) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.emptyRight(gutter)
        add(content, BorderLayout.CENTER)
    }

    companion object {
        const val GUTTER = 32
    }
}
