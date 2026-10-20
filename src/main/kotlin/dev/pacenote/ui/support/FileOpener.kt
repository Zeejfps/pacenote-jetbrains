package dev.pacenote.ui.support

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/** Opens files of the project in the editor. */
object FileOpener {
    /** Opens [absolutePath] at the 1-based [line]; false when there is no such file. */
    fun open(project: Project, absolutePath: String, line: Int): Boolean {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath.replace('\\', '/')) ?: return false
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }
}
