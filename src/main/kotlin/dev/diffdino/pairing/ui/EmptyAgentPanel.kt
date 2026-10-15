package dev.diffdino.pairing.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.ui.dialog.AgentPicker
import dev.diffdino.pairing.ui.dialog.NewPairingSessionDialog
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
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
            add(WrappingHtmlPane().apply { showPlain(DiffDinoBundle.message("empty.description"), ChatColors.secondaryText) })
            add(JBLabel(DiffDinoBundle.message("new.session.agent")).apply { foreground = ChatColors.secondaryText })
            add(picker)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JButton(DiffDinoBundle.message("empty.chat")).apply { addActionListener { service.chat.open(picker.picked) } })
                add(JButton(DiffDinoBundle.message("empty.start.session")).apply {
                    addActionListener { NewPairingSessionDialog(project).show() }
                })
            })
        }
        add(column, BorderLayout.NORTH)
    }
}
