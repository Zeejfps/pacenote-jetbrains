package dev.diffdino.pairing.ui.support

import java.awt.Container
import java.awt.MouseInfo
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Tells [onChange] whether the pointer is over [area], whichever of the components in it it is over. */
class HoverArea(private val area: JComponent, private val onChange: (Boolean) -> Unit) {
    private var hovered = false

    private val listener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) = set(true)

        override fun mouseExited(e: MouseEvent) = set(pointerInside())
    }

    init {
        attach(area)
    }

    private fun attach(component: java.awt.Component) {
        component.addMouseListener(listener)
        if (component is Container) component.components.forEach(::attach)
    }

    private fun pointerInside(): Boolean {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return false
        SwingUtilities.convertPointFromScreen(pointer, area)
        return area.contains(pointer)
    }

    private fun set(value: Boolean) {
        if (value == hovered) return
        hovered = value
        onChange(value)
    }
}
