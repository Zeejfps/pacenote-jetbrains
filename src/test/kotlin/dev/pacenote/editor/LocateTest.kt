package dev.pacenote.editor

import dev.pacenote.model.DeclarationSite
import dev.pacenote.model.FileLine
import dev.pacenote.model.StopLocation
import dev.pacenote.model.StopMiss
import dev.pacenote.model.StopPlacement
import dev.pacenote.model.TextPosition

internal class LocateTest : PairingEditorTestBase() {
    override fun setUp() {
        super.setUp()
        open()
    }

    fun testMethodInClassByDottedName() {
        val found = located("Foo.bar") as StopLocation.OnSymbol
        assertEquals(TextPosition.at(2, 2), found.at)
        assertEquals(4, found.lastLine.value)
        assertEquals("  bar(x: number): number {", found.lineText)
        assertEquals(sample.split('\n'), found.lines)
    }

    fun testOtherDeclarationsOfTheNameAreItsNamesakes() {
        open("b.ts", "class A {\n  run() {\n    return 1\n  }\n}\n\nclass B {\n  run() {\n    return 2\n  }\n}\n")

        val found = located("run", path = "b.ts") as StopLocation.OnSymbol

        assertEquals(TextPosition.at(2, 2), found.at)
        assertEquals(listOf(DeclarationSite(TextPosition.at(8, 2), FileLine(10))), found.namesakes)
    }

    fun testLastSegmentFindsTheMember() {
        assertEquals(TextPosition.at(2, 2), (located("bar") as StopLocation.OnSymbol).at)
    }

    fun testParameterListsAreDropped() {
        assertEquals(TextPosition.at(2, 2), (located("Foo.bar(x: number)") as StopLocation.OnSymbol).at)
    }

    fun testTopLevelFunction() {
        val found = located("top") as StopLocation.OnSymbol
        assertEquals(TextPosition.at(9, 9), found.at)
        assertEquals(12, found.lastLine.value)
    }

    fun testConstArrowFunction() {
        val found = located("arrow") as StopLocation.OnSymbol
        assertEquals(TextPosition.at(14, 6), found.at)
        assertEquals(17, found.lastLine.value)
    }

    fun testFieldInClass() {
        assertEquals(TextPosition.at(6, 2), (located("Foo.baz") as StopLocation.OnSymbol).at)
    }

    fun testMissingSymbolListsWhatIsDeclared() {
        val miss = (locate("nowhereToBeFound") as StopPlacement.Missed).miss as StopMiss.NoSuchSymbol
        assertEquals("a.ts", miss.path)
        assertContainsElements(miss.known, "Foo", "Foo.bar", "Foo.baz", "top", "arrow")
        assertTrue(StopMiss.describe(miss).contains("Declared there: "))
    }

    fun testUndeclaredNameCalledInTheFileIsFoundByText() {
        val found = located("helperCall") as StopLocation.OnSymbol
        assertEquals(TextPosition.at(10, 2), found.at)
    }

    fun testNameWithAnAfterIsNotTakenFromAMention() {
        val inserted = located("helperCall", after = "top") as StopLocation.Insertion
        assertEquals("top", inserted.after)
        assertEquals(TextPosition.at(12, 1), inserted.at)
    }

    fun testInsertionAfterAMemberGoesAfterItsLastLine() {
        val inserted = located("qux", after = "Foo.bar") as StopLocation.Insertion
        assertEquals(TextPosition.at(4, 3), inserted.at)
    }

    fun testMissingAfterIsReported() {
        val miss = (locate("qux", after = "noSuchAnchor") as StopPlacement.Missed).miss
        assertTrue(miss is StopMiss.NoSuchAfter)
    }

    fun testUnsavedEditsAreWhatIsSearched() {
        typeAtEndOfLine(1, "\n// edited")
        val found = located("top") as StopLocation.OnSymbol
        assertEquals(10, found.at.line.value)
        assertTrue(found.lines.contains("// edited"))
    }

    fun testMissingFileIsNew() {
        val placement = locate("anything", path = "nested/fresh.ts") as StopPlacement.Placed
        assertTrue(placement.location is StopLocation.NewFile)
        assertTrue(placement.location.absolutePath.endsWith("nested/fresh.ts"))
    }

    fun testEmptyFileIsNew() {
        open("empty.ts", "  \n")
        val placement = locate("anything", path = "empty.ts") as StopPlacement.Placed
        assertTrue(placement.location is StopLocation.NewFile)
    }

    fun testOutsideTheRepository() {
        val miss = (locate("x", path = "../elsewhere.ts") as StopPlacement.Missed).miss
        assertEquals(StopMiss.OutsideRepository("../elsewhere.ts"), miss)
    }

    fun testRelativeAndAbsolute() {
        val absolute = (located("top") as StopLocation.OnSymbol).absolutePath
        assertEquals("a.ts", presentation.relative(absolute))
        assertNull(presentation.relative("/definitely/not/in/the/project/a.ts"))
    }

    fun testDirectoryIsUnreadable() {
        myFixture.addFileToProject("dir/inner.ts", "const x = 1")
        val miss = (locate("x", path = "dir") as StopPlacement.Missed).miss
        assertEquals(StopMiss.Unreadable("dir", "it is a directory"), miss)
    }
}
