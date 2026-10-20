package dev.pacenote.ui.support

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Keeps a scroll pane at the end of what grows in it, until the user scrolls up from the end with
 * the wheel or the scrollbar; it follows again once they are back there, or when [pin] says so.
 * Layout moving the view never counts as the user leaving the end.
 */
class StickToBottom(private val pane: JScrollPane) {
    private var pinned = true

    init {
        val bar = pane.verticalScrollBar
        bar.addAdjustmentListener {
            if (atEnd()) pinned = true
            else if (it.valueIsAdjusting) pinned = false
        }
        pane.addMouseWheelListener { if (it.wheelRotation < 0) pinned = false }
        pane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (pinned) scrollToEnd()
            }
        })
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
            if (!pinned) return@invokeLater
            val bar = pane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private companion object {
        const val SLACK = 4
    }
}
