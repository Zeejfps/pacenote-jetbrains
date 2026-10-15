package dev.diffdino.pairing.pairing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class RepoRootTest {
    @Test
    fun findsTheWorkingTreeAboveAProjectSubfolder() {
        val repo = Files.createTempDirectory("repo-root")
        Files.createDirectory(repo.resolve(".git"))
        val sub = Files.createDirectories(repo.resolve("web/app"))
        assertEquals(repo.toRealPath(), RepoRoot.of(sub).toRealPath())
    }

    @Test
    fun takesALinkedWorktreesGitFile() {
        val tree = Files.createTempDirectory("worktree")
        Files.writeString(tree.resolve(".git"), "gitdir: elsewhere")
        assertEquals(tree.toRealPath(), RepoRoot.of(tree).toRealPath())
    }

    @Test
    fun fallsBackToTheProjectFolderOutsideGit() {
        val plain = Files.createTempDirectory("plain")
        assertEquals(plain.toAbsolutePath().normalize(), RepoRoot.of(plain))
    }
}
