package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.ui.action.SelectedCode
import dev.diffdino.pairing.ui.support.CardCorner
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedCard
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Where the user talks to the agent: Enter sends, Shift+Enter breaks the line. It stays usable
 * while the agent works, since what is said then is queued behind the turn in flight, and goes dead
 * once the agent is gone. Code selected in the editor goes along with what is sent, named above it.
 */
class ChatFooter(
    private val conversation: AgentConversation,
    project: Project,
    lifetime: Lifetime,
    private val onSent: () -> Unit,
) : JPanel(BorderLayout()) {
    private val input = JBTextArea(1, 20).apply {
        font = JBFont.label()
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(6, 2)
        emptyText.text = DiffDinoBundle.message("chat.placeholder")
    }
    private val send = SendButton { submit() }
    private val scroll = object : JBScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val content = input.preferredSize.height + insets.top + insets.bottom
            return Dimension(size.width, content.coerceIn(input.getFontMetrics(input.font).height + JBUI.scale(MIN_PADDING), JBUI.scale(MAX_HEIGHT)))
        }
    }
    private val selection = SelectionChip(project, conversation.root, SelectedCode(project, lifetime), lifetime)
    private val composer = RoundedCard(ChatColors.inputFill, ChatColors.border, CardCorner.Fixed(RADIUS), vertical = 2, horizontal = 8)

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(ChatColors.border, 1, 0, 0, 0), JBUI.Borders.empty(12))
        scroll.border = JBUI.Borders.empty()
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        composer.add(selection, BorderLayout.NORTH)
        composer.add(scroll, BorderLayout.CENTER)
        add(composer, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
            add(send, BorderLayout.SOUTH)
        }, BorderLayout.EAST)

        bindKeys(input)
        input.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                composer.outline = ChatColors.focusRing
            }

            override fun focusLost(e: FocusEvent) {
                composer.outline = ChatColors.border
            }
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        selection.onChanged = ::changed
        lifetime.bind(conversation.takesChat) { takes ->
            input.isEnabled = takes
            changed()
        }
    }

    private fun changed() {
        val takes = conversation.takesChat.value
        input.emptyText.text = DiffDinoBundle.message(
            when {
                !takes -> "chat.gone"
                selection.attached != null -> "chat.placeholder.selection"
                else -> "chat.placeholder"
            },
        )
        input.repaint()
        send.isEnabled = takes && input.text.isNotBlank()
        scroll.revalidate()
        composer.revalidate()
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
        conversation.say(text, selection.attached)
        selection.dismiss()
        input.text = ""
        onSent()
    }

    private companion object {
        const val SEND = "diffdino-send"
        const val RADIUS = 10
        const val MIN_PADDING = 12
        const val MAX_HEIGHT = 120
    }
}
