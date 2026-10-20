package dev.pacenote.editor

internal class NamesTest : PairingEditorTestBase() {
    override fun setUp() {
        super.setUp()
        open()
    }

    private fun show(code: String) {
        presentation.showDraft(located("added", after = "top"), insertAfter(12, code), noActions)
        flush()
    }

    fun testAnUndeclaredNameIsNeededAndADeclaredOneIsNot() {
        show("const total = undeclaredHelper(1) + top()")
        waitUntil("the draft's names") { presentation.draftNeeds.value.isNotEmpty() }
        assertEquals(listOf("undeclaredHelper"), presentation.draftNeeds.value)
    }

    fun testEachNameIsListedOnce() {
        show("const a = undeclaredHelper(1)\nconst b = undeclaredHelper(2) + otherMissing")
        waitUntil("the draft's names") { presentation.draftNeeds.value.size == 2 }
        assertEquals(listOf("undeclaredHelper", "otherMissing"), presentation.draftNeeds.value)
    }

    fun testNamesTheDraftDeclaresItselfAreNotNeeded() {
        show("function made() {\n  return 1\n}\nconst x = made()\nconst y = missingOne")
        waitUntil("the draft's names") { presentation.draftNeeds.value.isNotEmpty() }
        assertEquals(listOf("missingOne"), presentation.draftNeeds.value)
    }

    fun testMembersOfSomethingElseAreNotNeeded() {
        show("const z = top().somethingUnknown\nconst w = missingOne")
        waitUntil("the draft's names") { presentation.draftNeeds.value.isNotEmpty() }
        assertEquals(listOf("missingOne"), presentation.draftNeeds.value)
    }

    fun testMissingNamesAreMarkedInTheGhost() {
        show("const total = undeclaredHelper(1)")
        waitUntil("the marked name") { blocks().single().rows.single().missing.isNotEmpty() }
        val row = blocks().single().rows.single()
        assertEquals("undeclaredHelper", row.text.substring(row.missing.single().first, row.missing.single().last + 1))
    }

    fun testClearingForgetsTheNeeds() {
        show("const total = undeclaredHelper(1)")
        waitUntil("the draft's names") { presentation.draftNeeds.value.isNotEmpty() }
        presentation.clearDraft()
        assertEmpty(presentation.draftNeeds.value)
    }

    fun testTheGhostIsColouredByTheFilesLanguage() {
        show("const total = 1")
        waitUntil("the colours") { blocks().single().rows.single().runs.isNotEmpty() }
        val row = blocks().single().rows.single()
        assertTrue(row.runs.all { it.end <= row.text.length })
        assertTrue(row.runs.any { it.start == 0 })
    }
}
