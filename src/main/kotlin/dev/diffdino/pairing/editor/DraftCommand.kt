package dev.diffdino.pairing.editor

import com.intellij.openapi.keymap.KeymapUtil
import dev.diffdino.pairing.model.SuggestionActions

/** What the user can do with a draft shown in the editor, each one an action with a keyboard shortcut. */
internal enum class DraftCommand(val actionId: String, val label: String) {
    Accept("DiffDino.AcceptDraft", "Accept"),
    AcceptAndNext("DiffDino.AcceptDraftAndNext", "Accept & next"),
    Next("DiffDino.NextStop", "Next"),
    ;

    /** The label with the keys bound to the command, as its pill's tooltip reads. */
    val tooltip: String
        get() = KeymapUtil.getFirstKeyboardShortcutText(actionId).let { if (it.isEmpty()) label else "$label ($it)" }

    fun run(actions: SuggestionActions) {
        when (this) {
            Accept -> actions.accept()
            AcceptAndNext -> actions.acceptAndNext()
            Next -> actions.next()
        }
    }
}
