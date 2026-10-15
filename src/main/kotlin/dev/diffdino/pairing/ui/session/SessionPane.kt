package dev.diffdino.pairing.ui.session

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.pairing.PairingSession
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ScrollableColumn
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** The live session's pane above the conversation: the goal and roadmap, then the stop the user is
 * on, scrolling on its own, with the stop's controls pinned under it. A new stop takes it back to
 * the top. */
class SessionPane(session: PairingSession, project: Project, lifetime: Lifetime) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        val store = session.store
        val column = ScrollableColumn(GAP).apply {
            border = JBUI.Borders.empty(10, 12)
            add(GoalAndRoadmap(store, lifetime))
            add(StopSlot(session, lifetime))
        }
        val scroll = ScrollableColumn.scrolling(column)
        add(scroll, BorderLayout.CENTER)
        add(StopActions(session, project, lifetime), BorderLayout.SOUTH)

        var shown = store.stop.value?.stop?.number ?: 0
        lifetime.bind(store.stop) { open ->
            val number = open?.stop?.number ?: 0
            if (number != shown && number != 0) SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }
            shown = number
        }
    }

    private companion object {
        const val GAP = 12
    }
}
