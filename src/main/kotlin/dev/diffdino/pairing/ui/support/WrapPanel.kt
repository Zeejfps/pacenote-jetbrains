package dev.diffdino.pairing.ui.support

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** A row of components that breaks into more rows when it is narrower than they are wide. */
class WrapPanel(gap: Int = 6) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(gap), JBUI.scale(gap))) {
    private var knownWidth = 0

    init {
        isOpaque = false
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val widthChanged = width != knownWidth
        knownWidth = width
        super.setBounds(x, y, width, height)
        if (widthChanged) SwingUtilities.invokeLater { revalidate() }
    }
}
