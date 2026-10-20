package dev.pacenote.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.pacenote.pairing.AgentConversation
import dev.pacenote.pairing.PairingProjectService
import dev.pacenote.pairing.PairingSessions
import dev.pacenote.ui.dialog.ConfirmAgentChatDialog
import dev.pacenote.ui.dialog.NewPairingSessionDialog
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.StopIcon
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
                PacenoteBundle.message("confirm.end.title"),
                PacenoteBundle.message("confirm.end.body"),
                PacenoteBundle.message("stop.end"),
                PacenoteBundle.message("confirm.keep.pairing"),
            )
            if (confirmed) conversation.session.value?.end()
        }

    private fun restart() = ConversationAction("header.restart", AllIcons.Actions.Restart, { PairingSessions.canRestart(it) }) { conversation ->
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            PacenoteBundle.message("confirm.restart.title", agent),
            PacenoteBundle.message("confirm.restart.body"),
            PacenoteBundle.message("header.restart.action"),
        )
        if (confirmed) sessions.restart()
    }

    private fun close() = ConversationAction("header.close", AllIcons.Actions.Close, { true }) { conversation ->
        val agent = conversation.harness.name
        val confirmed = ConfirmAgentChatDialog.confirm(
            project,
            PacenoteBundle.message("confirm.close.title", agent),
            PacenoteBundle.message("confirm.close.body"),
            PacenoteBundle.message("header.close.action"),
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
    ) : DumbAwareAction(PacenoteBundle.message(textKey), null, icon) {
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
