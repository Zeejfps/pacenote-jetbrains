package dev.diffdino.pairing.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.pairing.PairingStart
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import javax.swing.JComponent

/** Asks for the goal of a pairing session, and the agent to pair with when none is running yet.
 * The session then runs in the project's conversation. */
class NewPairingSessionDialog(private val project: Project) : DialogWrapper(project) {
    private val service = PairingProjectService.getInstance(project)
    private val goal = JBTextArea(5, 44).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = DiffDinoBundle.message("new.session.goal.placeholder")
    }
    private val picker = AgentPicker(service.chat)

    init {
        title = DiffDinoBundle.message("new.session.title")
        setOKButtonText(DiffDinoBundle.message("new.session.start"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val live = service.sessions.liveConversation
        val agent: JComponent = if (live == null) picker
        else JBLabel(DiffDinoBundle.message("new.session.continues.with", live.harness.name)).apply { foreground = ChatColors.secondaryText }
        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel(DiffDinoBundle.message("new.session.description")).apply {
                foreground = ChatColors.secondaryText
                border = JBUI.Borders.emptyBottom(8)
            })
            .addLabeledComponent(DiffDinoBundle.message("new.session.goal"), JBScrollPane(goal), true)
            .addLabeledComponent(DiffDinoBundle.message("new.session.agent"), agent, true)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = goal

    override fun doValidate(): ValidationInfo? =
        if (goal.text.isBlank()) ValidationInfo(DiffDinoBundle.message("new.session.goal.required"), goal) else null

    override fun doOKAction() {
        when (service.sessions.start(goal.text, picker.picked)) {
            is PairingStart.Started -> super.doOKAction()
            is PairingStart.AlreadyRunning -> {
                Messages.showInfoMessage(project, DiffDinoBundle.message("new.session.already.running"), DiffDinoBundle.message("new.session.title"))
                super.doOKAction()
            }
        }
    }
}
