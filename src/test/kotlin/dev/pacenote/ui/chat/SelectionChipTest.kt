package dev.pacenote.ui.chat

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pacenote.model.FileLine
import dev.pacenote.ui.action.SelectedCode
import dev.pacenote.ui.support.Lifetime
import java.nio.file.Files

/** The chip follows the selection of the selected editor, and a selection left out stays out until a different one is made. */
class SelectionChipTest : BasePlatformTestCase() {
    private lateinit var chip: SelectionChip

    override fun setUp() {
        super.setUp()
        val dir = Files.createTempDirectory("pacenote-chip")
        val file = dir.resolve("Main.kt")
        Files.writeString(file, "fun a() {}\nfun b() {}\nfun c() {}\n")
        myFixture.openFileInEditor(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!)
        val lifetime = Lifetime(testRootDisposable)
        chip = SelectionChip(project, dir, SelectedCode(project, lifetime), lifetime)
    }

    private fun select(start: Int, end: Int) {
        myFixture.editor.selectionModel.setSelection(start, end)
        repeat(3) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    }

    fun testNothingSelectedAttachesNothingAndHidesTheChip() {
        assertNull(chip.attached)
        assertFalse(chip.isVisible)
    }

    fun testASelectionIsAttachedWithItsLines() {
        select(11, 32)

        val quote = chip.attached!!
        assertEquals(FileLine(2), quote.start)
        assertEquals(FileLine(3), quote.end)
        assertTrue(chip.isVisible)
    }

    fun testADismissedSelectionStaysOutUntilADifferentOneIsMade() {
        select(11, 32)
        chip.dismiss()
        assertNull(chip.attached)
        assertFalse(chip.isVisible)

        select(0, 10)

        assertEquals(FileLine(1), chip.attached!!.start)
    }

    fun testClearingTheSelectionLetsTheSameOneBackIn() {
        select(11, 32)
        chip.dismiss()

        myFixture.editor.selectionModel.removeSelection()
        repeat(3) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
        select(11, 32)

        assertEquals(FileLine(2), chip.attached!!.start)
    }
}
