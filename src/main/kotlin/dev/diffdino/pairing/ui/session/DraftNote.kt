package dev.diffdino.pairing.ui.session

import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.DraftState
import dev.diffdino.pairing.pairing.OpenStop
import dev.diffdino.pairing.pairing.PairingStore
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.WrappingHtmlPane
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
            is DraftState.Taken -> DiffDinoBundle.message("draft.accepted")
            DraftState.Offered -> when (val place = open.draft.place) {
                is DraftPlace.Replace -> DiffDinoBundle.message("draft.replaces", place.lines.from, place.lines.to)
                is DraftPlace.InsertAfter -> DiffDinoBundle.message("draft.inserts", place.line.value)
            }
        }
        where.showPlain(text, ChatColors.secondaryText, small = true)
    }

    private fun showNeeds(names: List<String>) {
        needs.isVisible = names.isNotEmpty()
        if (names.isNotEmpty()) needs.showPlain(DiffDinoBundle.message("draft.needs", names.joinToString(", ")), ChatColors.warningText, small = true)
    }
}
