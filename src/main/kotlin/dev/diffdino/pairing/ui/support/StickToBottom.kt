package dev.diffdino.pairing.ui.support

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Keeps a scroll pane at the end of what grows in it, until the user scrolls up from the end; it
 * follows again once they are back there, or when [pin] says so.
 */
class StickToBottom(private val pane: JScrollPane) {
    private var pinned = true
    private var lastMaximum = 0

    init {
        val bar = pane.verticalScrollBar
        bar.addAdjustmentListener {
            val maximum = bar.maximum
            if (maximum == lastMaximum) pinned = atEnd()
            lastMaximum = maximum
        }
    }

    /** Follows [content]'s growth. */
    fun follow(content: Component) {
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (pinned) scrollToEnd()
            }
        })
    }

    /** Goes to the end and follows from there, as when the user has just said something. */
    fun pin() {
        pinned = true
        scrollToEnd()
    }

    private fun atEnd(): Boolean {
        val bar = pane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - SLACK
    }

    private fun scrollToEnd() {
        SwingUtilities.invokeLater {
            val bar = pane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private companion object {
        const val SLACK = 4
    }
}
