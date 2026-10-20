package dev.pacenote.editor

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * The keyboard side of a draft: its actions are there only while the focused editor shows a draft,
 * and hidden and disabled otherwise so the IDE's own binding for the same keys is the one that runs.
 */
internal object DraftKeys {
    fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = draftShownIn(e) != null
    }

    fun perform(e: AnActionEvent, command: DraftCommand) {
        draftShownIn(e)?.let { command.run(it.actions) }
    }

    private fun draftShownIn(e: AnActionEvent): ShownDraft? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        if (LookupManager.getActiveLookup(editor) != null) return null
        return PairingEditorService.draftShownIn(project, editor)
    }
}
