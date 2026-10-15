package dev.diffdino.pairing.ui.chat

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Where the user talks to the agent: Enter sends, Shift+Enter breaks the line. It stays usable
 * while the agent works, since what is said then is queued behind the turn in flight, and goes dead
 * once the agent is gone.
 */
class ChatFooter(private val conversation: AgentConversation, lifetime: Lifetime, private val onSent: () -> Unit) : JPanel(BorderLayout()) {
    private val input = JBTextArea(1, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
        emptyText.text = DiffDinoBundle.message("chat.placeholder")
    }
    private val send = JButton(DiffDinoBundle.message("chat.send"))
    private val scroll = object : JBScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val content = input.preferredSize.height + insets.top + insets.bottom
            return Dimension(size.width, content.coerceIn(JBUI.scale(MIN_HEIGHT), JBUI.scale(MAX_HEIGHT)))
        }
    }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(ChatColors.border, 1, 0, 0, 0), JBUI.Borders.empty(8))
        scroll.border = JBUI.Borders.customLine(ChatColors.border, 1)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(send, BorderLayout.SOUTH)
        }, BorderLayout.EAST)

        bindKeys(input)
        send.addActionListener { submit() }
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        lifetime.bind(conversation.takesChat) { takes ->
            input.isEnabled = takes
            input.emptyText.text = DiffDinoBundle.message(if (takes) "chat.placeholder" else "chat.gone")
            changed()
        }
    }

    private fun changed() {
        send.isEnabled = conversation.takesChat.value && input.text.isNotBlank()
        scroll.revalidate()
    }

    private fun bindKeys(area: JComponent) {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        area.inputMap.put(shiftEnter, "insert-break")
        area.inputMap.put(enter, SEND)
        area.actionMap.put(SEND, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = submit()
        })
    }

    private fun submit() {
        val text = input.text
        if (text.isBlank() || !conversation.takesChat.value) return
        conversation.say(text)
        input.text = ""
        onSent()
    }

    private companion object {
        const val SEND = "diffdino-send"
        const val MIN_HEIGHT = 34
        const val MAX_HEIGHT = 140
    }
}
