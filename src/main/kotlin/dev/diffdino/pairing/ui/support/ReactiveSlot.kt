package dev.diffdino.pairing.ui.support

import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A place in a layout that shows what is built for the current value of a flow, and builds it
 * again when the value's [keyOf] changes. What was built last is disposed first.
 */
class ReactiveSlot<T>(
    private val lifetime: Lifetime,
    source: StateFlow<T>,
    private val keyOf: (T) -> Any? = { it },
    private val build: (T, Lifetime) -> JComponent?,
) : JPanel(BorderLayout()) {
    private var current: Lifetime? = null
    private var shownKey: Any? = null
    private var hasShown = false

    init {
        isOpaque = false
        lifetime.bind(source) { show(it) }
    }

    private fun show(value: T) {
        val key = keyOf(value)
        if (hasShown && key == shownKey) return
        hasShown = true
        shownKey = key
        current?.let(Disposer::dispose)
        removeAll()
        val child = lifetime.child()
        current = child
        build(value, child)?.let { add(it, BorderLayout.CENTER) }
        revalidate()
        repaint()
    }
}
