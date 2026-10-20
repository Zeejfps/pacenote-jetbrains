package dev.pacenote.editor

import com.intellij.openapi.diff.DiffColors
import dev.pacenote.model.DraftPlace
import dev.pacenote.model.LineSpan
import dev.pacenote.model.StopDraft
import dev.pacenote.model.StopLocation

internal class DraftTest : PairingEditorTestBase() {
    private val added = "function added() {\n  return 2\n}"

    override fun setUp() {
        super.setUp()
        open()
    }

    private fun showAddedAfterTop(code: String = added): StopLocation {
        val location = located("added", after = "top")
        presentation.showDraft(location, insertAfter(12, code), noActions)
        flush()
        return location
    }

    fun testShowDraftDrawsOneBlockOfTheDraftsLines() {
        showAddedAfterTop()
        val shown = blocks()
        assertEquals(1, shown.size)
        assertEquals(listOf("function added() {", "  return 2", "}"), shown.single().rows.map { it.text })
    }

    fun testADraftIsNotDrawnIntoAFileThatChangedSinceTheStopWasFound() {
        val location = located("added", after = "top")
        typeAtEndOfLine(1, "\n// typed meanwhile")

        assertFalse(presentation.showDraft(location, insertAfter(12, added), noActions))
        assertEmpty(blocks())
        assertTrue(presentation.showDraft(located("added", after = "top"), insertAfter(13, added), noActions))
        assertEquals(1, blocks().size)
    }

    fun testBlockHangsBetweenTheAnchorAndTheLineBelow() {
        showAddedAfterTop()
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(editor.document.getLineStartOffset(12), inlay.offset)
        assertTrue(inlay.properties.isShownAbove)
    }

    fun testClearDraftRemovesTheBlock() {
        showAddedAfterTop()
        presentation.clearDraft()
        assertEmpty(blocks())
        assertEmpty(presentation.draftNeeds.value)
    }

    fun testShowingAnotherDraftReplacesTheFirst() {
        showAddedAfterTop()
        presentation.showDraft(located("added", after = "top"), insertAfter(12, "const only = 1"), noActions)
        flush()
        assertEquals(listOf("const only = 1"), blocks().single().rows.map { it.text })
    }

    fun testBlockFollowsLinesInsertedAbove() {
        showAddedAfterTop()
        typeAtEndOfLine(1, "\n// one\n// two")
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(editor.document.getLineStartOffset(14), inlay.offset)
    }

    fun testLinesTheUserHasTypedDropOut() {
        showAddedAfterTop()
        typeAtEndOfLine(12, "\nfunction added() {")
        assertEquals(listOf("  return 2", "}"), blocks().single().rows.map { it.text })
        typeAtEndOfLine(13, "\n  return 2")
        assertEquals(listOf("}"), blocks().single().rows.map { it.text })
        typeAtEndOfLine(14, "\n}")
        assertEmpty(blocks())
    }

    fun testLinesAlreadyBelowAreNeverTakenForTypedOnes() {
        showAddedAfterTop("const arrow = 1\n}")
        assertEquals(listOf("const arrow = 1", "}"), blocks().single().rows.map { it.text })
    }

    fun testTypedLinesComeBackWhenUndone() {
        showAddedAfterTop()
        typeAtEndOfLine(12, "\nfunction added() {")
        assertEquals(2, blocks().single().rows.size)
        undo()
        assertEquals(3, blocks().single().rows.size)
    }

    fun testTakeInsertsTheDraftAsOneUndoableStep() {
        val before = text
        val location = showAddedAfterTop()
        assertTrue(await { presentation.takeDraft(location) })
        flush()
        val lines = text.split('\n')
        assertEquals("}", lines[11])
        assertEquals(listOf("function added() {", "  return 2", "}"), lines.subList(12, 15))
        assertEquals("", lines[15])
        assertEmpty(blocks())

        undo()
        assertEquals(before, text)
    }

    fun testTakeOnlyInsertsWhatIsLeftOfTheDraft() {
        val location = showAddedAfterTop()
        typeAtEndOfLine(12, "\nfunction added() {")
        assertTrue(await { presentation.takeDraft(location) })
        assertEquals(1, text.split('\n').count { it == "function added() {" })
        assertTrue(text.contains("function added() {\n  return 2\n}\n"))
    }

