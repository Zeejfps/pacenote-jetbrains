package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ScrollableColumn
import dev.diffdino.pairing.ui.support.StickToBottom
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The conversation with the agent: a row for each message, growing as they come, with a typing
 * indicator while the agent works. It follows the end of the conversation unless the user has
 * scrolled up from it, and goes back to it when they say something.
 */
class TranscriptView(
    private val conversation: AgentConversation,
    project: Project,
    private val lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    private val rows = MessageRow(conversation, project)
    private val column = ScrollableColumn(GAP)
    private val scroll = ScrollableColumn.scrolling(column)
    private val follow = StickToBottom(scroll)
    private val shown = ArrayList<Pair<PairingMessage, Lifetime>>()
    private val activity = ActivityRow(conversation, lifetime)

    init {
        isOpaque = false
        column.border = JBUI.Borders.empty(12)
        column.add(activity)
        follow.follow(column)
        add(scroll, BorderLayout.CENTER)
        lifetime.bind(conversation.transcript.messages) { sync(it) }
    }

    /** Takes the view to the end of the conversation and keeps it there. */
    fun pinToEnd() = follow.pin()

    private fun sync(messages: List<PairingMessage>) {
        if (!extendsShown(messages)) clear()
        val added = messages.drop(shown.size)
        for (message in added) append(message)
        if (added.lastOrNull() is PairingMessage.FromUser || added.lastOrNull() is PairingMessage.SessionOver) follow.pin()
        column.revalidate()
        column.repaint()
    }

    private fun extendsShown(messages: List<PairingMessage>): Boolean =
        messages.size >= shown.size && shown.indices.all { shown[it].first === messages[it] }

    private fun append(message: PairingMessage) {
        val rowLifetime = lifetime.child()
        val row: JComponent = rows.create(message, rowLifetime)
        column.add(row, column.componentCount - 1)
        shown.add(message to rowLifetime)
    }

    private fun clear() {
        for ((_, rowLifetime) in shown) Disposer.dispose(rowLifetime)
        shown.clear()
        while (column.componentCount > 1) column.remove(0)
    }

    private companion object {
        const val GAP = 16
    }
}
