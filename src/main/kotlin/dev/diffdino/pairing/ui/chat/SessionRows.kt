package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.NoticeTone
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Where a pairing session began in the conversation. The live session's goal is in its own pane,
 * so its start is left out. */
class SessionStartedRow(
    private val started: PairingMessage.SessionStarted,
    private val conversation: AgentConversation,
    lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val pane = WrappingHtmlPane()
        Disposer.register(lifetime, pane)
        pane.showPlain(DiffDinoBundle.message("session.started", started.goal), ChatColors.secondaryText, bold = true)
        add(pane, BorderLayout.CENTER)
        lifetime.bind(conversation.session) { isVisible = !isLiveStart() }
    }

    private fun isLiveStart(): Boolean {
        if (conversation.session.value == null) return false
        val last = conversation.transcript.messages.value.lastOrNull { it is PairingMessage.SessionStarted }
        return last === started
    }
}

/** How a session ended, in the conversation where it did: the agent's summary, or why it failed. */
object SessionOutcomeRow {
    fun create(outcome: PairingPhase, lifetime: Lifetime): JComponent = when (outcome) {
        is PairingPhase.Ended -> outcome.summary?.let { summary(it, lifetime) }
            ?: NoticeRow.of(DiffDinoBundle.message("status.session.ended"), NoticeTone.Info, lifetime)
        is PairingPhase.Failed -> NoticeRow.of(outcome.reason, NoticeTone.Error, lifetime)
        is PairingPhase.Disconnected -> NoticeRow.of(outcome.reason, NoticeTone.Info, lifetime)
        PairingPhase.Starting, is PairingPhase.Running -> throw IllegalArgumentException("A live session has no outcome.")
    }

    private fun summary(markdown: String, lifetime: Lifetime): JComponent {
        val pane = WrappingHtmlPane()
        Disposer.register(lifetime, pane)
        pane.showMarkdown(markdown)
        return JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel(DiffDinoBundle.message("session.summary")).apply {
                font = JBFont.small().asBold()
                foreground = ChatColors.secondaryText
            })
            add(pane)
        }
    }
}
