package dev.diffdino.pairing.ui.support

import com.intellij.util.ui.JBUI
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A bubble on the trailing side of a row, as wide as [content] says it wants up to [maxShare] of the
 * row, and as round as its height allows.
 */
class PillBubble(
    content: JComponent,
    private val wantedWidth: () -> Int,
    private val maxShare: Float = MAX_SHARE,
) : JPanel(null) {
    private val bubble = RoundedCard(
        ChatColors.userFill,
        outline = null,
        corner = CardCorner.Pill(CAP),
        vertical = 8,
        horizontal = 14,
    )

    init {
        isOpaque = false
        bubble.add(content)
        add(bubble)
        layout = BubbleLayout()
    }

    private fun widthWithin(row: Int): Int {
        val room = (row * maxShare).toInt()
        val wanted = wantedWidth() + bubble.insets.left + bubble.insets.right
        return minOf(room, wanted)
    }

    private inner class BubbleLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: java.awt.Component?) = Unit

        override fun removeLayoutComponent(comp: java.awt.Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val row = if (parent.width > 0) parent.width else JBUI.scale(FALLBACK_ROW)
            return Dimension(widthWithin(row), bubble.preferredSize.height)
        }

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, bubble.minimumSize.height)

        override fun layoutContainer(parent: Container) {
            val width = widthWithin(parent.width)
            bubble.setBounds(parent.width - width, 0, width, parent.height)
        }
    }

    private companion object {
        const val MAX_SHARE = 0.75f
        const val CAP = 18
        const val FALLBACK_ROW = 320
    }
}
