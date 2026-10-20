package dev.pacenote.pairing

import dev.pacenote.model.SnapshotResult
import dev.pacenote.model.TreeSnapshot
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Captures the working tree as a git tree and diffs two captures, so the diff of a stop is the
 * user's edit and nothing that was there before it. Written through a throwaway index, so the
 * user's staging area is never touched; the blobs land in the object store unreferenced, where
 * git's own collection removes them. Blocking: call off the EDT.
 */
class WorkingTreeSnapshots(private val git: String = "git") {
    fun capture(repoPath: Path): SnapshotResult<TreeSnapshot> {
        val indexPath = Path.of(System.getProperty("java.io.tmpdir"), "pacenote-pairing-${UUID.randomUUID().toString().replace("-", "")}.index")
        try {
            // Starting from a copy of the real index keeps its stat cache, so `add` only rehashes
            // what changed.
            val located = run(repoPath, listOf("rev-parse", "--git-path", "index"))
            if (located.ok) {
                var real = Path.of(located.stdout.trim())
                if (!real.isAbsolute) real = repoPath.resolve(real)
                if (Files.isRegularFile(real)) Files.copy(real, indexPath)
            }

            val withIndex = mapOf("GIT_INDEX_FILE" to indexPath.toString())
            val added = run(repoPath, listOf("add", "-A", "--", "."), withIndex)
            if (!added.ok) return SnapshotResult.Failed(added.firstLineError("git add"))
            val written = run(repoPath, listOf("write-tree"), withIndex)
            if (!written.ok) return SnapshotResult.Failed(written.firstLineError("git write-tree"))
            return SnapshotResult.Ok(TreeSnapshot(written.stdout.trim()))
        } catch (e: IOException) {
            return SnapshotResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            tryDelete(indexPath)
            tryDelete(Path.of("$indexPath.lock"))
        }
    }

    /** The unified diff from [from] to [to]; empty when nothing changed. */
    fun diff(repoPath: Path, from: TreeSnapshot, to: TreeSnapshot): SnapshotResult<String> {
        if (from == to) return SnapshotResult.Ok("")
        val diff = try {
            run(repoPath, listOf("diff", "--no-color", "--no-ext-diff", "--find-renames", from.treeId, to.treeId))
        } catch (e: IOException) {
            return SnapshotResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (!diff.ok) return SnapshotResult.Failed(diff.firstLineError("git diff"))
        var text = diff.stdout
        if (text.length > MAX_DIFF_CHARS) text = text.substring(0, MAX_DIFF_CHARS) + "\n… diff cut at $MAX_DIFF_CHARS characters."
        return SnapshotResult.Ok(text)
    }

    private fun tryDelete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
        }
    }

    private class GitRun(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        fun firstLineError(what: String): String {
            val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            return if (line == null) "$what failed with exit code $exitCode." else "$what failed: $line"
        }
    }

    private fun run(directory: Path, args: List<String>, environment: Map<String, String> = emptyMap()): GitRun {
        val builder = ProcessBuilder(listOf(git, "-c", "core.quotepath=false") + args).directory(directory.toFile())
        builder.environment().putAll(environment)
        builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
        val process = builder.start()
        process.outputStream.close()
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(StandardCharsets.UTF_8) }
        val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val exit = process.waitFor()
        return GitRun(exit, stdout, stderr.join())
    }

    companion object {
        /** A diff past this many characters is cut, with a note saying so. */
        const val MAX_DIFF_CHARS = 60_000
    }
}
