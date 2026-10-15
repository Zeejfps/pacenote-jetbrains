package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.FileCreation
import dev.diffdino.pairing.model.SnapshotResult
import dev.diffdino.pairing.model.TreeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Snapshots through real git: a stop's diff is the edit since its capture, new files included, and
 * the user's staging area is never touched. */
class GitPairingWorkspaceTest {
    private val repo: Path = Files.createTempDirectory("pairing-workspace")
    private val workspace = GitPairingWorkspace(repo, WorkingTreeSnapshots())

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(output, 0, process.waitFor())
        return output
    }

    @Before
    fun setUp() {
        val available = try {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        } catch (_: java.io.IOException) {
            false
        }
        assumeTrue("git is not on PATH", available)
        git("init", "-q")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        Files.writeString(repo.resolve("a.txt"), "one\n")
        git("add", "a.txt")
        git("commit", "-q", "-m", "first")
    }

    @After
    fun tearDown() {
        repo.toFile().deleteRecursively()
    }

    private fun tree(result: SnapshotResult<TreeSnapshot>): TreeSnapshot = when (result) {
        is SnapshotResult.Ok -> result.value
        is SnapshotResult.Failed -> throw AssertionError(result.reason)
    }

    @Test
    fun theDiff_isTheEditSinceTheCapture_newFilesIncluded_andTheIndexStaysAsItWas() = runBlocking {
        Files.writeString(repo.resolve("a.txt"), "one\nstaged\n")
        git("add", "a.txt")
        val staged = git("diff", "--cached")

        val before = tree(workspace.capture())
        Files.writeString(repo.resolve("a.txt"), "one\nstaged\ntwo\n")
        Files.writeString(repo.resolve("b.txt"), "new\n")
        val after = tree(workspace.capture())

        val diff = (workspace.diff(before, after) as SnapshotResult.Ok).value
        assertTrue(diff, diff.contains("+two"))
        assertTrue(diff, diff.contains("+++ b/b.txt"))
        assertFalse(diff, diff.contains("+staged"))
        assertEquals(staged, git("diff", "--cached"))
        assertEquals("", (workspace.diff(after, after) as SnapshotResult.Ok).value)
    }

    @Test
    fun anEmptyFile_isCreatedOnce_andGoesAgainWhenLeftEmpty() = runBlocking {
        assertEquals(FileCreation.Created, workspace.ensureEmptyFile("src/New.kt"))
        assertEquals(FileCreation.AlreadyEmpty, workspace.ensureEmptyFile("src/New.kt"))

        workspace.removeIfEmpty("src/New.kt")

        assertFalse(Files.exists(repo.resolve("src/New.kt")))
    }

    @Test
    fun aFileWithContent_isRefused_andKept() = runBlocking {
        assertTrue(workspace.ensureEmptyFile("a.txt") is FileCreation.Refused)

        workspace.removeIfEmpty("a.txt")

        assertTrue(Files.exists(repo.resolve("a.txt")))
    }

    @Test
    fun aPathOutsideTheProject_isRefused() = runBlocking {
        assertTrue(workspace.ensureEmptyFile("../escape.txt") is FileCreation.Refused)
    }
}
