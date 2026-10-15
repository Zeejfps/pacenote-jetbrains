package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.EditorCaret
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.PairingPresentation
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopMiss
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.SuggestionActions
import dev.diffdino.pairing.model.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The pairing loop over the IDE's editors: a stop is found in the file as the user has it, unsaved
 * edits included, through the IDE's own code model, and shown by opening the file in this project.
 * Never switches projects. [scope] ends everything it listens to and draws.
 */
internal class EditorPairingPresentation(private val project: Project, private val scope: Disposable) : PairingPresentation {
    private val places = RepoFilePlaces(project)
    private val caretState = MutableStateFlow<EditorCaret?>(null)
    private val needsState = MutableStateFlow<List<String>>(emptyList())
    private var shown: ShownDraft? = null

    override val caret: StateFlow<EditorCaret?> get() = caretState

    override val draftNeeds: StateFlow<List<String>> get() = needsState

    init {
        followCaret()
    }

    /** The draft drawn in [editor], if there is one. */
    fun draftIn(editor: Editor): ShownDraft? = shown?.takeIf { it.isShownIn(editor) }

    override suspend fun locate(target: StopTarget): StopPlacement = withContext(Dispatchers.Default) {
        val absolute = places.absolute(target.path)
            ?: return@withContext StopPlacement.Missed(StopMiss.OutsideRepository(target.path))
        val file = places.fileAt(absolute, refresh = true)
            ?: return@withContext StopResolver.resolve(absolute, null, null, target)
        unreadable(file)?.let { return@withContext StopPlacement.Missed(StopMiss.Unreadable(target.path, it)) }

        readAction {
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@readAction StopPlacement.Missed(StopMiss.Unreadable(target.path, "it has no text"))
            val outline = com.intellij.psi.PsiManager.getInstance(project).findFile(file)?.let { DeclarationOutline.of(it, document) }
            StopResolver.resolve(absolute, document.text, outline, target)
        }
    }

    private fun unreadable(file: com.intellij.openapi.vfs.VirtualFile): String? = when {
        file.isDirectory -> "it is a directory"
        FileUtilRt.isTooLarge(file.length) -> "the file is too large to open"
        file.fileType.isBinary -> "it is a binary file"
        else -> null
    }

    override suspend fun readText(location: StopLocation): String? = readAction {
        places.fileAt(location.absolutePath, refresh = false)?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
    }

    override fun showDraft(location: StopLocation, draft: StopDraft, actions: SuggestionActions) {
        clearDraft()
        val file = places.fileAt(location.absolutePath, refresh = false) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        shown = ShownDraft(project, file, document, location, draft, actions, scope) { needsState.value = it }
    }

    override fun clearDraft() {
        shown?.let(Disposer::dispose)
        shown = null
        needsState.value = emptyList()
    }

    override suspend fun takeDraft(location: StopLocation): Boolean = withContext(Dispatchers.EDT) {
        val current = shown ?: return@withContext false
        if (!FileUtil.pathsEqual(current.path, location.absolutePath)) return@withContext false
        val taken = current.take()
        if (taken) clearDraft()
        taken
    }

    override fun reveal(location: StopLocation) {
        when (location) {
            is StopLocation.OnSymbol -> open(location.absolutePath, location.at)
            is StopLocation.Insertion -> open(location.absolutePath, location.at)
            is StopLocation.NewFile -> open(location.absolutePath, TextPosition.at(1, 0))
        }
    }

    override fun revealDraft(location: StopLocation, draft: StopDraft) {
        when (val place = draft.place) {
            is DraftPlace.Replace -> open(location.absolutePath, TextPosition.at(place.lines.from, 0))
            is DraftPlace.InsertAfter -> open(location.absolutePath, TextPosition(FileLine(maxOf(1, place.line.value)), Int.MAX_VALUE))
        }
    }

    // Opens the file in this project's window and puts the caret in the middle of the screen.
    private fun open(absolutePath: String, at: TextPosition) {
        val file = places.fileAt(absolutePath, refresh = false) ?: return
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true) ?: return
        editor.caretModel.moveToOffset(editor.document.offsetOf(at))
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    override fun saveUnsaved(): List<String> {
        val manager = FileDocumentManager.getInstance()
        val index = ProjectFileIndex.getInstance(project)
        val failed = ArrayList<String>()
        for (document in manager.unsavedDocuments) {
            val file = manager.getFile(document) ?: continue
            val relative = places.relative(file.path)
            if (relative == null && !index.isInContent(file)) continue
            manager.saveDocument(document)
            if (manager.isDocumentUnsaved(document)) failed.add(relative ?: file.path)
        }

        return failed
    }

    override fun relative(absolutePath: String): String? = places.relative(absolutePath)

    private fun followCaret() {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = editorMoved(event.editor)
            },
            scope,
        )
        multicaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) = editorMoved(e.editor)
            },
            scope,
        )
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = showCaretOfSelectedEditor()
            },
        )
        showCaretOfSelectedEditor()
    }

    private fun editorMoved(editor: Editor) {
        if (editor.project !== project) return
        val selected = FileEditorManager.getInstance(project).selectedTextEditor
        if (selected == null || selected === editor) caretState.value = caretOf(editor)
    }

    private fun showCaretOfSelectedEditor() {
        caretState.value = FileEditorManager.getInstance(project).selectedTextEditor?.let(::caretOf)
    }

    private fun caretOf(editor: Editor): EditorCaret? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val path = places.relative(file.path) ?: return null
        return EditorCaret(
            path,
            editor.document.positionOf(editor.caretModel.primaryCaret.offset),
            editor.selectionModel.selectedText.orEmpty(),
        )
    }
}
