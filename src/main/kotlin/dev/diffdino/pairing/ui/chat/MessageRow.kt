package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import dev.diffdino.pairing.pairing.AgentConversation
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import javax.swing.JComponent

/** The row of the transcript for each kind of message. */
class MessageRow(private val conversation: AgentConversation, private val project: Project) {
    fun create(message: PairingMessage, lifetime: Lifetime): JComponent = when (message) {
        is PairingMessage.SessionStarted -> SessionStartedRow(message, conversation, lifetime)
        is PairingMessage.SessionOver -> SessionOutcomeRow.create(message.outcome, lifetime)
        is PairingMessage.FromUser -> UserBubble(message, conversation.root, project, lifetime)
        is PairingMessage.Narration -> AgentReply(message.text, lifetime)
        is PairingMessage.Notice -> NoticeRow.of(message.text, message.tone, lifetime)
        is PairingMessage.Approval -> ApprovalCard(message, project, lifetime)
        is PairingMessage.AllowedEdit -> ExpandableRow(DiffDinoBundle.message("approval.allowed.edit", message.title)) {
            EditDetails(message.preview, message.edits, project)
        }
    }
}
