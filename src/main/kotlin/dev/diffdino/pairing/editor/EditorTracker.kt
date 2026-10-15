package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/**
 * Follows the editors a project has open on one document, those open now and those opened later,
 * and hands each to [attach] with a disposable that ends when the editor is released or the tracker is.
 */
internal class EditorTracker(
    private val project: Project,
    private val document: Document,
    parent: Disposable,
    private val attach: (Editor, Disposable) -> Unit,
) : Disposable {
    private val attached = LinkedHashMap<Editor, Disposable>()

    init {
        Disposer.register(parent, this)
        EditorFactory.getInstance().getEditors(document, project).forEach(::add)
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = add(event.editor)

                override fun editorReleased(event: EditorFactoryEvent) = remove(event.editor)
            },
            this,
        )
    }

    val editors: Set<Editor> get() = attached.keys

    private fun add(editor: Editor) {
        val tracked = editor.document === document && editor.project === project &&
            editor.editorKind == EditorKind.MAIN_EDITOR && !editor.isDisposed
        if (!tracked || editor in attached) return
        val scope = Disposer.newDisposable(this, "pairing editor")
        attached[editor] = scope
        attach(editor, scope)
    }

    private fun remove(editor: Editor) {
        attached.remove(editor)?.let(Disposer::dispose)
    }

    override fun dispose() {
        attached.clear()
    }
}
