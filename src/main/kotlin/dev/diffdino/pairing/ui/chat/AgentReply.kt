package dev.diffdino.pairing.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/** What the agent wrote, as markdown, kept up with the text while it streams in. */
class AgentReply(private val markdown: StateFlow<String>, lifetime: Lifetime) : JPanel(BorderLayout()) {
    private val pane = WrappingHtmlPane()

    init {
        isOpaque = false
        Disposer.register(lifetime, pane)
        lifetime.bindThrottled(markdown, RENDER_PAUSE_MILLIS) { pane.showMarkdown(it) }
        add(pane, BorderLayout.CENTER)
        add(copyLink(), BorderLayout.EAST)
    }

    private fun copyLink(): JPanel {
        val copy = JBLabel(AllIcons.Actions.Copy).apply {
            toolTipText = DiffDinoBundle.message("reply.copy")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(markdown.value))
                }
            })
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(6)
            add(copy, BorderLayout.NORTH)
        }
    }

    private companion object {
        const val RENDER_PAUSE_MILLIS = 50L
    }
}
