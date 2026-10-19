package dev.diffdino.pairing.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.pairing.AgentPrompt
import dev.diffdino.pairing.pairing.lines
import dev.diffdino.pairing.ui.action.SelectedCode
import dev.diffdino.pairing.ui.action.SelectionQuoteResult
import dev.diffdino.pairing.ui.support.CardCorner
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.FileOpener
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedCard
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JPanel

/**
 * The code selected in the editor, shown above the message as the file and lines it covers, which
 * goes along with the next message sent. Closing it, or sending it, leaves that selection out until
 * a different one is made. A selection too long to send is named, but not taken.
 */
class SelectionChip(private val project: Project, private val root: Path, selected: SelectedCode, lifetime: Lifetime) :
    JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
    private var shown: SelectionQuoteResult = SelectionQuoteResult.NoSelection
    private var dismissed: CodeQuote.InFile? = null
    private val icon = JBLabel()
    private val label = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(0, 4)
    }
    private val close = InplaceButton(
        IconButton(DiffDinoBundle.message("chat.selection.leave.out"), AllIcons.Actions.Close, AllIcons.Actions.CloseHovered),
    ) { dismiss() }
    private val pill = RoundedCard(ChatColors.raised, ChatColors.border, CardCorner.Pill(CAP), FlowLayout(FlowLayout.LEFT, 0, 0), vertical = 2, horizontal = 8)

    /** The quote that goes along with the next message, if any. */
    val attached: CodeQuote.InFile?
        get() = (shown as? SelectionQuoteResult.Quoted)?.quote?.takeIf { it != dismissed }

    /** Fires when the chip comes or goes, so the composer can make room. */
    var onChanged: () -> Unit = {}

    init {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        pill.add(icon)
        pill.add(label)
        pill.add(close)
        add(pill)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                attached?.let { FileOpener.open(project, it.absolutePath, it.start.value) }
            }
        })
        lifetime.bind(selected.current) { result ->
            shown = result
            if ((result as? SelectionQuoteResult.Quoted)?.quote != dismissed) dismissed = null
            refresh()
        }
    }

    /** Leaves the selection now shown out of what is sent, until a different one is made. */
    fun dismiss() {
        dismissed = (shown as? SelectionQuoteResult.Quoted)?.quote
        refresh()
    }

    private fun refresh() {
        val quote = attached
        val result = shown
        when {
            quote != null -> {
                val path = AgentPrompt.relativeTo(root, quote.absolutePath)
                icon.icon = FileTypeManager.getInstance().getFileTypeByFileName(fileName(quote.absolutePath)).icon
                label.text = "${fileName(quote.absolutePath)}:${span(quote)}"
                label.foreground = ChatColors.accent
                label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                pill.toolTipText = DiffDinoBundle.message("chat.selection.tooltip", path, quote.lines)
                label.toolTipText = pill.toolTipText
                close.isVisible = true
                isVisible = true
            }
            result is SelectionQuoteResult.TooLong -> {
                icon.icon = AllIcons.General.Warning
                label.text = DiffDinoBundle.message("chat.selection.too.long", result.lines, CodeQuote.MAX_LINES)
                label.foreground = ChatColors.warningText
                label.cursor = Cursor.getDefaultCursor()
                pill.toolTipText = null
                label.toolTipText = null
                close.isVisible = false
                isVisible = true
            }
            else -> isVisible = false
        }
        revalidate()
        repaint()
        onChanged()
    }

    private fun fileName(absolutePath: String): String = absolutePath.substringAfterLast('/').substringAfterLast('\\')

    private fun span(quote: CodeQuote.InFile): String =
        if (quote.start == quote.end) "${quote.start.value}" else "${quote.start.value}-${quote.end.value}"

    private companion object {
        const val CAP = 10
    }
}
