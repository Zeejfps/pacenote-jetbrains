package dev.pacenote.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.pacenote.pairing.PairingProjectService
import dev.pacenote.ui.dialog.AgentPicker
import dev.pacenote.ui.dialog.NewPairingSessionDialog
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/** What the tool window shows with no conversation: pick an agent, then chat with it or start a
 * pairing session. */
class EmptyAgentPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val service = PairingProjectService.getInstance(project)
    private val picker = AgentPicker(service.chat)

    init {
        val column = JPanel(VerticalLayout(JBUI.scale(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(20, 16)
            add(WrappingHtmlPane().apply { showPlain(PacenoteBundle.message("empty.description"), ChatColors.secondaryText) })
            add(JBLabel(PacenoteBundle.message("new.session.agent")).apply { foreground = ChatColors.secondaryText })
            add(picker)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JButton(PacenoteBundle.message("empty.chat")).apply { addActionListener { service.chat.open(picker.picked) } })
                add(JButton(PacenoteBundle.message("empty.start.session")).apply {
                    addActionListener { NewPairingSessionDialog(project).show() }
                })
            })
        }
        add(column, BorderLayout.NORTH)
    }
}
