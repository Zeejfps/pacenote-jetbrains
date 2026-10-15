package dev.diffdino.pairing.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.RoadmapChange
import dev.diffdino.pairing.model.RoadmapEntry
import dev.diffdino.pairing.pairing.PairingStore
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ReactiveSlot
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JPanel

/** The goal as the user wrote it, and the agent's roadmap toward it. */
class GoalAndRoadmap(store: PairingStore, lifetime: Lifetime) : JPanel(VerticalLayout(JBUI.scale(6))) {
    init {
        isOpaque = false
        add(heading(DiffDinoBundle.message("session.goal")))
        add(WrappingHtmlPane().also { Disposer.register(lifetime, it); it.showPlain(store.goal) })
        add(heading(DiffDinoBundle.message("session.roadmap")))
        add(ReactiveSlot(lifetime, store.roadmap) { entries, _ -> roadmapOf(entries, lifetime) })
    }

    private fun roadmapOf(entries: List<RoadmapEntry>, lifetime: Lifetime): JComponent =
        if (entries.isEmpty()) JBLabel(DiffDinoBundle.message("session.roadmap.empty")).apply { foreground = ChatColors.secondaryText }
        else JPanel(VerticalLayout(JBUI.scale(3))).apply {
            isOpaque = false
            for (entry in entries) add(RoadmapRow(entry, lifetime.child()))
        }

    private fun heading(text: String) = JBLabel(text).apply {
        font = JBFont.small().asBold()
        foreground = ChatColors.secondaryText
    }
}

/** One milestone: ticked when done, tagged when the last revision added it, struck through and
 * tagged when the last revision dropped it. */
class RoadmapRow(entry: RoadmapEntry, lifetime: Lifetime) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
    init {
        isOpaque = false
        val dropped = entry.change == RoadmapChange.Removed
        add(JBLabel(TickBox(entry.done)).apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.WEST)
        add(WrappingHtmlPane().also {
            Disposer.register(lifetime, it)
            it.showPlain(entry.title, if (entry.done || dropped) ChatColors.secondaryText else null, struck = dropped)
        }, BorderLayout.CENTER)
        when (entry.change) {
            RoadmapChange.Kept -> Unit
            RoadmapChange.Added -> add(tag(DiffDinoBundle.message("session.roadmap.added"), ChatColors.accent), BorderLayout.EAST)
            RoadmapChange.Removed -> add(tag(DiffDinoBundle.message("session.roadmap.dropped"), ChatColors.dangerText), BorderLayout.EAST)
        }
    }

    private fun tag(text: String, color: Color) = JBLabel(text).apply {
        font = JBFont.small().asBold()
        foreground = color
    }
}
