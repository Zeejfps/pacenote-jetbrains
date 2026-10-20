package dev.pacenote.pairing

import dev.pacenote.model.DraftPlace
import dev.pacenote.model.DraftRequest
import dev.pacenote.model.DraftSpan
import dev.pacenote.model.FileLine
import dev.pacenote.model.LineSpan
import dev.pacenote.model.StopDraft
import dev.pacenote.model.StopLocation
import dev.pacenote.model.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftPlacingTest {
    private val stop = StopLocation.OnSymbol(
        "C:/repo/a.ts", TextPosition.at(26, 0), "line 26", FileLine(27), RecordingPairingPresentation.FILE_LINES)

    private fun place(code: String, span: DraftSpan = DraftSpan.Declaration): StopDraft =
        when (val placement = DraftPlacing.place(stop, DraftRequest(code, span))) {
            is DraftPlacement.Placed -> placement.draft
            is DraftPlacement.Refused -> throw AssertionError("Refused: ${placement.message}")
        }

    @Test
    fun linesThatReadTheSame_areLeftOut_soAddedLinesGoInAsAnInsertion() {
        assertEquals(StopDraft("authorId?: string", DraftPlace.InsertAfter(FileLine(27))), place("line 26\nline 27\nauthorId?: string"))
    }

    @Test
    fun onlyTheLinesThatChange_areReplaced() {
        assertEquals(StopDraft("changed", DraftPlace.Replace(LineSpan(21, 21))), place("line 20\nchanged\nline 22", DraftSpan.Lines(20, 22)))
    }

    @Test
    fun linesAddedAboveTheFirstLine_keepItInTheReplacement() {
        assertEquals(StopDraft("import x\nline 1", DraftPlace.Replace(LineSpan(1, 1))), place("import x\nline 1", DraftSpan.Lines(1, 1)))
    }

    @Test
    fun codeThatChangesNothing_isRefused() {
        val placement = DraftPlacing.place(stop, DraftRequest("line 26\nline 27", DraftSpan.Declaration))

        assertTrue((placement as DraftPlacement.Refused).message.contains("changes nothing"))
    }

    @Test
    fun aNewFile_takesNoLines() {
        val placement = DraftPlacing.place(StopLocation.NewFile("C:/repo/b.ts"), DraftRequest("x", DraftSpan.After(1)))

        assertTrue(placement is DraftPlacement.Refused)
    }
}
