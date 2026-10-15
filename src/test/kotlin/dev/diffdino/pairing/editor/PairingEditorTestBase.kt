package dev.diffdino.pairing.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.PairingPresentation
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopPlacement
import dev.diffdino.pairing.model.StopTarget
import dev.diffdino.pairing.model.SuggestionActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

internal abstract class PairingEditorTestBase : BasePlatformTestCase() {
    protected val sample = """
        export class Foo {
          bar(x: number): number {
            return x + 1
          }

          baz = 3
        }

        function top() {
          helperCall(1)
          return 1
        }

        const arrow = () => {
          const inner = 1
          return inner
        }
    """.trimIndent()

    override fun tearDown() {
        try {
            presentation.clearDraft()
            editorService.spotlights.clear()
        } finally {
            super.tearDown()
        }
    }

    internal val editorService: PairingEditorService get() = PairingEditorService.getInstance(project)

    protected val presentation: PairingPresentation get() = editorService.presentation

    protected fun open(name: String = "a.ts", text: String = sample): PsiFile = myFixture.configureByText(name, text)

    protected val editor: Editor get() = myFixture.editor

    protected val text: String get() = editor.document.text

    // Suspend calls go through a worker so the test thread can keep dispatching what the call posts to the UI thread.
    protected fun <T> await(block: suspend () -> T): T {
        val result = CompletableFuture<T>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }

        return PlatformTestUtil.waitForFuture(result, 60_000)
    }

    protected fun locate(symbol: String, after: String? = null, path: String = "a.ts"): StopPlacement =
        await { presentation.locate(StopTarget(path, symbol, after)) }

    protected fun located(symbol: String, after: String? = null, path: String = "a.ts"): StopLocation =
        (locate(symbol, after, path) as StopPlacement.Placed).location

    protected val noActions = SuggestionActions({}, {}, {})

    protected fun insertAfter(line: Int, code: String) = StopDraft(code, DraftPlace.InsertAfter(FileLine(line)))

    internal fun blocks(): List<DraftBlockRenderer> =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .map { it.renderer }.filterIsInstance<DraftBlockRenderer>()

    protected fun typeAtEndOfLine(line: Int, content: String) {
        val document = editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineEndOffset(line - 1), content)
        }
        flush()
    }

    protected fun undo() {
        val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
        UndoManager.getInstance(project).undo(fileEditor)
        flush()
        flush()
    }

    protected fun flush() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    protected fun highlighters(): List<RangeHighlighter> =
        DocumentMarkupModel.forDocument(editor.document, project, false)?.allHighlighters?.toList().orEmpty()

    protected fun waitUntil(what: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for $what", condition, 30)
}
