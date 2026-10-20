package dev.pacenote.ui.chat

import com.intellij.openapi.project.Project
import dev.pacenote.pairing.AgentConversation
import dev.pacenote.pairing.PairingMessage
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.TrailingGutter
import javax.swing.JComponent

/** The row of the transcript for each kind of message. What the agent and the session say leaves a
 * gutter on the right, where the user's side of the conversation sits. */
class MessageRow(private val conversation: AgentConversation, private val project: Project) {
    fun create(message: PairingMessage, lifetime: Lifetime): JComponent = when (message) {
        is PairingMessage.SessionStarted -> SessionStartedRow(message, conversation, lifetime)
        is PairingMessage.SessionOver -> TrailingGutter(SessionOutcomeRow.create(message.outcome, lifetime))
        is PairingMessage.FromUser -> UserBubble(message, conversation.root, project, lifetime)
        is PairingMessage.Narration -> TrailingGutter(AgentReply(message.text, lifetime))
        is PairingMessage.Notice -> TrailingGutter(NoticeRow.of(message.text, message.tone, lifetime))
        is PairingMessage.Approval -> TrailingGutter(ApprovalCard(message, project, lifetime))
        is PairingMessage.AllowedEdit -> TrailingGutter(
            ExpandableRow(PacenoteBundle.message("approval.allowed.edit", message.title)) {
                EditDetails(message.preview, message.edits, project)
            },
        )
    }
}
