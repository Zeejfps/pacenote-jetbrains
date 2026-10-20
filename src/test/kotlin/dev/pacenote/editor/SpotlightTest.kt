package dev.pacenote.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import dev.pacenote.model.CodeSpotlights
import dev.pacenote.model.LineSpan
import dev.pacenote.model.ReadOutcome
import dev.pacenote.model.ReadSpan
import dev.pacenote.model.ShowOutcome
import dev.pacenote.model.ShowTarget
import dev.pacenote.model.SpotlightRun

internal class SpotlightTest : PairingEditorTestBase() {
    private val spotlights: EditorCodeSpotlights get() = project.service<CodeSpotlights>() as EditorCodeSpotlights

    override fun setUp() {
        super.setUp()
        open()
    }

    private fun spotlight(vararg runs: SpotlightRun) = await { spotlights.spotlight("a.ts", runs.toList()) }

    private fun bands() = highlighters().filter { it.layer < com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION }
        .filter { it.targetArea == com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE }

    private fun read(span: ReadSpan) = await { spotlights.read("a.ts", span) }

    fun testReadIsTheFileAsTheEditorHasIt_unsavedEditsIncluded() {
        typeAtEndOfLine(1, "\n  // typed, not saved")

        val read = read(ReadSpan.Whole) as ReadOutcome.Read

        assertEquals("a.ts", read.relativePath)
        assertEquals("  // typed, not saved", read.lines[1])
        assertEquals(LineSpan(1, read.lines.size), read.span)
    }

    fun testReadADeclarationOrLines() {
        assertEquals(LineSpan(2, 4), (read(ReadSpan.Symbol("Foo.bar")) as ReadOutcome.Read).span)
        assertEquals(LineSpan(9, 11), (read(ReadSpan.Lines(9, 11)) as ReadOutcome.Read).span)
        val toTheEnd = read(ReadSpan.Lines(15, 500)) as ReadOutcome.Read
        assertEquals(LineSpan(15, toTheEnd.lines.size), toTheEnd.span)
    }

    fun testReadMissesAreDescribed() {
        assertTrue((read(ReadSpan.Lines(500, null)) as ReadOutcome.Missed).message.contains("there is no line 500"))
        assertTrue((read(ReadSpan.Symbol("nope")) as ReadOutcome.Missed).message.contains("'nope' is not declared"))
    }

    fun testShowOpensAtASymbol() {
        editor.caretModel.moveToOffset(0)
        val outcome = await { spotlights.show("a.ts", ShowTarget.Symbol("Foo.bar")) } as ShowOutcome.Shown
        assertEquals("a.ts", outcome.relativePath)
        assertEquals(2, outcome.line)
        assertEquals(1, editor.caretModel.logicalPosition.line)
    }

    fun testShowOpensAtALine() {
        val outcome = await { spotlights.show("a.ts", ShowTarget.Line(9)) } as ShowOutcome.Shown
        assertEquals(9, outcome.line)
        assertEquals(8, editor.caretModel.logicalPosition.line)
    }

    fun testShowMissesAreDescribed() {
        val symbol = await { spotlights.show("a.ts", ShowTarget.Symbol("nope")) } as ShowOutcome.Missed
        assertTrue(symbol.message, symbol.message.contains("'nope' is not declared in a.ts"))
        val line = await { spotlights.show("a.ts", ShowTarget.Line(500)) } as ShowOutcome.Missed
        assertEquals("a.ts has 17 lines; there is no line 500.", line.message)
        val outside = await { spotlights.show("../x.ts", ShowTarget.Line(1)) } as ShowOutcome.Missed
        assertEquals("../x.ts is outside the repository.", outside.message)
        val absent = await { spotlights.show("absent.ts", ShowTarget.Line(1)) } as ShowOutcome.Missed
        assertEquals("absent.ts is not a file in the repository, or is too large to open.", absent.message)
    }

    fun testRunsAreLitAsBandsWithAPinEach() {
        val outcome = spotlight(SpotlightRun.Lines(10, 11, "calls it"), SpotlightRun.Symbol("top", "the function")) as ShowOutcome.Shown
        assertEquals(10, outcome.line)
        assertEquals(listOf(LineSpan(10, 11), LineSpan(9, 12)), spotlights.litLines)
        assertEquals(2, bands().size)
        val pins = editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
        assertEquals(2, pins.size)
    }

    fun testRunsFollowLinesInsertedAbove() {
        spotlight(SpotlightRun.Lines(10, 11, "calls it"))
        assertEquals(listOf(LineSpan(10, 11)), spotlights.litLines)
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "// a\n// b\n") }
        assertEquals(listOf(LineSpan(12, 13)), spotlights.litLines)
        val pin = editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).single()
        assertEquals(11, editor.document.getLineNumber(pin.offset))
    }

    fun testALaterCallReplacesTheSet() {
        spotlight(SpotlightRun.Lines(1, 1, "one"), SpotlightRun.Lines(2, 2, "two"))
        spotlight(SpotlightRun.Lines(9, 9, "only"))
        assertEquals(listOf(LineSpan(9, 9)), spotlights.litLines)
        assertEquals(1, bands().size)
        assertEquals(1, editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).size)
    }

    fun testClearRemovesEverything() {
        spotlight(SpotlightRun.Lines(10, 11, "calls it"))
        spotlights.clear()
        assertEmpty(spotlights.litLines)
        assertEmpty(bands())
        assertEmpty(editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength))
        assertNotNull(DocumentMarkupModel.forDocument(editor.document, project, false))
    }

    fun testBadRunsAreRefusedWithTheirIndex() {
        val reversed = spotlight(SpotlightRun.Lines(1, 1, ""), SpotlightRun.Lines(5, 3, "")) as ShowOutcome.Missed
        assertEquals("spotlights[1]: 'to' (3) is before 'from' (5).", reversed.message)
        val past = spotlight(SpotlightRun.Lines(1, 99, "")) as ShowOutcome.Missed
        assertEquals("spotlights[0]: a.ts has 17 lines; there is no line 99.", past.message)
        assertEmpty(spotlights.litLines)
    }
}
