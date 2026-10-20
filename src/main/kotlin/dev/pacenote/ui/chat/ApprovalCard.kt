package dev.pacenote.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pacenote.pairing.AllowanceScope
import dev.pacenote.pairing.PairingMessage
import dev.pacenote.pairing.ToolApprovalOutcome
import dev.pacenote.ui.support.ChatButton
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.ReactiveSlot
import dev.pacenote.ui.support.RoundedCard
import dev.pacenote.ui.support.WrapPanel
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
        add(JBLabel(PacenoteBundle.message("approval.title", approval.pending.toolName)).apply { font = JBFont.label().asBold() })
        when {
            approval.preview.isNotEmpty() -> add(EditDetails(approval.preview, approval.edits, project))
            approval.pending.arguments.isNotBlank() -> add(EditDetails.commandLines(approval.pending.arguments))
        }
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
            add(ChatButton(PacenoteBundle.message("approval.allow"), primary = true).apply { addActionListener { pending.approve() } })
            approval.allowance?.let { allowance ->
                val label = when (val scope = allowance.scope) {
                    AllowanceScope.SameFiles -> PacenoteBundle.message("approval.allow.file")
                    is AllowanceScope.McpServer -> PacenoteBundle.message("approval.allow.server", scope.server)
                }
                add(ChatButton(label).apply { addActionListener { allowance.allow() } })
            }
            add(ChatButton(PacenoteBundle.message("approval.deny")).apply { addActionListener { pending.deny() } })
        }
    }

    private fun outcomeText(outcome: ToolApprovalOutcome): String = when (outcome) {
        ToolApprovalOutcome.Approved -> {
            val allowance = approval.allowance?.takeIf { it.granted.value }
            when (val scope = allowance?.scope) {
                null -> PacenoteBundle.message("approval.approved")
                AllowanceScope.SameFiles -> PacenoteBundle.message("approval.approved.file")
                is AllowanceScope.McpServer -> PacenoteBundle.message("approval.approved.server", scope.server)
            }
        }
        ToolApprovalOutcome.Denied -> PacenoteBundle.message("approval.denied")
        ToolApprovalOutcome.Cancelled -> PacenoteBundle.message("approval.withdrawn")
        ToolApprovalOutcome.Pending -> ""
    }
}
