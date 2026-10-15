package dev.diffdino.pairing.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.CornerOverlayLayout
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.HoverArea
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedCard
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import kotlinx.coroutines.flow.StateFlow
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** What the agent wrote, as markdown, in a card, kept up with the text while it streams in. A copy
 * button shows in the corner while the reply is hovered. */
class AgentReply(private val markdown: StateFlow<String>, lifetime: Lifetime) : JPanel(null) {
    private val pane = WrappingHtmlPane()
    private val card = RoundedCard(ChatColors.raised)
    private val copy = copyLink()

    init {
        isOpaque = false
        Disposer.register(lifetime, pane)
        lifetime.bindThrottled(markdown, RENDER_PAUSE_MILLIS) { pane.showMarkdown(it) }
        card.add(pane)
        add(copy)
        add(card)
        layout = CornerOverlayLayout(card, copy)
        HoverArea(this) { copy.isVisible = it }
    }

    private fun copyLink() = JBLabel(AllIcons.Actions.Copy).apply {
        toolTipText = DiffDinoBundle.message("reply.copy")
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(markdown.value))
            }
        })
    }

    private companion object {
        const val RENDER_PAUSE_MILLIS = 50L
    }
}
