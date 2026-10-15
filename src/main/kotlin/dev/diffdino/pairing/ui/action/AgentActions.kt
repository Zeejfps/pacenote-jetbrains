package dev.diffdino.pairing.ui.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.ui.AgentToolWindowFactory
import dev.diffdino.pairing.ui.dialog.NewPairingSessionDialog
import dev.diffdino.pairing.ui.dialog.SendToAgentDialog
import dev.diffdino.pairing.ui.support.DiffDinoBundle

/** Brings up the project's conversation with its agent, or the empty state to start one. */
class OpenAgentChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PairingProjectService.getInstance(project).chat.reveal()
        ToolWindowManager.getInstance(project).getToolWindow(AgentToolWindowFactory.ID)?.activate(null)
    }
}

/** Asks for a goal and starts a pairing session. */
class StartPairingSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        if (project != null) {
            e.presentation.isEnabled = PairingProjectService.getInstance(project).sessions.liveConversation?.isPairing != true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NewPairingSessionDialog(project).show()
    }
}

/** Sends the selected code to the agent along with a question. */
class SendSelectionToAgentAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val title = DiffDinoBundle.message("send.title")
        when (val result = SelectionQuote.of(editor)) {
            is SelectionQuoteResult.Quoted -> SendToAgentDialog(project, result.quote).show()
            SelectionQuoteResult.NoSelection -> Unit
            SelectionQuoteResult.NotAFile -> Messages.showInfoMessage(project, DiffDinoBundle.message("send.not.a.file"), title)
            is SelectionQuoteResult.TooLong ->
                Messages.showInfoMessage(project, DiffDinoBundle.message("send.too.long", result.lines, CodeQuote.MAX_LINES), title)
        }
    }
}
