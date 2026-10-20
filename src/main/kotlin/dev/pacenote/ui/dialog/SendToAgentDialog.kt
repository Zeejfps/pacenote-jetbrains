package dev.pacenote.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.pacenote.model.CodeQuote
import dev.pacenote.pairing.AgentChatAsk
import dev.pacenote.pairing.AgentPrompt
import dev.pacenote.pairing.PairingProjectService
import dev.pacenote.pairing.lines
import dev.pacenote.settings.AgentSettings
import dev.pacenote.ui.chat.EditPreviewPanel
import dev.pacenote.ui.chat.EditPreviewRows
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import java.nio.file.Path
import javax.swing.JComponent

/** Shows the code the user selected, and asks what to say about it to the project's agent: the one
 * already talking, or the one picked here when none is. */
class SendToAgentDialog(private val project: Project, private val quote: CodeQuote.InFile) : DialogWrapper(project) {
    private val service = PairingProjectService.getInstance(project)
    private val question = JBTextArea(4, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = PacenoteBundle.message("send.question.placeholder")
    }
    private val picker = AgentPicker(service.chat)

    init {
        title = PacenoteBundle.message("send.title")
        setOKButtonText(PacenoteBundle.message("chat.send"))
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
            .addLabeledComponent(PacenoteBundle.message("send.question"), JBScrollPane(question), true)
        if (live == null) builder.addLabeledComponent(PacenoteBundle.message("new.session.agent"), picker, true)
        return builder.panel
    }

    override fun getPreferredFocusedComponent(): JComponent = question

    override fun doValidate(): ValidationInfo? =
        if (question.text.isBlank()) ValidationInfo(PacenoteBundle.message("send.question.required"), question) else null

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
