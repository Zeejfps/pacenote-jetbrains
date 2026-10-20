package dev.pacenote.ui

import dev.pacenote.ui.support.StickToBottom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class StickToBottomTest {
    private val content = JPanel().apply { preferredSize = Dimension(200, 1000) }
    private val pane = JScrollPane(content)
    private val follow = StickToBottom(pane)

    init {
        follow.follow(content)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun layout(height: Int) = onEdt {
        pane.setSize(200, height)
        pane.doLayout()
        pane.viewport.doLayout()
        content.revalidate()
        pane.validate()
    }

    private fun flush() = repeat(3) { onEdt { } }

    private fun atEnd(): Boolean {
        val bar = pane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - 4
    }

    @Test
    fun followsTheEndThroughAViewportResize() {
        layout(300)
        onEdt { follow.pin() }
        onEdt {
            content.preferredSize = Dimension(200, 1400)
            pane.setSize(200, 360)
            pane.validate()
        }
        flush()
        onEdt {
            content.preferredSize = Dimension(200, 1600)
            pane.validate()
        }
        flush()
        assertTrue(atEnd())
    }

    @Test
    fun wheelingUpStopsFollowing() {
        layout(300)
        onEdt { follow.pin() }
        flush()
        onEdt {
            pane.verticalScrollBar.value = 100
            pane.dispatchEvent(MouseWheelEvent(pane, MouseWheelEvent.MOUSE_WHEEL, 0, 0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1))
        }
        flush()
        var before = 0
        onEdt { before = pane.verticalScrollBar.value }
        onEdt {
            content.preferredSize = Dimension(200, 1400)
            pane.validate()
        }
        flush()
        assertTrue(before < 700)
        assertEquals(before, pane.verticalScrollBar.value)
    }
}
