package dev.pacenote.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.RoundedCard
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/** A one-line card that opens to [details] when clicked, and closes again. [details] is built on
 * the first opening. */
class ExpandableRow(private val headline: String, private val details: () -> JComponent) : RoundedCard(ChatColors.raised, vertical = 8) {
    private val title = JBLabel(headline, AllIcons.General.ArrowRight, JBLabel.LEADING).apply { foreground = ChatColors.secondaryText }
    private val body = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
    }
    private var built = false

    init {
        title.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        title.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        })
        add(title, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    private fun toggle() {
        val opening = !body.isVisible
        if (opening && !built) {
            built = true
            body.border = JBUI.Borders.emptyTop(8)
            body.add(details(), BorderLayout.CENTER)
        }
        body.isVisible = opening
        title.icon = if (opening) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        revalidate()
        repaint()
    }
}