    fun testTakeReplacesTheLinesAndTintsThemMeanwhile() {
        val location = located("top")
        val draft = StopDraft("  return 2", DraftPlace.Replace(LineSpan(11, 11)))
        presentation.showDraft(location, draft, noActions)
        flush()
        val tinted = highlighters().filter { it.textAttributesKey == DiffColors.DIFF_DELETED }
        assertEquals(1, tinted.size)
        assertEquals(editor.document.getLineStartOffset(10), tinted.single().startOffset)

        val before = text
        assertTrue(await { presentation.takeDraft(location) })
        assertEquals(before.replace("  return 1", "  return 2"), text)
        assertEmpty(highlighters().filter { it.textAttributesKey == DiffColors.DIFF_DELETED })

        undo()
        assertEquals(before, text)
    }

    fun testReplacingWithNothingDeletesTheLines() {
        val location = located("top")
        presentation.showDraft(location, StopDraft("", DraftPlace.Replace(LineSpan(10, 11))), noActions)
        flush()
        assertEquals(1, blocks().size)
        assertTrue(await { presentation.takeDraft(location) })
        assertFalse(text.contains("helperCall"))
        assertFalse(text.contains("return 1"))
        assertTrue(text.contains("function top() {\n}"))
    }

    fun testTakeRefusesAnotherFile() {
        showAddedAfterTop()
        assertFalse(await { presentation.takeDraft(StopLocation.NewFile("/elsewhere/b.ts")) })
    }

    fun testTakeWithoutADraftAnswersNo() {
        assertFalse(await { presentation.takeDraft(located("top")) })
    }

    fun testNewFileTakesTheDraftAsItsFirstBlock() {
        open("fresh.ts", "")
        val location = located("anything", path = "fresh.ts")
        assertTrue(location is StopLocation.NewFile)
        val draft = StopDraft("export const fresh = 1", DraftPlace.Replace(LineSpan(1, 1)))
        presentation.showDraft(location, draft, noActions)
        flush()
        assertEquals(listOf("export const fresh = 1"), blocks().single().rows.map { it.text })
        assertEmpty(highlighters().filter { it.textAttributesKey == DiffColors.DIFF_DELETED })

        assertTrue(await { presentation.takeDraft(location) })
        assertEquals("export const fresh = 1", text)
    }

    fun testReadTextIsWhatTheUserHasUnsaved() {
        typeAtEndOfLine(1, "\n// edited")
        val location = located("top")
        assertTrue(await { presentation.readText(location) }!!.contains("// edited"))
    }

    fun testSaveUnsavedWritesTheDocuments() {
        typeAtEndOfLine(1, "\n// edited")
        assertEmpty(presentation.saveUnsaved())
        assertFalse(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))
    }

    fun testRevealPlacesTheCaretOnTheDeclaration() {
        val location = located("Foo.bar")
        editor.caretModel.moveToOffset(0)
        presentation.reveal(location)
        assertEquals(editor.document.getLineStartOffset(1) + 2, editor.caretModel.offset)
    }

    fun testRevealDraftPlacesTheCaretAtTheEndOfTheLineItHangsFrom() {
        val location = located("added", after = "top")
        presentation.revealDraft(location, insertAfter(12, added))
        assertEquals(editor.document.getLineEndOffset(11), editor.caretModel.offset)
    }

    fun testCaretFollowsTheEditor() {
        flush()
        editor.caretModel.moveToOffset(editor.document.getLineStartOffset(8) + 3)
        flush()
        val caret = presentation.caret.value!!
        assertEquals("a.ts", caret.path)
        assertEquals(9, caret.at.line.value)
        assertEquals(3, caret.at.column)
        assertEquals("", caret.selectedText)

        editor.selectionModel.setSelection(editor.document.getLineStartOffset(8), editor.document.getLineEndOffset(8))
        flush()
        assertEquals("function top() {", presentation.caret.value!!.selectedText)
    }
}
