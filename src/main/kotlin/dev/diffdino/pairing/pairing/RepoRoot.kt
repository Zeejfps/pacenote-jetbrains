package dev.diffdino.pairing.pairing

import java.nio.file.Files
import java.nio.file.Path

/** The working tree a project belongs to: the nearest folder at or above it holding `.git` (a
 * directory, or the file a linked worktree has), or the project folder itself outside git. */
object RepoRoot {
    fun of(projectDir: Path): Path {
        val start = projectDir.toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) } ?: start
    }
}
