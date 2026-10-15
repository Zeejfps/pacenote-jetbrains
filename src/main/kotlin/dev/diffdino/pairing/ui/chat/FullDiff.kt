package dev.diffdino.pairing.ui.chat

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import dev.diffdino.pairing.acp.AcpFileEdit
import dev.diffdino.pairing.ui.support.DiffDinoBundle

/** One edit as the diff viewer takes it: the text before and after, for the file it is in. */
data class FileDiff(val path: String, val before: String, val after: String) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}

/** Opens an edit the agent asks about in the IDE's diff viewer. */
object FullDiff {
    /** An edit with no old text writes a whole file: it is shown against nothing. */
    fun diffs(edits: List<AcpFileEdit>): List<FileDiff> = edits.map { FileDiff(it.path, it.oldText.orEmpty(), it.newText) }

    fun show(project: Project, diffs: List<FileDiff>) {
        if (diffs.isEmpty()) return
        val contents = DiffContentFactory.getInstance()
        val types = FileTypeManager.getInstance()
        val before = DiffDinoBundle.message("diff.before")
        val after = DiffDinoBundle.message("diff.after")
        val requests = diffs.map { diff ->
            val type = types.getFileTypeByFileName(diff.name)
            SimpleDiffRequest(diff.path, contents.create(project, diff.before, type), contents.create(project, diff.after, type), before, after)
        }
        val manager = DiffManager.getInstance()
        if (requests.size == 1) manager.showDiff(project, requests[0])
        else manager.showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
    }
}
