package dev.pacenote.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlTest {
    @Test
    fun rendersEmphasisAndLists() {
        val html = MarkdownHtml.render("Some **bold** text\n\n- one\n- two")
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<li>one</li>"))
    }

    @Test
    fun rendersFencedCodeWithItsLanguage() {
        val html = MarkdownHtml.render("```kotlin\nval x = 1 < 2\n```")
        assertTrue(html, html.contains("<pre>"))
        assertTrue(html, html.contains("<code"))
        assertTrue(html, html.contains("val x = 1 &lt; 2"))
    }

    @Test
    fun showsRawHtmlAsText() {
        val html = MarkdownHtml.render("Before <script>alert(1)</script> after\n\n<div onclick=\"x()\">block</div>")
        assertFalse(html, html.contains("<script"))
        assertFalse(html, html.contains("<div"))
        assertTrue(html, html.contains("&lt;script&gt;"))
    }

    @Test
    fun showsImagesAsText() {
        val html = MarkdownHtml.render("![logo](http://example.com/logo.png)")
        assertFalse(html, html.contains("<img"))
        assertTrue(html, html.contains("logo"))
    }

    @Test
    fun keepsLinks() {
        val html = MarkdownHtml.render("See [the docs](https://example.com/docs).")
        assertTrue(html, html.contains("<a href=\"https://example.com/docs\">the docs</a>"))
    }

    @Test
    fun plainTextKeepsLineBreaksAndEscapes() {
        val html = MarkdownHtml.plain("a < b\nsecond  line")
        assertTrue(html, html.contains("a &lt; b<br>second&nbsp; line"))
    }

    @Test
    fun plainTextCanBeColouredBoldAndStruck() {
        assertEquals(
            "<html><body><span style=\"color:#ff0000\"><small><s><b>x</b></s></small></span></body></html>",
            MarkdownHtml.plain("x", "ff0000", small = true, bold = true, struck = true),
        )
    }
}
