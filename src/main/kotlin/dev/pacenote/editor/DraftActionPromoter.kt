package dev.pacenote.editor

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import dev.pacenote.editor.actions.AcceptDraftAction
import dev.pacenote.editor.actions.AcceptDraftAndNextAction
import dev.pacenote.editor.actions.NextStopAction

/**
 * Puts the draft's actions ahead of the editor's own for the same keys, but only while the focused
 * editor shows a draft; otherwise the editor's actions are left in the order they were found.
 */
class DraftActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val project = context.getData(CommonDataKeys.PROJECT) ?: return emptyList()
        val editor = context.getData(CommonDataKeys.EDITOR) ?: return emptyList()
        if (PairingEditorService.draftShownIn(project, editor) == null) return emptyList()
        return actions.filter { it is AcceptDraftAction || it is AcceptDraftAndNextAction || it is NextStopAction }
    }
}
