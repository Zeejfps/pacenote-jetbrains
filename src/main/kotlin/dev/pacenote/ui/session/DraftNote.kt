package dev.pacenote.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.pacenote.model.DraftPlace
import dev.pacenote.model.DraftState
import dev.pacenote.pairing.OpenStop
import dev.pacenote.pairing.PairingStore
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.Lifetime
import dev.pacenote.ui.support.WrappingHtmlPane
import javax.swing.JPanel

/** Where the agent's code for the open stop is, and what it uses that nothing declares yet. */
class DraftNote(private val store: PairingStore, lifetime: Lifetime) : JPanel(VerticalLayout(JBUI.scale(2))) {
    private val where = WrappingHtmlPane()
    private val needs = WrappingHtmlPane()

    init {
        isOpaque = false
        Disposer.register(lifetime, where)
        Disposer.register(lifetime, needs)
        add(where)
        add(needs)
        lifetime.bind(store.stop) { showWhere(it) }
        lifetime.bind(store.draftNeeds) { showNeeds(it) }
    }

    private fun showWhere(open: OpenStop?) {
        where.isVisible = open != null
        if (open == null) return
        val text = when (open.draftState) {
            is DraftState.Taken -> PacenoteBundle.message("draft.accepted")
            DraftState.Offered -> when (val place = open.draft.place) {
                is DraftPlace.Replace -> PacenoteBundle.message("draft.replaces", place.lines.from, place.lines.to)
                is DraftPlace.InsertAfter -> PacenoteBundle.message("draft.inserts", place.line.value)
            }
        }
        where.showPlain(text, ChatColors.secondaryText, small = true)
    }

    private fun showNeeds(names: List<String>) {
        needs.isVisible = names.isNotEmpty()
        if (names.isNotEmpty()) needs.showPlain(PacenoteBundle.message("draft.needs", names.joinToString(", ")), ChatColors.warningText, small = true)
    }
}
