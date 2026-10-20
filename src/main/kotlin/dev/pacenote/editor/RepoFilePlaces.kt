package dev.pacenote.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.limits.FileSizeLimit
import git4idea.repo.GitRepositoryManager

/**
 * Keeps the agent's repository-relative paths inside the project's git repository and finds the
 * files they name. The repository is the one at the project's base directory, else the one holding
 * it, else the first the project has; with none, the base directory itself.
 */
internal class RepoFilePlaces(private val project: Project) {
    fun root(): VirtualFile? {
        val base = project.guessProjectDir()
        val roots = GitRepositoryManager.getInstance(project).repositories.map { it.root }
        if (base == null) return roots.firstOrNull()
        return roots.firstOrNull { it.path == base.path }
            ?: roots.firstOrNull { FileUtil.isAncestor(it.path, base.path, false) }
            ?: roots.firstOrNull()
            ?: base
    }

    /** The absolute path, with forward slashes, of a repository-relative one; null when it leads out of the repository. */
    fun absolute(relative: String): String? {
        val root = root() ?: return null
        val cleaned = relative.trim().replace('\\', '/').trimStart('/')
        val full = FileUtil.toCanonicalPath("${root.path}/$cleaned")
        return full.takeIf { relative(it) != null }
    }

    /** The repository-relative form, with forward slashes, of an absolute path; null outside the repository. */
    fun relative(absolutePath: String): String? {
        val root = root()?.path ?: return null
        val full = FileUtil.toCanonicalPath(FileUtil.toSystemIndependentName(absolutePath))
        if (!FileUtil.isAncestor(root, full, false)) return null
        return full.substring(root.length).trimStart('/')
    }

    /** The file at the path, or null when it doesn't exist. [refresh] looks on disk for one the file system hasn't seen; never inside a read action. */
    fun fileAt(absolutePath: String, refresh: Boolean): VirtualFile? {
        val system = root()?.fileSystem ?: return null
        return system.findFileByPath(absolutePath) ?: if (refresh) system.refreshAndFindFileByPath(absolutePath) else null
    }

    /** Why a file can't be read as text, or null when it can. */
    fun whyUnreadable(file: VirtualFile): String? = when {
        file.isDirectory -> "it is a directory"
        file.fileType.isBinary -> "it is a binary file"
        FileSizeLimit.isTooLargeForContentLoading(file.length, file.extension) -> "the file is too large to open"
        else -> null
    }
}
