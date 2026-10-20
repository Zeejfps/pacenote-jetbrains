package dev.pacenote.ui.support

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/** The colours of the chat, derived from the IDE theme or its editor scheme. */
object ChatColors {
    val raised: Color = JBColor { ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.045) }

    val userFill: Color = JBColor {
        ColorUtil.mix(UIUtil.getPanelBackground(), JBUI.CurrentTheme.Focus.focusColor(), if (JBColor.isBright()) 0.16 else 0.34)
    }

    val inputFill: Color = JBColor { UIUtil.getTextFieldBackground() }

    val focusRing: Color = JBColor { JBUI.CurrentTheme.Focus.focusColor() }

    val accentFill: Color = JBColor { JBUI.CurrentTheme.Button.defaultButtonColorStart() }

    val onAccent: Color = JBColor { JBUI.CurrentTheme.Button.defaultButtonForeground() }

    val border: Color = JBColor.border()

    val secondaryText: Color = JBColor { UIUtil.getContextHelpForeground() }

    val accent: Color = JBColor { JBUI.CurrentTheme.Link.Foreground.ENABLED }

    val warningText: Color = JBColor(0x9A6700, 0xE3B341)

    val dangerText: Color = JBColor(0xC62828, 0xFF6B68)

    val successText: Color = JBColor(0x2E7D32, 0x6CC070)

    val refusedNoticeBackground: Color = JBColor { JBUI.CurrentTheme.Banner.WARNING_BACKGROUND }

    val errorNoticeBackground: Color = JBColor { JBUI.CurrentTheme.Banner.ERROR_BACKGROUND }

    val addedLine: Color = JBColor { schemeBackground(DiffColors.DIFF_INSERTED, JBColor(0xDFF3E0, 0x294436)) }

    val removedLine: Color = JBColor { schemeBackground(DiffColors.DIFF_DELETED, JBColor(0xFADBD8, 0x4E2B2B)) }

    private fun schemeBackground(key: TextAttributesKey, fallback: Color): Color =
        EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.backgroundColor ?: fallback
}
