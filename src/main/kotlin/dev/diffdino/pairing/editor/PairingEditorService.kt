package dev.diffdino.pairing.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import dev.diffdino.pairing.model.CodeSpotlights
import dev.diffdino.pairing.model.PairingPresentation

/** The editor side of the pairing loop for one project: what the loop draws drafts with, and what the agent points at code with. */
class PairingEditorService(private val project: Project) {
    val presentation: PairingPresentation get() = project.service<PairingPresentation>()

    val spotlights: CodeSpotlights get() = project.service<CodeSpotlights>()

    internal fun draftIn(editor: Editor): ShownDraft? = (presentation as? EditorPairingPresentation)?.draftIn(editor)

    companion object {
        fun getInstance(project: Project): PairingEditorService = project.service()

        internal fun draftShownIn(project: Project, editor: Editor): ShownDraft? =
            (project.serviceIfCreated<PairingPresentation>() as? EditorPairingPresentation)?.draftIn(editor)
    }
}
