package dev.pacenote.ui.action

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import dev.pacenote.ui.support.Lifetime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The code selected in the project's selected text editor, as a quote, kept current as the
 * selection and the selected editor change. It stays while focus is elsewhere, the chat included. */
class SelectedCode(private val project: Project, lifetime: Lifetime) {
    private val state = MutableStateFlow<SelectionQuoteResult>(SelectionQuoteResult.NoSelection)

    val current: StateFlow<SelectionQuoteResult> get() = state

    init {
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (e.editor.project === project && e.editor === selectedEditor()) read()
                }
            },
            lifetime,
        )
        project.messageBus.connect(lifetime).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = read()
            },
        )
        read()
    }

    private fun selectedEditor() = FileEditorManager.getInstance(project).selectedTextEditor

    private fun read() {
        state.value = selectedEditor()?.let(SelectionQuote::of) ?: SelectionQuoteResult.NoSelection
    }
}
