package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import dev.diffdino.pairing.model.CodeSpotlights
import dev.diffdino.pairing.model.PairingPresentation

/** The editor side of the pairing loop for one project: what the loop draws drafts with, and what the agent points at code with. */
class PairingEditorService(project: Project) : Disposable {
    private val editorPresentation = EditorPairingPresentation(project, this)
    private val editorSpotlights = EditorCodeSpotlights(project, this)

    val presentation: PairingPresentation get() = editorPresentation

    val spotlights: CodeSpotlights get() = editorSpotlights

    internal fun draftIn(editor: Editor): ShownDraft? = editorPresentation.draftIn(editor)

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): PairingEditorService = project.service()

        internal fun ifCreated(project: Project): PairingEditorService? = project.serviceIfCreated()
    }
}
