package dev.diffdino.pairing.ui.support

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import javax.swing.JPanel

/** A panel on a rounded card of [fill], outlined in [outline] when given. */
open class RoundedPanel(
    private val fill: Color,
    private val outline: Color? = null,
    layout: LayoutManager = BorderLayout(),
) : JPanel(layout) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(ARC)
            g2.color = fill
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            if (outline != null) {
                g2.color = outline
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val ARC = 10
    }
}

/** A card with [padding] inside it. */
fun card(fill: Color, outline: Color? = null, padding: Int = 8, layout: LayoutManager = BorderLayout()): RoundedPanel =
    RoundedPanel(fill, outline, layout).apply { border = JBUI.Borders.empty(padding, padding + 2) }
