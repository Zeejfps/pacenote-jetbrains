package dev.diffdino.pairing.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import java.awt.Cursor
import java.awt.event.MouseEvent

/** Where a draft's block hangs against the line it is drawn at. */
internal enum class Hang { AboveLine, BelowLine }

/**
 * A draft's lines and pills as one block inlay in one editor, which hangs where the draft hangs
 * and is taken away when there is nothing left to show.
 */
internal class DraftBlock(
    private val editor: Editor,
    private val commands: (DraftCommand) -> Unit,
    scope: Disposable,
) {
    private val renderer = DraftBlockRenderer(editor)
    private var inlay: Inlay<DraftBlockRenderer>? = null
    private var shownWidth = 0

    init {
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mousePressed(event: EditorMouseEvent) {
                    if (event.mouseEvent.button != MouseEvent.BUTTON1) return
                    val command = commandAt(event) ?: return
                    event.consume()
                    commands(command)
                }

                override fun mouseExited(event: EditorMouseEvent) = hover(null)
            },
            scope,
        )
        editor.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) = hover(commandAt(event))
            },
            scope,
        )
        editor.scrollingModel.addVisibleAreaListener(
            { if (editor.scrollingModel.visibleArea.width != shownWidth) inlay?.update() },
            scope,
        )
        Disposer.register(scope) { remove() }
    }

    val isShowing: Boolean get() = inlay?.isValid == true

    /** Draws [rows] at [offset], or takes the block away for null. */
    fun show(rows: List<GhostRow>?, offset: Int, hang: Hang) {
        if (rows == null) {
            remove()
            return
        }

        renderer.rows = rows
        val current = inlay
        if (current != null && current.isValid) {
            current.update()
        } else {
            val properties = InlayProperties()
                .showAbove(hang == Hang.AboveLine)
                .relatesToPrecedingText(false)
                .priority(BLOCK_PRIORITY)
            inlay = editor.inlayModel.addBlockElement(offset, properties, renderer)
        }

        shownWidth = editor.scrollingModel.visibleArea.width
    }

    private fun commandAt(event: EditorMouseEvent): DraftCommand? {
        val current = inlay?.takeIf { it.isValid } ?: return null
        val bounds = current.bounds ?: return null
        return renderer.pillAt(event.mouseEvent.point, bounds)
    }

    private fun hover(command: DraftCommand?) {
        if (renderer.hovered == command) return
        renderer.hovered = command
        (editor as? EditorEx)?.setCustomCursor(this, command?.let { Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) })
        inlay?.repaint()
    }

    private fun remove() {
        inlay?.let(Disposer::dispose)
        inlay = null
    }

    private companion object {
        const val BLOCK_PRIORITY = 100
    }
}
