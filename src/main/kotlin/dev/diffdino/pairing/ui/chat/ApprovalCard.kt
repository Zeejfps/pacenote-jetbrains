package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.pairing.ToolApprovalOutcome
import dev.diffdino.pairing.ui.support.ChatButton
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ReactiveSlot
import dev.diffdino.pairing.ui.support.RoundedCard
import dev.diffdino.pairing.ui.support.WrapPanel
import javax.swing.JComponent

/**
 * A tool call the write guard left to the user, in a card: what it would change, and the answers
 * until it is answered, and then how it went.
 */
class ApprovalCard(
    private val approval: PairingMessage.Approval,
    project: Project,
    lifetime: Lifetime,
) : RoundedCard(ChatColors.raised, layout = VerticalLayout(JBUI.scale(8))) {
    init {
        add(JBLabel(DiffDinoBundle.message("approval.title", approval.pending.toolName)).apply { font = JBFont.label().asBold() })
        add(
            if (approval.preview.isNotEmpty()) EditDetails(approval.preview, approval.edits, project)
            else EditDetails.commandLines(approval.pending.arguments),
        )
        add(ReactiveSlot(lifetime, approval.pending.outcome) { outcome, _ -> answers(outcome) })
    }

    private fun answers(outcome: ToolApprovalOutcome): JComponent = when (outcome) {
        ToolApprovalOutcome.Pending -> buttons()
        ToolApprovalOutcome.Approved, ToolApprovalOutcome.Denied, ToolApprovalOutcome.Cancelled ->
            JBLabel(outcomeText(outcome)).apply {
                font = JBFont.small()
                foreground = ChatColors.secondaryText
            }
    }

    private fun buttons(): JComponent {
        val pending = approval.pending
        return WrapPanel().apply {
            add(ChatButton(DiffDinoBundle.message("approval.allow"), primary = true).apply { addActionListener { pending.approve() } })
            approval.allowance?.let { allowance ->
                add(ChatButton(DiffDinoBundle.message("approval.allow.file")).apply { addActionListener { allowance.allow() } })
            }
            add(ChatButton(DiffDinoBundle.message("approval.deny")).apply { addActionListener { pending.deny() } })
        }
    }

    private fun outcomeText(outcome: ToolApprovalOutcome): String = when (outcome) {
        ToolApprovalOutcome.Approved ->
            if (approval.allowance?.granted?.value == true) DiffDinoBundle.message("approval.approved.file")
            else DiffDinoBundle.message("approval.approved")
        ToolApprovalOutcome.Denied -> DiffDinoBundle.message("approval.denied")
        ToolApprovalOutcome.Cancelled -> DiffDinoBundle.message("approval.withdrawn")
        ToolApprovalOutcome.Pending -> ""
    }
}
