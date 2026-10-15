package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.acp.AcpFileEdit
import dev.diffdino.pairing.pairing.EditPreviewLine
import dev.diffdino.pairing.ui.support.CommandText
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import javax.swing.JComponent
import javax.swing.JPanel

/** What an edit would change as the transcript shows it: the preview, and a way to the full diff. */
class EditDetails(preview: List<EditPreviewLine>, edits: List<AcpFileEdit>, project: Project) : JPanel(VerticalLayout(JBUI.scale(4))) {
    init {
        isOpaque = false
        add(EditPreviewPanel.framed(EditPreviewRows.of(preview)))
        val diffs = FullDiff.diffs(edits)
        if (diffs.isNotEmpty()) add(ActionLink(DiffDinoBundle.message("approval.open.diff")) { FullDiff.show(project, diffs) })
    }

    companion object {
        /** The command a tool call would run, or what it is called with, as lines of code. */
        fun commandLines(arguments: String): JComponent = CommandText(commandText(arguments))

        private fun commandText(arguments: String): String = EditPreviewRows.plain(arguments, COMMAND_LINES).joinToString("\n") { row ->
            when (row) {
                is PreviewRow.Code -> row.text
                is PreviewRow.More -> DiffDinoBundle.message("preview.more.lines", row.lines)
                is PreviewRow.FileHeader -> row.path
            }
        }

        private const val COMMAND_LINES = 12
    }
}
