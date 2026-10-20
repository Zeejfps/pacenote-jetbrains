package dev.pacenote.ui.support

import com.intellij.openapi.Disposable
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/** Three dots breathing in turn while [running]. */
class TypingDots : JComponent(), Disposable {
    private val started = System.nanoTime()
    private val ticker = Timer(FRAME_MILLIS) { if (isShowing) repaint() }

    var running: Boolean = false
        set(value) {
            field = value
            if (value && !ticker.isRunning) ticker.start()
            if (!value) ticker.stop()
        }

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val dot = JBUI.scale(DOT)
        return Dimension(3 * dot + 2 * JBUI.scale(GAP), dot + JBUI.scale(LIFT))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val dot = JBUI.scale(DOT)
            val lift = JBUI.scale(LIFT)
            val seconds = (System.nanoTime() - started) / 1e9
            for (i in 0 until 3) {
                val breath = breath(seconds / PERIOD_SECONDS - i * STAGGER)
                g2.color = ChatColors.secondaryText
                g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, (0.35 + 0.65 * breath).toFloat())
                g2.fillOval(i * (dot + JBUI.scale(GAP)), lift - (lift * breath).toInt(), dot, dot)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun breath(phase: Double): Double = 0.5 - 0.5 * cos((phase - floor(phase)) * 2 * PI)

    override fun dispose() {
        ticker.stop()
    }

    private companion object {
        const val DOT = 7
        const val GAP = 4
        const val LIFT = 3
        const val FRAME_MILLIS = 40
        const val PERIOD_SECONDS = 1.1
        const val STAGGER = 0.18
    }
}
