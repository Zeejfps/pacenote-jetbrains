package dev.pacenote.ui.support

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/** How round the corners of a [RoundedCard] are. */
sealed interface CardCorner {
    /** Corners of a set radius. */
    data class Fixed(val radius: Int) : CardCorner

    /** As round as the card's height allows, up to [cap]: a pill for one line, a soft card for more. */
    data class Pill(val cap: Int) : CardCorner
}

/** A panel on a rounded card of [fill], outlined in [outline] when given, with [vertical] and
 * [horizontal] room inside it. */
open class RoundedCard(
    private val fill: Color,
    outline: Color? = ChatColors.border,
    private val corner: CardCorner = CardCorner.Fixed(RADIUS),
    layout: LayoutManager = BorderLayout(),
    vertical: Int = 12,
    horizontal: Int = 16,
) : JPanel(layout) {
    var outline: Color? = outline
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(vertical, horizontal)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = 2f * radius()
            g2.color = fill
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            outline?.let { ring ->
                g2.color = ring
                g2.stroke = BasicStroke(1f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc - 1f, arc - 1f))
            }
        } finally {
            g2.dispose()
        }
    }

    private fun radius(): Float = when (corner) {
        is CardCorner.Fixed -> JBUI.scale(corner.radius).toFloat()
        is CardCorner.Pill -> minOf(JBUI.scale(corner.cap).toFloat(), height / 2f)
    }

    private companion object {
        const val RADIUS = 12
    }
}
