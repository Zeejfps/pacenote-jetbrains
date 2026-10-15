package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.pairing.AgentPrompt
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.pairing.lines
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.FileOpener
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedPanel
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** What the user said, with the code they sent along, on a card that leaves room on the left. */
class UserBubble(
    message: PairingMessage.FromUser,
    private val root: Path,
    private val project: Project,
    lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(MARGIN)
        val bubble = RoundedPanel(ChatColors.userBubble, layout = VerticalLayout(JBUI.scale(6)))
        bubble.border = JBUI.Borders.empty(8, 10)
        val pane = WrappingHtmlPane()
        Disposer.register(lifetime, pane)
        pane.showPlain(message.text)
        bubble.add(pane)
        message.quote?.let { bubble.add(quoteOf(it)) }
        add(bubble, BorderLayout.CENTER)
    }

    private fun quoteOf(quote: CodeQuote): JComponent = when (quote) {
        is CodeQuote.InFile -> JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            val shown = AgentPrompt.relativeTo(root, quote.absolutePath)
            add(ActionLink("$shown, ${quote.lines}") { FileOpener.open(project, quote.absolutePath, quote.start.value) }.apply {
                toolTipText = quote.absolutePath
            })
            add(EditPreviewPanel.framed(EditPreviewRows.plain(quote.text, QUOTE_LINES), marked = false))
        }
    }

    private companion object {
        const val MARGIN = 28
        const val QUOTE_LINES = 8
    }
}
