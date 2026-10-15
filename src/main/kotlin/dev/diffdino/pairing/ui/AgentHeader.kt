package dev.diffdino.pairing.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.AgentMenuItem
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.pairing.PairingSessions
import dev.diffdino.pairing.ui.dialog.ConfirmAgentChatDialog
import dev.diffdino.pairing.ui.dialog.NewPairingSessionDialog
import dev.diffdino.pairing.ui.session.AgentStatusText
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * The band over the conversation: the agent, which can be swapped for another, whose turn it is,
 * and the actions on the conversation: a new pairing session, ending it, starting over with the
 * same agent, closing the agent, and hiding the panel.
 */
class AgentHeader(
    private val conversation: AgentConversation,
    private val project: Project,
    lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    private val service = PairingProjectService.getInstance(project)
    private val sessions = service.sessions
    private val status = JBLabel()
    private val toolbar = ActionManager.getInstance().createActionToolbar(PLACE, actions(), true)
    private var sessionWatch: Lifetime? = null

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(ChatColors.border, 0, 0, 1, 0), JBUI.Borders.empty(2, 10))
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(agentLink())
            add(status)
        }
        add(left, BorderLayout.CENTER)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        add(toolbar.component, BorderLayout.EAST)

        lifetime.bind(conversation.phase) { refresh() }
        lifetime.bind(conversation.session) { session ->
            sessionWatch?.let(Disposer::dispose)
            sessionWatch = lifetime.child().also { watch -> session?.let { watch.bind(it.store.phase) { refresh() } } }
            refresh()
        }
    }

    private fun refresh() {
        val sessionPhase = conversation.session.value?.store?.phase?.value
        val shown = AgentStatusText.of(conversation.harness.name, conversation.phase.value, sessionPhase)
        status.text = shown.text
        status.foreground = if (shown.trouble) ChatColors.dangerText else ChatColors.secondaryText
        toolbar.updateActionsAsync()
    }

    private fun agentLink(): ActionLink {
        val link = ActionLink(conversation.harness.name)
        link.addActionListener { showAgentMenu(link) }
        link.setDropDownLinkIcon()
        link.toolTipText = DiffDinoBundle.message("header.switch.agent")
        return link
    }

    private fun showAgentMenu(anchor: Component) {
        val group = DefaultActionGroup()
        for (item in service.chat.agentMenu()) group.add(AgentChoice(item))
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(anchor)
    }

    private inner class AgentChoice(private val item: AgentMenuItem) : ToggleAction(item.label), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = item.checked

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = item.enabled
        }

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state || item.checked) return
            val confirmed = ConfirmAgentChatDialog.confirm(
                project,
                DiffDinoBundle.message("confirm.switch.title", item.label),
                DiffDinoBundle.message("confirm.switch.body"),
                DiffDinoBundle.message("confirm.switch.action"),
            )
            if (confirmed) item.select()
        }
    }

    private fun actions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(HeaderAction("header.new.session", AllIcons.General.Add, enabled = { !conversation.isPairing && !conversation.isGone }) {
            NewPairingSessionDialog(project).show()
        })
        add(HeaderAction("header.end.session", AllIcons.Actions.Suspend, enabled = { conversation.isPairing }) { confirmEndSession() })
        add(HeaderAction("header.restart", AllIcons.Actions.Restart, enabled = { PairingSessions.canRestart(conversation) }) { confirmRestart() })
        add(HeaderAction("header.close", AllIcons.Actions.Cancel, enabled = { true }) { confirmClose() })
        add(HeaderAction("header.hide", AllIcons.General.HideToolWindow, enabled = { true }) { sessions.hidePanel() })
    }

    private fun confirmEndSession() {
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.end.title"),
            DiffDinoBundle.message("confirm.end.body"),
            DiffDinoBundle.message("stop.end"),
            DiffDinoBundle.message("confirm.keep.pairing"),
        )
        if (confirmed) conversation.session.value?.end()
    }

    private fun confirmRestart() {
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.restart.title", agent),
            DiffDinoBundle.message("confirm.restart.body"),
            DiffDinoBundle.message("header.restart.action"),
        )
        if (confirmed) sessions.restart()
    }

    private fun confirmClose() {
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.close.title", agent),
            DiffDinoBundle.message("confirm.close.body"),
            DiffDinoBundle.message("header.close.action"),
        )
        if (confirmed) sessions.close()
    }

    private class HeaderAction(
        textKey: String,
        icon: Icon,
        private val enabled: () -> Boolean,
        private val run: () -> Unit,
    ) : DumbAwareAction(DiffDinoBundle.message(textKey), null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private companion object {
        const val PLACE = "DiffDinoAgentHeader"
    }
}
