package dev.diffdino.pairing.ui.support

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/** A small solid square in the secondary text colour: stop, without the red. */
object StopIcon : Icon {
    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            g2.color = ChatColors.secondaryText
            val side = JBUI.scale(SQUARE).toFloat()
            val inset = (iconWidth - side) / 2f
            val arc = JBUIScale.scale(2f)
            g2.fill(RoundRectangle2D.Float(x + inset, y + inset, side, side, arc, arc))
        } finally {
            g2.dispose()
        }
    }

    private const val SIZE = 16
    private const val SQUARE = 8
}
