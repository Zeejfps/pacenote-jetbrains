package dev.diffdino.pairing.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.pairing.OpenStop
import dev.diffdino.pairing.pairing.PairingSession
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ReactiveSlot
import dev.diffdino.pairing.ui.support.RoundedPanel
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** The stop's place in the session pane: the open stop, a placeholder while the agent works out
 * the next one, and nothing once the session is over. Keyed on the stop's number, so a change within
 * the stop doesn't rebuild the card. */
class StopSlot(session: PairingSession, lifetime: Lifetime) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val store = session.store
        add(
            ReactiveSlot(lifetime, store.stop, keyOf = { it?.stop?.number ?: if (store.isLive) BETWEEN else OVER }) { open, life ->
                when {
                    open != null -> StopCard(session, open, life)
                    store.isLive -> placeholder(life)
                    else -> null
                }
            },
            BorderLayout.CENTER,
        )
    }

    private fun placeholder(lifetime: Lifetime): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        val spinner = AsyncProcessIcon("diffdino-stop-wait")
        Disposer.register(lifetime, spinner)
        add(spinner)
        add(JBLabel(DiffDinoBundle.message("stop.waiting")).apply { foreground = ChatColors.secondaryText })
    }

    private companion object {
        const val BETWEEN = 0
        const val OVER = -1
    }
}

/** The stop the user is on: its number, title, where it is (a click goes back there), why, and where
 * the agent's code for it is. */
class StopCard(private val session: PairingSession, open: OpenStop, lifetime: Lifetime) :
    RoundedPanel(ChatColors.card, ChatColors.border, VerticalLayout(JBUI.scale(6))) {
    init {
        border = JBUI.Borders.empty(10, 12)
        val stop = open.stop
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(DiffDinoBundle.message("stop.number", stop.number)).apply {
                font = JBFont.small().asBold()
                foreground = ChatColors.accent
            }, BorderLayout.WEST)
            add(ActionLink(DiffDinoBundle.message("stop.show.change")) { session.revealStop() }.apply {
                toolTipText = DiffDinoBundle.message("stop.go.to")
            }, BorderLayout.EAST)
        })
        add(WrappingHtmlPane().also { Disposer.register(lifetime, it); it.showPlain(stop.title, bold = true) })
        add(ActionLink("${stop.target.path} › ${detail(open)}") { session.revealStop() }.apply {
            toolTipText = DiffDinoBundle.message("stop.go.to") + "\n" + open.location.absolutePath
        })
        add(WrappingHtmlPane().also { Disposer.register(lifetime, it); it.showMarkdown(stop.reason) })
        add(DraftNote(session.store, lifetime))
    }

    private fun detail(open: OpenStop): String = when (val location = open.location) {
        is StopLocation.OnSymbol -> open.stop.target.symbol
        is StopLocation.Insertion -> DiffDinoBundle.message("stop.after", location.after)
        is StopLocation.NewFile -> DiffDinoBundle.message("stop.new.file")
    }
}
