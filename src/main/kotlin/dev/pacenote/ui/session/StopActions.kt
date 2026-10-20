package dev.pacenote.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import dev.pacenote.model.DraftState
import dev.pacenote.pairing.PairingSession
import dev.pacenote.pairing.StopActivity
import dev.pacenote.ui.support.ChatButton
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.WrapPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/** The stop's controls: Accept & next, Accept, Next and Skip, with the one to press next filled in. They
 * wait while the card is busy checking the user's changes or putting the code in. */
class StopActions(private val session: PairingSession, lifetime: Lifetime) : JPanel(BorderLayout()) {
    private val store = session.store
    private val acceptAndNext = ChatButton(PacenoteBundle.message("stop.accept.next")).apply {
        addActionListener { session.acceptAndNext() }
    }
    private val accept = ChatButton(PacenoteBundle.message("stop.accept")).apply {
        addActionListener { session.accept() }
    }
    private val next = ChatButton(PacenoteBundle.message("stop.next")).apply {
        addActionListener { session.next() }
    }
    private val skip = ChatButton(PacenoteBundle.message("stop.skip")).apply { addActionListener { session.skip() } }
    private val busyText = JBLabel().apply { foreground = ChatColors.secondaryText }
    private val busyIcon = AsyncProcessIcon("pacenote-stop-busy")
    private val busy = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(busyIcon)
        add(busyText)
    }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(ChatColors.border, 1, 0, 0, 0), JBUI.Borders.empty(8, 12))
        Disposer.register(lifetime, busyIcon)
        val buttons = WrapPanel().apply {
            add(acceptAndNext)
            add(accept)
            add(next)
            add(skip)
        }
        add(buttons, BorderLayout.CENTER)
        add(busy, BorderLayout.SOUTH)

        lifetime.bind(store.stop) { refresh() }
        lifetime.bind(store.activity) { refresh() }
    }

    private fun refresh() {
        val open = store.stop.value
        val activity = store.activity.value
        val idle = open != null && activity == StopActivity.Idle
        val offered = open?.draftState is DraftState.Offered
        acceptAndNext.isVisible = offered
        accept.isVisible = offered
        acceptAndNext.primary = offered
        next.primary = !offered
        for (button in listOf(acceptAndNext, accept, next, skip)) button.isEnabled = idle
        busy.isVisible = activity != StopActivity.Idle
        busyText.text = when (activity) {
            StopActivity.Idle -> ""
            StopActivity.Checking -> PacenoteBundle.message("stop.checking")
            StopActivity.Accepting -> PacenoteBundle.message("stop.accepting")
        }
        revalidate()
    }
}
