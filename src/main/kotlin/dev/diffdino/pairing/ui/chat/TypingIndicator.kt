package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.Lifetime
import kotlinx.coroutines.flow.StateFlow
import java.awt.FlowLayout
import javax.swing.JPanel

/** A spinner and [text], shown while [visibleWhile] holds. */
class TypingIndicator(text: String, visibleWhile: StateFlow<Boolean>, lifetime: Lifetime) :
    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
    init {
        isOpaque = false
        val spinner = AsyncProcessIcon("diffdino-typing")
        Disposer.register(lifetime, spinner)
        add(spinner)
        add(JBLabel(text).apply { foreground = ChatColors.secondaryText })
        lifetime.bind(visibleWhile) { isVisible = it }
    }
}
