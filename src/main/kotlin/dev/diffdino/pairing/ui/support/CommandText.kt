package dev.diffdino.pairing.ui.support

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import javax.swing.SwingUtilities

/** Lines of code or a command in the editor's font and the secondary colour, wrapped to the width it is given. */
class CommandText(text: String) : JBTextArea(text) {
    private var knownWidth = 0

    init {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = false
        border = null
        foreground = ChatColors.secondaryText
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBFont.small().size2D)
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val widthChanged = width != knownWidth
        knownWidth = width
        super.setBounds(x, y, width, height)
        if (widthChanged) SwingUtilities.invokeLater { revalidate() }
    }
}
