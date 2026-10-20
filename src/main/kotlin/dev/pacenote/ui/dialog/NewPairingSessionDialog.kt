package dev.pacenote.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.pacenote.pairing.PairingProjectService
import dev.pacenote.pairing.PairingStart
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import javax.swing.JComponent

/** Asks for the goal of a pairing session, and the agent to pair with when none is running yet.
 * The session then runs in the project's conversation. */
class NewPairingSessionDialog(private val project: Project) : DialogWrapper(project) {
    private val service = PairingProjectService.getInstance(project)
    private val goal = JBTextArea(5, 44).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = PacenoteBundle.message("new.session.goal.placeholder")
    }
    private val picker = AgentPicker(service.chat)

    init {
        title = PacenoteBundle.message("new.session.title")
        setOKButtonText(PacenoteBundle.message("new.session.start"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val live = service.sessions.liveConversation
        val agent: JComponent = if (live == null) picker
        else JBLabel(PacenoteBundle.message("new.session.continues.with", live.harness.name)).apply { foreground = ChatColors.secondaryText }
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel(PacenoteBundle.message("new.session.description")).apply {
                foreground = ChatColors.secondaryText
                border = JBUI.Borders.emptyBottom(8)
            })
            .addLabeledComponent(PacenoteBundle.message("new.session.goal"), JBScrollPane(goal), true)
            .addLabeledComponent(PacenoteBundle.message("new.session.agent"), agent, true)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = goal

    override fun doValidate(): ValidationInfo? =
        if (goal.text.isBlank()) ValidationInfo(PacenoteBundle.message("new.session.goal.required"), goal) else null

    override fun doOKAction() {
        when (service.sessions.start(goal.text, picker.picked)) {
            is PairingStart.Started -> super.doOKAction()
            is PairingStart.AlreadyRunning -> {
                Messages.showInfoMessage(project, PacenoteBundle.message("new.session.already.running"), PacenoteBundle.message("new.session.title"))
                super.doOKAction()
            }
        }
    }
}
