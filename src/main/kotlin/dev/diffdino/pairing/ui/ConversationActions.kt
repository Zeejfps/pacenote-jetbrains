package dev.diffdino.pairing.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.pairing.PairingSessions
import dev.diffdino.pairing.ui.dialog.ConfirmAgentChatDialog
import dev.diffdino.pairing.ui.dialog.NewPairingSessionDialog
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.StopIcon
import javax.swing.Icon

/** What an action does when it can't be used on the conversation as it is. */
enum class WhenUnavailable { Disabled, Hidden }

/** The actions on the project's conversation, as the header offers them and as the tool window's
 * options menu does. The ones that lose something ask first. */
class ConversationActions(private val project: Project) {
    private val sessions = PairingProjectService.getInstance(project).sessions

    val header: DefaultActionGroup = DefaultActionGroup().apply {
        add(endSession(WhenUnavailable.Hidden, StopIcon))
        add(restart())
        add(close())
    }

    val options: DefaultActionGroup = DefaultActionGroup().apply {
        add(newSession())
        add(endSession(WhenUnavailable.Disabled, null))
        add(hide())
    }

    private fun newSession() = ConversationAction("header.new.session", null, { !it.isPairing && !it.isGone }) {
        NewPairingSessionDialog(project).show()
    }

    private fun endSession(whenUnavailable: WhenUnavailable, icon: Icon?) =
        ConversationAction("header.end.session", icon, { it.isPairing }, whenUnavailable) { conversation ->
            val confirmed = ConfirmAgentChatDialog.confirm(
                project,
                DiffDinoBundle.message("confirm.end.title"),
                DiffDinoBundle.message("confirm.end.body"),
                DiffDinoBundle.message("stop.end"),
                DiffDinoBundle.message("confirm.keep.pairing"),
            )
            if (confirmed) conversation.session.value?.end()
        }

    private fun restart() = ConversationAction("header.restart", AllIcons.Actions.Restart, { PairingSessions.canRestart(it) }) { conversation ->
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.restart.title", agent),
            DiffDinoBundle.message("confirm.restart.body"),
            DiffDinoBundle.message("header.restart.action"),
        )
        if (confirmed) sessions.restart()
    }

    private fun close() = ConversationAction("header.close", AllIcons.Actions.Close, { true }) { conversation ->
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            DiffDinoBundle.message("confirm.close.title", agent),
            DiffDinoBundle.message("confirm.close.body"),
            DiffDinoBundle.message("header.close.action"),
        )
        if (confirmed) sessions.close()
    }

    private fun hide() = ConversationAction("header.hide", null, { true }) { sessions.hidePanel() }

    private inner class ConversationAction(
        textKey: String,
        icon: Icon?,
        private val available: (AgentConversation) -> Boolean,
        private val whenUnavailable: WhenUnavailable = WhenUnavailable.Disabled,
        private val run: (AgentConversation) -> Unit,
    ) : DumbAwareAction(DiffDinoBundle.message(textKey), null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val usable = sessions.conversation.value?.let(available) == true
            e.presentation.isEnabled = usable
            e.presentation.isVisible = usable || whenUnavailable == WhenUnavailable.Disabled
        }

        override fun actionPerformed(e: AnActionEvent) {
            sessions.conversation.value?.let(run)
        }
    }
}
