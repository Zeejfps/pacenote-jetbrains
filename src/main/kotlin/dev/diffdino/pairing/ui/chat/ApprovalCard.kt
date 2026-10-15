package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.PairingMessage
import dev.diffdino.pairing.pairing.ToolApprovalOutcome
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ReactiveSlot
import dev.diffdino.pairing.ui.support.RoundedPanel
import dev.diffdino.pairing.ui.support.WrapPanel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A tool call the write guard left to the user: what it would change and the answers, until it is
 * answered, and then a line saying how it went that opens to what was asked.
 */
class ApprovalCard(
    private val approval: PairingMessage.Approval,
    private val project: Project,
    lifetime: Lifetime,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        add(ReactiveSlot(lifetime, approval.pending.outcome) { outcome, _ -> render(outcome) }, BorderLayout.CENTER)
    }

    private fun render(outcome: ToolApprovalOutcome): JComponent = when (outcome) {
        ToolApprovalOutcome.Pending -> pendingCard()
        ToolApprovalOutcome.Approved, ToolApprovalOutcome.Denied, ToolApprovalOutcome.Cancelled -> ExpandableRow(outcomeText(outcome)) { details() }
    }

    private fun pendingCard(): JComponent {
        val pending = approval.pending
        val card = RoundedPanel(ChatColors.card, ChatColors.border, VerticalLayout(JBUI.scale(8)))
        card.border = JBUI.Borders.empty(10, 12)
        card.add(JBLabel(DiffDinoBundle.message("approval.title", pending.toolName)).apply { font = JBFont.label().asBold() })
        card.add(details())
        card.add(WrapPanel().apply {
            add(JButton(DiffDinoBundle.message("approval.allow")).apply { addActionListener { pending.approve() } })
            add(JButton(DiffDinoBundle.message("approval.deny")).apply { addActionListener { pending.deny() } })
            approval.allowance?.let { allowance ->
                add(JButton(DiffDinoBundle.message("approval.allow.file")).apply { addActionListener { allowance.allow() } })
            }
        })
        return card
    }

    private fun details(): JComponent =
        if (approval.preview.isNotEmpty()) EditDetails(approval.preview, approval.edits, project)
        else EditDetails.commandLines(approval.pending.arguments)

    private fun outcomeText(outcome: ToolApprovalOutcome): String {
        val title = approval.pending.toolName
        return when (outcome) {
            ToolApprovalOutcome.Approved ->
                if (approval.allowance?.granted?.value == true) DiffDinoBundle.message("approval.approved.file", title)
                else DiffDinoBundle.message("approval.approved", title)
            ToolApprovalOutcome.Denied -> DiffDinoBundle.message("approval.denied", title)
            ToolApprovalOutcome.Cancelled -> DiffDinoBundle.message("approval.withdrawn", title)
            ToolApprovalOutcome.Pending -> title
        }
    }
}
