package dev.diffdino.pairing.ui.chat

import com.intellij.openapi.util.Disposer
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.RoundedCard
import dev.diffdino.pairing.ui.support.TypingDots
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import javax.swing.JPanel

/** Dots breathing in a small card, shown while [visibleWhile] holds. */
class TypingIndicator(visibleWhile: StateFlow<Boolean>, lifetime: Lifetime) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val dots = TypingDots()
        Disposer.register(lifetime, dots)
        add(RoundedCard(ChatColors.raised, vertical = 10).apply { add(dots, BorderLayout.CENTER) }, BorderLayout.WEST)
        lifetime.bind(visibleWhile) {
            isVisible = it
            dots.running = it
        }
    }
}
