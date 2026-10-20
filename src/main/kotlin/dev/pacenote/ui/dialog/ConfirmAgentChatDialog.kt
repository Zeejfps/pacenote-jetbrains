package dev.pacenote.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.pacenote.ui.support.PacenoteBundle
import javax.swing.JComponent

/** Asked before something in the agent chat that can't be taken back: closing or restarting it,
 * ending the pairing session, or replacing the conversation with one with another agent. */
class ConfirmAgentChatDialog(
    project: Project?,
    title: String,
    private val body: String,
    private val actionLabel: String,
    private val keepLabel: String = PacenoteBundle.message("confirm.keep.talking"),
) : DialogWrapper(project) {
    init {
        this.title = title
        setOKButtonText(actionLabel)
        setCancelButtonText(keepLabel)
        init()
    }

    override fun createCenterPanel(): JComponent = JBLabel("<html><body style='width: ${JBUI.scale(WIDTH)}px'>$body</body></html>")

    override fun getPreferredFocusedComponent(): JComponent? = null

    companion object {
        private const val WIDTH = 320

        /** True when the user went ahead. */
        fun confirm(project: Project?, title: String, body: String, actionLabel: String, keepLabel: String? = null): Boolean =
            (if (keepLabel == null) ConfirmAgentChatDialog(project, title, body, actionLabel)
            else ConfirmAgentChatDialog(project, title, body, actionLabel, keepLabel)).showAndGet()
    }
}
