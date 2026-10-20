package dev.pacenote.ui.session

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.pacenote.ui.support.ChatColors
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Icon

/** A small box that is ticked when [done]. */
class TickBox(private val done: Boolean) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val size = iconWidth
            val arc = JBUI.scale(3)
            if (done) {
                g2.color = ChatColors.successText
                g2.fillRoundRect(x, y, size, size, arc, arc)
                g2.color = java.awt.Color.WHITE
                g2.stroke = BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawPolyline(
                    intArrayOf(x + size * 22 / 100, x + size * 43 / 100, x + size * 78 / 100),
                    intArrayOf(y + size * 52 / 100, y + size * 70 / 100, y + size * 32 / 100),
                    3,
                )
            } else {
                g2.color = ChatColors.secondaryText
                g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE = 13
    }
}
