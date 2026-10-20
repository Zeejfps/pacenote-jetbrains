package dev.pacenote.ui.support

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/** A column of full-width rows that follows the width of the scroll pane it is in. */
open class ScrollableColumn(gap: Int) : JPanel(VerticalLayout(JBUI.scale(gap))), Scrollable {
    init {
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (visibleRect.height * 0.9).toInt()

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    companion object {
        /** [content] in a scroll pane that never scrolls sideways. */
        fun scrolling(content: JComponent): JBScrollPane = JBScrollPane(
            content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }
    }
}
