package dev.diffdino.pairing.editor

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea

/** The draft wears the colours the file's own highlighting gave the same names, not just the lexer's. */
internal class NameColorsTest : PairingEditorTestBase() {
    override fun setUp() {
        super.setUp()
        open()
    }

    private fun show(code: String) {
        presentation.showDraft(located("added", after = "top"), insertAfter(12, code), noActions)
        flush()
    }

    // As the IDE's highlighting would: the [nth] use of [name] in the file, coloured by [key].
    private fun highlight(name: String, key: TextAttributesKey, nth: Int = 0) {
        var at = -1
        repeat(nth + 1) { at = text.indexOf(name, at + 1) }
        DocumentMarkupModel.forDocument(editor.document, project, true)
            .addRangeHighlighter(key, at, at + name.length, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE)
        flush()
    }

    private fun keysOf(word: String): List<TextAttributesKey>? {
        val row = blocks().single().rows.single()
        val start = row.text.indexOf(word)
        return row.runs.firstOrNull { it.start == start && it.end == start + word.length }?.keys
    }

    private fun waitForKey(word: String, key: TextAttributesKey) =
        waitUntil("$word coloured") { keysOf(word)?.contains(key) == true }

    fun testANameTheFileColoursIsColouredTheSameInTheDraft() {
        highlight("helperCall", DefaultLanguageHighlighterColors.FUNCTION_CALL)

        show("const y = helperCall(2)")

        waitForKey("helperCall", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    }

    fun testTheDraftIsColouredAgainWhenTheFilesHighlightingComesIn() {
        show("const y = helperCall(2)")
        waitUntil("the lexer's colours") { blocks().single().rows.single().runs.isNotEmpty() }

        highlight("helperCall", DefaultLanguageHighlighterColors.FUNCTION_CALL)

        waitForKey("helperCall", DefaultLanguageHighlighterColors.FUNCTION_CALL)
    }

    fun testANameInsideAStringKeepsTheStringsColour() {
        highlight("helperCall", DefaultLanguageHighlighterColors.FUNCTION_CALL)

        show("const s = \"helperCall\" + top()")
        waitUntil("the lexer's colours") { blocks().single().rows.single().runs.isNotEmpty() }
        flush()

        val row = blocks().single().rows.single()
        assertTrue(row.runs.none { DefaultLanguageHighlighterColors.FUNCTION_CALL in it.keys })
    }

    fun testANameColouredTwoWaysIsToldApartByHowItIsUsed() {
        highlight("x", DefaultLanguageHighlighterColors.PARAMETER, nth = 0)
        highlight("baz", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        runReadAction {
            val names = FileNameColors.of(project, editor.document)
            assertEquals(listOf(DefaultLanguageHighlighterColors.PARAMETER), names.of("x", NameUse.Plain)?.keys)
            assertEquals(listOf(DefaultLanguageHighlighterColors.INSTANCE_FIELD), names.of("baz", NameUse.Member)?.keys)
        }
    }

    fun testHowANameIsUsedIsReadFromTheCharactersAroundIt() {
        val text = "a.b(c) + d(e) + f->g + h::i"
        fun use(name: String) = text.indexOf(name).let { FileNameColors.useAt(text, it, it + name.length) }
        assertEquals(NameUse.MemberCall, use("b"))
        assertEquals(NameUse.Plain, use("c"))
        assertEquals(NameUse.Call, use("d"))
        assertEquals(NameUse.Member, use("g"))
        assertEquals(NameUse.Member, use("i"))
    }

    fun testTheIdesOwnHighlightingIsPickedUp() {
        myFixture.doHighlighting()

        runReadAction {
            val names = FileNameColors.of(project, editor.document)
            for (name in listOf("Foo", "bar", "baz", "top", "inner")) assertNotNull(name, names.of(name, NameUse.Plain))
        }
    }
}
