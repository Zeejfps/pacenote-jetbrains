package dev.pacenote.ui.support

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/** Lays out [body] over the whole container and [badge] over its top trailing corner, [inset] in. */
class CornerOverlayLayout(private val body: Component, private val badge: Component, private val inset: Int = 6) : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = body.preferredSize

    override fun minimumLayoutSize(parent: Container): Dimension = body.minimumSize

    override fun layoutContainer(parent: Container) {
        body.setBounds(0, 0, parent.width, parent.height)
        val size = badge.preferredSize
        val margin = JBUI.scale(inset)
        badge.setBounds(parent.width - size.width - margin, margin, size.width, size.height)
    }
}
