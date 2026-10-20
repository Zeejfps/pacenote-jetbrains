package dev.pacenote.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pacenote.pairing.AgentConversation
import dev.pacenote.pairing.AgentStepKind
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.RoundedCard
import dev.pacenote.ui.support.StopIcon
import dev.pacenote.ui.support.TypingDots
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * The foot of the transcript while the agent works: breathing dots, what it is at and for how
 * long — the command it runs, the file it reads, or that it is thinking — a word when it has gone
 * quiet, and Stop. Just the dots while it starts up. Ticks once a second through a turn. The card
 * is as wide as it needs up to the row's width; narrower than that, the title gives way, never Stop.
 */
class ActivityRow(private val conversation: AgentConversation, lifetime: Lifetime) : JPanel(null) {
    private val dots = TypingDots()
    private val icon = JBLabel()
    private val text = JBLabel()
    private val elapsed = JBLabel().apply { foreground = ChatColors.secondaryText }
    private val more = JBLabel().apply { foreground = ChatColors.secondaryText }
    private val quiet = JBLabel().apply { foreground = ChatColors.warningText }
    private val stop = ActionLink(PacenoteBundle.message("activity.stop")) { conversation.stopTurn() }.apply {
        icon = StopIcon
        toolTipText = PacenoteBundle.message("activity.stop.tooltip")
    }
    private val ticker = Timer(TICK_MILLIS) { refresh() }
    private val card = RoundedCard(ChatColors.raised, vertical = 8, horizontal = 12)

    init {
        isOpaque = false
        Disposer.register(lifetime, dots)
        Disposer.register(lifetime) { ticker.stop() }
        fun group(vararg parts: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
            isOpaque = false
            parts.forEach(::add)
        }
        text.border = JBUI.Borders.emptyRight(GAP)
        card.add(group(dots, icon), BorderLayout.WEST)
        card.add(text, BorderLayout.CENTER)
        card.add(group(elapsed, more, quiet, stop), BorderLayout.EAST)
        add(card)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, comp: Component?) = Unit

            override fun removeLayoutComponent(comp: Component?) = Unit

            override fun preferredLayoutSize(parent: Container): Dimension = card.preferredSize

            override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, card.preferredSize.height)

            override fun layoutContainer(parent: Container) {
                card.setBounds(0, 0, minOf(card.preferredSize.width, parent.width), parent.height)
            }
        }
        lifetime.bind(conversation.activity) { refresh() }
        lifetime.bind(conversation.isWorking) { refresh() }
        lifetime.bind(conversation.isComposing) { refresh() }
    }

    private fun refresh() {
        val activity = conversation.activity.value
        val line = ActivityText.of(activity, conversation.isWorking.value, System.currentTimeMillis())
        val startingUp = activity == null && conversation.isComposing.value
        isVisible = line != null || startingUp
        dots.running = isVisible
        // Ticks through the whole turn, hidden or not: the line comes back once prose stops streaming.
        if (activity != null) ticker.start() else ticker.stop()

        icon.icon = line?.kind?.let(::iconOf)
        icon.isVisible = icon.icon != null
        text.text = line?.text.orEmpty()
        text.toolTipText = line?.full
        text.isVisible = line != null
        elapsed.text = line?.elapsed.orEmpty()
        elapsed.isVisible = !line?.elapsed.isNullOrEmpty()
        more.text = line?.more.orEmpty()
        more.isVisible = line?.more != null
        quiet.text = line?.quiet.orEmpty()
        quiet.isVisible = line?.quiet != null
        stop.isVisible = activity != null && !activity.stopping
        revalidate()
        repaint()
    }

    private fun iconOf(kind: AgentStepKind): Icon? = when (kind) {
        AgentStepKind.Run -> AllIcons.Actions.Execute
        AgentStepKind.Read -> AllIcons.FileTypes.Text
        AgentStepKind.Edit -> AllIcons.Actions.Edit
        AgentStepKind.Search -> AllIcons.Actions.Find
        AgentStepKind.Fetch -> AllIcons.General.Web
        AgentStepKind.Think -> null
        AgentStepKind.Other -> AllIcons.General.Settings
    }

    private companion object {
        const val TICK_MILLIS = 1000
        const val GAP = 6
    }
}
