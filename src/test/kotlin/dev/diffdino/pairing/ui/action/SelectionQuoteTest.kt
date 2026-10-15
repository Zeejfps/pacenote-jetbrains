package dev.diffdino.pairing.ui.action

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.model.FileLine
import java.nio.file.Files
import java.nio.file.Path

class SelectionQuoteTest : BasePlatformTestCase() {
    /** Opens a real file with [marked] as its text, `[[` and `]]` around what is selected. */
    private fun openWithSelection(name: String, marked: String): Path {
        val start = marked.indexOf("[[")
        val end = marked.indexOf("]]") - 2
        val dir = Files.createTempDirectory("diffdino-selection")
        val file = dir.resolve(name)
        Files.writeString(file, marked.replace("[[", "").replace("]]", ""))
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        myFixture.openFileInEditor(virtual)
        if (start >= 0) myFixture.editor.selectionModel.setSelection(start, end)
        return file
    }

    private fun quoted(result: SelectionQuoteResult): CodeQuote.InFile = (result as SelectionQuoteResult.Quoted).quote

    fun testQuotesTheSelectedLinesOfTheFileWithItsAbsolutePath() {
        val file = openWithSelection("Main.kt", "fun a() {}\n[[fun b() {\n    1\n}]]\nfun c() {}\n")

        val quote = quoted(SelectionQuote.of(myFixture.editor))

        assertEquals(FileLine(2), quote.start)
        assertEquals(FileLine(4), quote.end)
        assertEquals("fun b() {\n    1\n}", quote.text)
        assertTrue(Path.of(quote.absolutePath).isAbsolute)
        assertEquals(file.toRealPath(), Path.of(quote.absolutePath).toRealPath())
    }

    fun testASelectionInsideOneLineQuotesThatLine() {
        openWithSelection("Main.kt", "val x = [[1 + 2]]\n")

        val quote = quoted(SelectionQuote.of(myFixture.editor))

        assertEquals(FileLine(1), quote.start)
        assertEquals(FileLine(1), quote.end)
        assertEquals("1 + 2", quote.text)
    }

    fun testASelectionEndingAtTheStartOfALineLeavesThatLineOut() {
        openWithSelection("Main.kt", "[[one\ntwo\n]]three\n")

        val quote = quoted(SelectionQuote.of(myFixture.editor))

        assertEquals(FileLine(1), quote.start)
        assertEquals(FileLine(2), quote.end)
    }

    fun testNothingSelectedQuotesNothing() {
        openWithSelection("Main.kt", "val x = 1\n")

        assertEquals(SelectionQuoteResult.NoSelection, SelectionQuote.of(myFixture.editor))
    }

    fun testTooManyLinesAreRefusedWithTheirCount() {
        val lines = CodeQuote.MAX_LINES + 5
        val text = (1..lines).joinToString("\n") { "line $it" }
        openWithSelection("Big.txt", "[[$text]]")

        assertEquals(SelectionQuoteResult.TooLong(lines), SelectionQuote.of(myFixture.editor))
    }

    fun testADocumentWithNoFileOnDiskCantBeQuoted() {
        val document = EditorFactory.getInstance().createDocument("abc")

        assertEquals(SelectionQuoteResult.NotAFile, SelectionQuote.of(document, 0, 3, null))
    }
}
