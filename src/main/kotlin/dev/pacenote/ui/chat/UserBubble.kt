package dev.pacenote.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.pacenote.model.CodeQuote
import dev.pacenote.pairing.AgentPrompt
import dev.pacenote.pairing.PairingMessage
import dev.pacenote.pairing.lines
import dev.pacenote.ui.support.FileOpener
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.PillBubble
import dev.pacenote.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/** What the user said, with the code they sent along, in a bubble on the right that is as wide as
 * the words and no wider than most of the row. */
class UserBubble(
    message: PairingMessage.FromUser,
    private val root: Path,
    private val project: Project,
    lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val pane = WrappingHtmlPane()
        Disposer.register(lifetime, pane)
        pane.showPlain(message.text)
        val content = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            isOpaque = false
            add(pane)
            message.quote?.let { add(quoteOf(it)) }
        }
        val wanted = if (message.quote == null) ({ pane.widestLine(message.text) + JBUI.scale(SLACK) }) else ({ Int.MAX_VALUE / 2 })
        add(PillBubble(content, wanted), BorderLayout.CENTER)
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
        const val QUOTE_LINES = 8
        const val SLACK = 6
    }
}
