package dev.diffdino.pairing.ui.session

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.DraftState
import dev.diffdino.pairing.pairing.PairingSession
import dev.diffdino.pairing.pairing.StopActivity
import dev.diffdino.pairing.ui.dialog.ConfirmAgentChatDialog
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ShortcutHint
import dev.diffdino.pairing.ui.support.WrapPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** The stop's controls: Accept, Accept & next, Next and Skip, and apart from them End, which asks
 * first. They wait while the card is busy checking the user's changes or putting the code in. The
 * keys the editor binds to the same moves are shown on the buttons. */
class StopActions(private val session: PairingSession, private val project: Project, lifetime: Lifetime) : JPanel(BorderLayout()) {
    private val store = session.store
    private val acceptAndNext = JButton(ShortcutHint.label(DiffDinoBundle.message("stop.accept.next"), ACCEPT_AND_NEXT_ACTION)).apply {
        addActionListener { session.acceptAndNext() }
    }
    private val accept = JButton(ShortcutHint.label(DiffDinoBundle.message("stop.accept"), ACCEPT_ACTION)).apply {
        addActionListener { session.accept() }
    }
    private val next = JButton(ShortcutHint.label(DiffDinoBundle.message("stop.next"), NEXT_ACTION)).apply {
        addActionListener { session.next() }
    }
    private val skip = JButton(DiffDinoBundle.message("stop.skip")).apply { addActionListener { session.skip() } }
    private val end = JButton(DiffDinoBundle.message("stop.end")).apply { addActionListener { confirmEnd() } }
    private val busyText = JBLabel().apply { foreground = ChatColors.secondaryText }
    private val busyIcon = AsyncProcessIcon("diffdino-stop-busy")
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
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(end, BorderLayout.NORTH)
        }, BorderLayout.EAST)
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
        for (button in listOf(acceptAndNext, accept, next, skip)) button.isEnabled = idle
        next.isEnabled = idle
        busy.isVisible = activity != StopActivity.Idle
        busyText.text = when (activity) {
            StopActivity.Idle -> ""
            StopActivity.Checking -> DiffDinoBundle.message("stop.checking")
            StopActivity.Accepting -> DiffDinoBundle.message("stop.accepting")
        }
        revalidate()
    }

    private fun confirmEnd() {
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.end.title"),
            DiffDinoBundle.message("confirm.end.body"),
            DiffDinoBundle.message("stop.end"),
            DiffDinoBundle.message("confirm.keep.pairing"),
        )
        if (confirmed) session.end()
    }

    private companion object {
        const val ACCEPT_AND_NEXT_ACTION = "DiffDino.AcceptDraftAndNext"
        const val ACCEPT_ACTION = "DiffDino.AcceptDraft"
        const val NEXT_ACTION = "DiffDino.NextStop"
    }
}
