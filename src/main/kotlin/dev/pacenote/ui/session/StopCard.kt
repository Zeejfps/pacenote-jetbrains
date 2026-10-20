package dev.pacenote.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pacenote.model.StopLocation
import dev.pacenote.pairing.OpenStop
import dev.pacenote.pairing.PairingSession
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.ReactiveSlot
import dev.pacenote.ui.support.RoundedCard
import dev.pacenote.ui.support.WrappingHtmlPane
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
        val spinner = AsyncProcessIcon("pacenote-stop-wait")
        Disposer.register(lifetime, spinner)
        add(spinner)
        add(JBLabel(PacenoteBundle.message("stop.waiting")).apply { foreground = ChatColors.secondaryText })
    }

    private companion object {
        const val BETWEEN = 0
        const val OVER = -1
    }
}

/** The stop the user is on: its number, title, where it is (a click goes back there), why, and where
 * the agent's code for it is. */
class StopCard(private val session: PairingSession, open: OpenStop, lifetime: Lifetime) :
    RoundedCard(ChatColors.raised, layout = VerticalLayout(JBUI.scale(8))) {
    init {
        val stop = open.stop
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(PacenoteBundle.message("stop.number", stop.number)).apply {
                font = JBFont.small().asBold()
                foreground = ChatColors.accent
            }, BorderLayout.WEST)
            add(ActionLink(PacenoteBundle.message("stop.show.change")) { session.revealStop() }.apply {
                toolTipText = PacenoteBundle.message("stop.go.to")
            }, BorderLayout.EAST)
        })
        add(WrappingHtmlPane().also { Disposer.register(lifetime, it); it.showPlain(stop.title, bold = true) })
        add(ActionLink("${stop.target.path} › ${detail(open)}") { session.revealStop() }.apply {
            toolTipText = PacenoteBundle.message("stop.go.to") + "\n" + open.location.absolutePath
        })
        add(WrappingHtmlPane().also { Disposer.register(lifetime, it); it.showMarkdown(stop.reason) })
        add(DraftNote(session.store, lifetime))
    }

    private fun detail(open: OpenStop): String = when (val location = open.location) {
        is StopLocation.OnSymbol -> open.stop.target.symbol
        is StopLocation.Insertion -> PacenoteBundle.message("stop.after", location.after)
        is StopLocation.NewFile -> PacenoteBundle.message("stop.new.file")
    }
}
