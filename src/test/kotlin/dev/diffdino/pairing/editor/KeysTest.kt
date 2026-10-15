package dev.diffdino.pairing.editor

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.impl.ActionProcessor
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import com.intellij.testFramework.TestActionEvent
import dev.diffdino.pairing.model.SuggestionActions
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.KeyStroke

internal class KeysTest : PairingEditorTestBase() {
    private val calls = ArrayList<String>()
    private val recording = SuggestionActions({ calls.add("accept") }, { calls.add("acceptAndNext") }, { calls.add("next") })

    override fun setUp() {
        super.setUp()
        open()
    }

    private fun showDraft() {
        presentation.showDraft(located("added", after = "top"), insertAfter(12, "const added = 1"), recording)
        flush()
    }

    private fun action(command: DraftCommand): AnAction = ActionManager.getInstance().getAction(command.actionId)

    private fun event(action: AnAction): AnActionEvent {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    private fun updated(command: DraftCommand): AnActionEvent {
        val action = action(command)
        val event = event(action)
        action.update(event)
        return event
    }

    // What the IDE does with a key: the actions bound to it are tried in turn and the first enabled one runs.
    private fun press(keyCode: Int, modifiers: Int): List<String> {
        val stroke = KeyStroke.getKeyStroke(keyCode, modifiers)
        val performed = ArrayList<String>()
        val dispatcher = IdeKeyEventDispatcher(IdeEventQueue.getInstance())
        dispatcher.updateCurrentContext(editor.contentComponent, KeyboardShortcut(stroke, null))
        dispatcher.context.dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
        dispatcher.context.project = project
        val key = KeyEvent(editor.contentComponent, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, '\n')
        dispatcher.processAction(
            key,
            object : ActionProcessor() {
                override fun performAction(inputEvent: InputEvent, action: AnAction, event: AnActionEvent) {
                    performed.add(ActionManager.getInstance().getId(action) ?: action.javaClass.simpleName)
                }
            },
        )
        return performed
    }

    fun testActionsAreRegisteredWithTheirKeys() {
        val keymap = KeymapManager.getInstance().getKeymap("\$default")!!
        fun ids(modifiers: Int) = keymap.getActionIds(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifiers)).toList()
        assertTrue(ids(InputEvent.CTRL_DOWN_MASK).contains("DiffDino.AcceptDraftAndNext"))
        assertTrue(ids(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK).contains("DiffDino.AcceptDraft"))
        assertTrue(ids(InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK).contains("DiffDino.NextStop"))
        val mac = KeymapManager.getInstance().getKeymap("Mac OS X 10.5+")
        if (mac != null) {
            val meta = mac.getActionIds(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)).toList()
            assertTrue(meta.contains("DiffDino.AcceptDraftAndNext"))
        }
    }

    fun testActionsAreOffWithoutADraft() {
        for (command in DraftCommand.entries) {
            val event = updated(command)
            assertFalse(command.name, event.presentation.isEnabled)
            assertFalse(command.name, event.presentation.isVisible)
        }
    }

    fun testActionsAreOnWhileTheEditorShowsADraft() {
        showDraft()
        for (command in DraftCommand.entries) assertTrue(command.name, updated(command).presentation.isEnabled)
        presentation.clearDraft()
        for (command in DraftCommand.entries) assertFalse(command.name, updated(command).presentation.isEnabled)
    }

    fun testActionsRunTheDraftsCallbacks() {
        showDraft()
        for (command in DraftCommand.entries) action(command).actionPerformed(event(action(command)))
        assertEquals(listOf("accept", "acceptAndNext", "next"), calls)
    }

    fun testWithoutADraftTheIdesOwnBindingKeepsTheKey() {
        val performed = press(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        assertFalse(performed.toString(), performed.contains("DiffDino.AcceptDraftAndNext"))
        assertTrue(performed.toString(), performed.isNotEmpty())
    }

    fun testWithADraftOurActionTakesTheKey() {
        showDraft()
        assertEquals("DiffDino.AcceptDraftAndNext", press(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK).firstOrNull())
    }

    fun testDraftHasAcceptPillsWhoseTooltipsNameTheKeys() {
        showDraft()
        val renderer = blocks().single()
        val bounds = Rectangle(0, 0, 3000, renderer.rows.size * editor.lineHeight)
        val lastRow = (renderer.rows.size - 1) * editor.lineHeight + editor.lineHeight / 2
        val hits = (0 until 3000).mapNotNull { x -> renderer.pillAt(java.awt.Point(x, lastRow), bounds) }.distinct()
        assertEquals(listOf(DraftCommand.Accept, DraftCommand.AcceptAndNext), hits)
        val keys = com.intellij.openapi.keymap.KeymapUtil.getFirstKeyboardShortcutText(DraftCommand.Accept.actionId)
        assertTrue(keys.isNotEmpty())
        assertEquals("Accept ($keys)", DraftCommand.Accept.tooltip)
    }

    fun testBlockPaintsWithoutError() {
        showDraft()
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val image = BufferedImage(900, 200, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            val region = Rectangle(0, 0, inlay.renderer.calcWidthInPixels(inlay), inlay.renderer.calcHeightInPixels(inlay))
            inlay.renderer.paint(inlay, g as java.awt.Graphics, region, com.intellij.openapi.editor.markup.TextAttributes())
            assertEquals((inlay.renderer as DraftBlockRenderer).rows.size * editor.lineHeight, region.height)
        } finally {
            g.dispose()
        }
    }
}
