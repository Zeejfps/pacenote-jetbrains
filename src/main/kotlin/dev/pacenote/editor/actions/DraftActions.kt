package dev.pacenote.editor.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.pacenote.editor.DraftCommand
import dev.pacenote.editor.DraftKeys

class AcceptDraftAndNextAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) = DraftKeys.update(e)

    override fun actionPerformed(e: AnActionEvent) = DraftKeys.perform(e, DraftCommand.AcceptAndNext)
}

class AcceptDraftAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) = DraftKeys.update(e)

    override fun actionPerformed(e: AnActionEvent) = DraftKeys.perform(e, DraftCommand.Accept)
}

class NextStopAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) = DraftKeys.update(e)

    override fun actionPerformed(e: AnActionEvent) = DraftKeys.perform(e, DraftCommand.Next)
}
