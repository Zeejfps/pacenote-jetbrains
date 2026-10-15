package dev.diffdino.pairing.editor

import dev.diffdino.pairing.model.SuggestionActions

/** What the user can do with a draft shown in the editor, each one an action with a keyboard shortcut. */
internal enum class DraftCommand(val actionId: String, val label: String) {
    Accept("DiffDino.AcceptDraft", "Accept"),
    AcceptAndNext("DiffDino.AcceptDraftAndNext", "Accept & next"),
    Next("DiffDino.NextStop", "Next"),
    ;

    fun run(actions: SuggestionActions) {
        when (this) {
            Accept -> actions.accept()
            AcceptAndNext -> actions.acceptAndNext()
            Next -> actions.next()
        }
    }
}
