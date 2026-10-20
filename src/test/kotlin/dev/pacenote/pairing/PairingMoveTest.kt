package dev.pacenote.pairing

import dev.pacenote.model.DraftOutcome
import dev.pacenote.model.EditorCaret
import dev.pacenote.model.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the agent is told of each thing the user did in a session: the turn it gets in place of
 * waiting on them. */
class PairingMoveTest {
    private fun relative(path: String): String? = if (path.startsWith("C:/repo/")) path.removePrefix("C:/repo/") else null

    @Test
    fun done_carriesTheDiffAndWhatTheUserDidWithTheCode() {
        val told = PairingInstructions.move(PairingAction.Done(3, "+ retry()", DraftOutcome.AcceptedThenEdited, emptyList()), ::relative)

        assertTrue(told.startsWith("Pacenote pairing: the user finished stop 3;"))
        assertTrue(told.contains("changed it"))
        assertTrue(told.contains("```diff\n+ retry()\n```"))
    }

    @Test
    fun aDiffWithAFenceInIt_cannotCloseTheFenceItIsIn() {
        val told = PairingInstructions.move(PairingAction.Done(1, "+ ```code```", DraftOutcome.NotAccepted, emptyList()), ::relative)

        assertTrue(told.contains("````diff\n+ ```code```\n````"))
    }

    @Test
    fun done_withNothingChanged_saysSo_andListsItsProblems() {
        val told = PairingInstructions.move(
            PairingAction.Done(2, "", DraftOutcome.NotAccepted, listOf("src/A.cs could not be saved")), ::relative)

        assertTrue(told.contains("(no changes)"))
        assertTrue(told.endsWith("Problems:\n- src/A.cs could not be saved"))
    }

    @Test
    fun aMessage_saysWhereTheUserWas() {
        val caret = EditorCaret("C:/repo/src/Client.cs", TextPosition.at(12, 3), "retries")

        val told = PairingInstructions.move(PairingAction.Message(4, "Is this right?", caret), ::relative)

        assertTrue(told, told.startsWith("Pacenote pairing: the user says, at stop 4 with the caret at src/Client.cs:12:4 and this selected:"))
        assertTrue(told.endsWith(":\n\nIs this right?"))
    }

    @Test
    fun aMessageBetweenStops_namesNoStop() {
        assertEquals("Pacenote pairing: the user says:\n\nWhy?", PairingInstructions.move(PairingAction.Message(0, "Why?", null), ::relative))
    }
}
