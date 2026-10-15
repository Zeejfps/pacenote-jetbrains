package dev.diffdino.pairing.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.pairing.AgentChatAsk
import dev.diffdino.pairing.pairing.AgentPrompt
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.pairing.lines
import dev.diffdino.pairing.settings.AgentSettings
import dev.diffdino.pairing.ui.chat.EditPreviewPanel
import dev.diffdino.pairing.ui.chat.EditPreviewRows
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import java.nio.file.Path
import javax.swing.JComponent

/** Shows the code the user selected, and asks what to say about it to the project's agent: the one
 * already talking, or the one picked here when none is. */
class SendToAgentDialog(private val project: Project, private val quote: CodeQuote.InFile) : DialogWrapper(project) {
    private val service = PairingProjectService.getInstance(project)
    private val question = JBTextArea(4, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = DiffDinoBundle.message("send.question.placeholder")
    }
    private val picker = AgentPicker(service.chat)

    init {
        title = DiffDinoBundle.message("send.title")
        setOKButtonText(DiffDinoBundle.message("chat.send"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val live = service.sessions.liveConversation
        val root = project.basePath?.let { Path.of(it) } ?: Path.of(quote.absolutePath).parent
        val shown = AgentPrompt.relativeTo(root, quote.absolutePath)
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("$shown, ${quote.lines}").apply {
                foreground = ChatColors.secondaryText
                border = JBUI.Borders.emptyBottom(4)
            })
            .addComponent(EditPreviewPanel.framed(EditPreviewRows.plain(quote.text, QUOTE_LINES), marked = false))
            .addLabeledComponent(DiffDinoBundle.message("send.question"), JBScrollPane(question), true)
        if (live == null) builder.addLabeledComponent(DiffDinoBundle.message("new.session.agent"), picker, true)
        return builder.panel
    }

    override fun getPreferredFocusedComponent(): JComponent = question

    override fun doValidate(): ValidationInfo? =
        if (question.text.isBlank()) ValidationInfo(DiffDinoBundle.message("send.question.required"), question) else null

    override fun doOKAction() {
        val text = question.text
        if (service.sessions.liveConversation != null) {
            when (service.chat.ask(text, quote)) {
                is AgentChatAsk.Asked -> Unit
                AgentChatAsk.NeedsAgent -> askWithPicked(text)
            }
        } else {
            askWithPicked(text)
        }
        super.doOKAction()
    }

    private fun askWithPicked(text: String) {
        val preset = picker.picked
        AgentSettings.getInstance().rememberChatAgent(preset.id)
        service.sessions.ask(text, quote, preset)
    }

    private companion object {
        const val QUOTE_LINES = 12
    }
}
