package dev.diffdino.pairing.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.ui.chat.ChatFooter
import dev.diffdino.pairing.ui.chat.TranscriptView
import dev.diffdino.pairing.ui.session.SessionPane
import dev.diffdino.pairing.ui.support.Lifetime
import java.awt.BorderLayout
import javax.swing.JPanel

/** The conversation with the agent: its header, the live session's pane above the transcript while
 * one runs, and the footer to talk in. */
class ConversationPanel(conversation: AgentConversation, project: Project, private val lifetime: Lifetime) : JPanel(BorderLayout()) {
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }
    private val chat = JPanel(BorderLayout()).apply { isOpaque = false }
    private var sessionLifetime: Lifetime? = null

    init {
        val transcript = TranscriptView(conversation, project, lifetime)
        chat.add(transcript, BorderLayout.CENTER)
        chat.add(ChatFooter(conversation, project, lifetime, onSent = transcript::pinToEnd), BorderLayout.SOUTH)

        add(AgentHeader(conversation, project, lifetime), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        lifetime.bind(conversation.session) { session ->
            sessionLifetime?.let(Disposer::dispose)
            body.removeAll()
            if (session == null) {
                sessionLifetime = null
                body.add(chat, BorderLayout.CENTER)
            } else {
                val own = lifetime.child()
                sessionLifetime = own
                body.add(OnePixelSplitter(true, SPLIT_KEY, SPLIT_DEFAULT).apply {
                    firstComponent = SessionPane(session, own)
                    secondComponent = chat
                }, BorderLayout.CENTER)
            }
            body.revalidate()
            body.repaint()
        }
    }

    private companion object {
        const val SPLIT_KEY = "DiffDino.Agent.SessionSplit"
        const val SPLIT_DEFAULT = 0.5f
    }
}
