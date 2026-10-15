package dev.diffdino.pairing.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.AgentMenuItem
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.ui.dialog.ConfirmAgentChatDialog
import dev.diffdino.pairing.ui.session.AgentStatusText
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * The band over the conversation: the agent, which a click on its name can swap for another, whose
 * turn it is, and the actions on the conversation: ending the pairing session while one runs,
 * starting over with the same agent, and closing the agent.
 */
class AgentHeader(
    private val conversation: AgentConversation,
    private val project: Project,
    lifetime: Lifetime,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val service = PairingProjectService.getInstance(project)
    private val agentName = JBLabel(conversation.harness.name, AllIcons.General.ArrowDown, JBLabel.LEADING)
    private val status = JBLabel()
    private val toolbar = ActionManager.getInstance().createActionToolbar(PLACE, ConversationActions(project).header, true)
    private var sessionWatch: Lifetime? = null

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(ChatColors.border, 0, 0, 1, 0), JBUI.Borders.empty(2, 12, 2, 4))
        agentName.apply {
            font = JBFont.label().asBold()
            horizontalTextPosition = JBLabel.LEADING
            iconTextGap = JBUI.scale(2)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = DiffDinoBundle.message("header.switch.agent")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = showAgentMenu(agentName)
            })
        }
        status.font = JBFont.small()
        add(agentName, BorderLayout.WEST)
        add(status, BorderLayout.CENTER)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()
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
        val shown = AgentStatusText.of(conversation.phase.value, sessionPhase)
        status.text = shown.text
        status.foreground = if (shown.trouble) ChatColors.dangerText else ChatColors.secondaryText
        toolbar.updateActionsAsync()
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

    private companion object {
        const val PLACE = "DiffDinoAgentHeader"
    }
}
