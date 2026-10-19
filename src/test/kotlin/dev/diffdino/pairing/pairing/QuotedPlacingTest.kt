package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.DeclarationSite
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftSpan
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.LineSpan
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent's code placed by the lines it quotes: found in the stop's declaration, once, as whole lines. */
class QuotedPlacingTest {
    private val file = listOf(
        "class Client {",                 // 1
        "    fun fetch() {",              // 2
        "        val a = 1",              // 3
        "        send(a)",                // 4
        "        send(a)",                // 5
        "    }",                          // 6
        "",                               // 7
        "    fun fetch(retries: Int) {",  // 8
        "        log(\"retry\")",         // 9
        "    }",                          // 10
        "",                               // 11
        "    fun other() {",              // 12
        "        close()   ",             // 13
        "    }",                          // 14
        "}",                              // 15
    )

    private val fetch = StopLocation.OnSymbol(
        "C:/repo/Client.kt", TextPosition.at(2, 8), file[1], FileLine(6), file,
        namesakes = listOf(DeclarationSite(TextPosition.at(8, 8), FileLine(10))),
    )

    private val other = StopLocation.OnSymbol("C:/repo/Client.kt", TextPosition.at(12, 8), file[11], FileLine(14), file)

    private fun place(code: String, quote: String, at: StopLocation = fetch) =
        DraftPlacing.place(at, DraftRequest(code, DraftSpan.Quoted(quote)))

    private fun placed(code: String, quote: String, at: StopLocation = fetch): DraftPlacement.Placed =
        place(code, quote, at) as? DraftPlacement.Placed ?: throw AssertionError("Refused: ${place(code, quote, at)}")

    private fun refused(code: String, quote: String, at: StopLocation = fetch): String =
        (place(code, quote, at) as? DraftPlacement.Refused ?: throw AssertionError("Placed: ${place(code, quote, at)}")).message

    @Test
    fun quotedLines_areReplacedWhereTheyAreInTheDeclaration() {
        assertEquals(StopDraft("        val a = 2", DraftPlace.Replace(LineSpan(3, 3))), placed("        val a = 2", "        val a = 1").draft)
    }

    @Test
    fun aQuotedLineKeptInTheCode_makesTheNewLinesAnInsertionAfterIt() {
        val draft = placed("        val a = 1\n        val b = 2", "        val a = 1").draft

        assertEquals(StopDraft("        val b = 2", DraftPlace.InsertAfter(FileLine(3))), draft)
    }

    @Test
    fun emptyCode_deletesTheQuotedLines() {
        assertEquals(StopDraft("", DraftPlace.Replace(LineSpan(3, 3))), placed("", "        val a = 1").draft)
    }

    @Test
    fun trailingSpacesAndWindowsLineEnds_stillMatch() {
        assertEquals(DraftPlace.Replace(LineSpan(13, 13)), placed("        close(true)", "        close()\r\n", other).draft.place)
        assertEquals(DraftPlace.Replace(LineSpan(3, 4)), placed("x", "        val a = 1   \r\n        send(a)", fetch).draft.place)
    }

    @Test
    fun linesThatFitTwoPlaces_areRefused_namingBoth() {
        val message = refused("        send(b)", "        send(a)")

        assertTrue(message, message.contains("fits 2 places"))
        assertTrue(message, message.contains("lines 4-4, lines 5-5"))
    }

    @Test
    fun moreContext_makesThemFitOnePlace() {
        val draft = placed("        val a = 1\n        send(b)", "        val a = 1\n        send(a)").draft

        assertEquals(StopDraft("        send(b)", DraftPlace.Replace(LineSpan(4, 4))), draft)
    }

    @Test
    fun linesInTheOneNamesakeThatHoldsThem_moveTheStopThere() {
        val placement = placed("        log(\"again\")", "        log(\"retry\")")

        assertEquals(DraftPlace.Replace(LineSpan(9, 9)), placement.draft.place)
        val location = placement.location as StopLocation.OnSymbol
        assertEquals(FileLine(8), location.at.line)
        assertEquals(FileLine(10), location.lastLine)
        assertEquals(listOf(DeclarationSite(TextPosition.at(2, 8), FileLine(6))), location.namesakes)
    }

    @Test
    fun linesOutsideTheDeclaration_areRefused_sayingWhereTheyAre() {
        val message = refused("        close(true)", "        close()")

        assertTrue(message, message.contains("not in the declaration at lines 2-6"))
        assertTrue(message, message.contains("lines 13-13, outside this declaration"))
        assertTrue(message, message.contains("     2\t    fun fetch() {"))
    }

    @Test
    fun linesIndentedDifferently_areRefused_sayingSo() {
        val message = refused("val a = 2", "val a = 1")

        assertTrue(message, message.contains("Lines 3-3 read the same but for indentation"))
    }

    @Test
    fun partOfALine_isRefused_askingForWholeLines() {
        val message = refused("val a = 2", "a = 1")

        assertTrue(message, message.contains("Line 3 holds the first quoted line as part of it"))
    }

    @Test
    fun linesNowhereInTheFile_pointToEditorRead() {
        assertTrue(refused("x", "        nothing()").contains("editor_read"))
    }

    @Test
    fun anEmptyQuote_isRefused() {
        assertTrue(refused("x", "  \n").contains("replaces is empty"))
    }

    @Test
    fun aStopForANewDeclaration_takesNoQuote() {
        val insertion = StopLocation.Insertion("C:/repo/Client.kt", TextPosition.at(6, 5), "fetch", file)

        assertTrue(refused("fun more() {}", "    }", insertion).contains("this stop is for a new one"))
    }
}
