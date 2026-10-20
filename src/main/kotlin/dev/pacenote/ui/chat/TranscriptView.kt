package dev.pacenote.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.pacenote.pairing.AgentConversation
import dev.pacenote.pairing.PairingMessage
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.ScrollableColumn
import dev.pacenote.ui.support.StickToBottom
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The conversation with the agent: a row for each message, growing as they come, and under the
 * last of them what the agent is doing while it works. It follows the end of the conversation unless the user has
 * scrolled up from it, and goes back to it when they say something.
 */
class TranscriptView(
    private val conversation: AgentConversation,
    project: Project,
    private val lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    private val rows = MessageRow(conversation, project)
    private val column = ScrollableColumn(GAP)

    // The messages in a column of their own, so the activity row stays under the last of them: the
    // layout places rows in the order they were added, whatever index they are added at.
    private val messages = JPanel(VerticalLayout(JBUI.scale(GAP))).apply { isOpaque = false }
    private val scroll = ScrollableColumn.scrolling(column)
    private val follow = StickToBottom(scroll)
    private val shown = ArrayList<Pair<PairingMessage, Lifetime>>()
    private val activity = ActivityRow(conversation, lifetime)

    init {
        isOpaque = false
        column.border = JBUI.Borders.empty(12)
        column.add(messages)
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
        messages.add(row)
        shown.add(message to rowLifetime)
    }

    private fun clear() {
        for ((_, rowLifetime) in shown) Disposer.dispose(rowLifetime)
        shown.clear()
        messages.removeAll()
    }

    private companion object {
        const val GAP = 16
    }
}
