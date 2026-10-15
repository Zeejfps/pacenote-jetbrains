package dev.diffdino.pairing.ui.chat

import dev.diffdino.pairing.acp.AcpFileEdit
import dev.diffdino.pairing.pairing.EditPreviewLine
import org.junit.Assert.assertEquals
import org.junit.Test

class EditPreviewRowsTest {
    @Test
    fun mapsEveryKindOfLineToARow() {
        val rows = EditPreviewRows.of(listOf(
            EditPreviewLine.File("src/A.kt"),
            EditPreviewLine.Context("before"),
            EditPreviewLine.Removed("old"),
            EditPreviewLine.Added("new"),
            EditPreviewLine.Elided(7),
        ))

        assertEquals(listOf(
            PreviewRow.FileHeader("src/A.kt"),
            PreviewRow.Code(PreviewRow.Code.Kind.Context, "before"),
            PreviewRow.Code(PreviewRow.Code.Kind.Removed, "old"),
            PreviewRow.Code(PreviewRow.Code.Kind.Added, "new"),
            PreviewRow.More(7),
        ), rows)
    }

    @Test
    fun marksRemovedAndAddedLines() {
        assertEquals("-", PreviewRow.Code.Kind.Removed.marker)
        assertEquals("+", PreviewRow.Code.Kind.Added.marker)
        assertEquals(" ", PreviewRow.Code.Kind.Context.marker)
    }

    @Test
    fun plainTextIsCutAtTheLimitAndCounted() {
        val rows = EditPreviewRows.plain("a\nb\nc\nd\n", maxLines = 2)

        assertEquals(listOf(
            PreviewRow.Code(PreviewRow.Code.Kind.Context, "a"),
            PreviewRow.Code(PreviewRow.Code.Kind.Context, "b"),
            PreviewRow.More(2),
        ), rows)
    }

    @Test
    fun plainTextWithinTheLimitIsShownWhole() {
        assertEquals(listOf(PreviewRow.Code(PreviewRow.Code.Kind.Context, "git status")), EditPreviewRows.plain("git status", maxLines = 5))
    }

    @Test
    fun fullDiffShowsAWholeFileWriteAgainstNothing() {
        val diffs = FullDiff.diffs(listOf(
            AcpFileEdit("C:\\repo\\src\\Main.kt", null, "fun main() {}"),
            AcpFileEdit("/repo/src/Util.kt", "old", "new"),
        ))

        assertEquals(listOf(
            FileDiff("C:\\repo\\src\\Main.kt", "", "fun main() {}"),
            FileDiff("/repo/src/Util.kt", "old", "new"),
        ), diffs)
        assertEquals(listOf("Main.kt", "Util.kt"), diffs.map { it.name })
    }
}
