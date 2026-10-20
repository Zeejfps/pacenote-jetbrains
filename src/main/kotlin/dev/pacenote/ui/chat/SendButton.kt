package dev.pacenote.ui.chat

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent

/** A round accent button with an arrow pointing up, which sends what has been written. */
class SendButton(private val onSend: () -> Unit) : JComponent() {
    private var hovered = false

    init {
        isOpaque = false
        toolTipText = PacenoteBundle.message("chat.send")
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = hover(true)

            override fun mouseExited(e: MouseEvent) = hover(false)

            override fun mouseClicked(e: MouseEvent) {
                if (isEnabled) onSend()
            }
        })
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON

                override fun getAccessibleName(): String = PacenoteBundle.message("chat.send")
            }
        }
        return accessibleContext
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        repaint()
    }

    override fun getPreferredSize(): Dimension = JBUI.size(SIZE)

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            if (!isEnabled) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA)
            val diameter = minOf(width, height)
            g2.color = if (hovered && isEnabled) ChatColors.accentFill.brighter() else ChatColors.accentFill
            g2.fillOval((width - diameter) / 2, (height - diameter) / 2, diameter, diameter)
            g2.color = ChatColors.onAccent
            g2.stroke = BasicStroke(JBUIScale.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(arrow(width / 2f, height / 2f, diameter / 2f))
        } finally {
            g2.dispose()
        }
    }

    private fun arrow(cx: Float, cy: Float, radius: Float): Path2D.Float = Path2D.Float().apply {
        val reach = radius * ARROW_REACH
        moveTo(cx, cy + reach)
        lineTo(cx, cy - reach)
        moveTo(cx - reach * HEAD, cy - reach * (1 - HEAD))
        lineTo(cx, cy - reach)
        lineTo(cx + reach * HEAD, cy - reach * (1 - HEAD))
    }

    private fun hover(value: Boolean) {
        hovered = value
        repaint()
    }

    private companion object {
        const val SIZE = 28
        const val DISABLED_ALPHA = 0.4f
        const val ARROW_REACH = 0.45f
        const val HEAD = 0.75f
    }
}
