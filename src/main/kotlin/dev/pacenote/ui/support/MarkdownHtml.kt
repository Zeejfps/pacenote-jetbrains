package dev.pacenote.ui.support

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.net.URI

/**
 * The agent's markdown as the HTML the chat shows. Markup the agent writes as HTML, and images,
 * are shown as the text they were written as: nothing in a reply loads or injects anything.
 */
object MarkdownHtml {
    private val flavour = SafeFlavour()

    fun render(markdown: String): String {
        val text: CharSequence = markdown
        val tree = MarkdownParser(flavour, true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(text)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }

    /** [text] as it is written, line breaks and runs of spaces kept, in the colour [hexColor] where given. */
    fun plain(text: String, hexColor: String? = null, small: Boolean = false, bold: Boolean = false, struck: Boolean = false): String {
        val escaped = escape(text).replace("\r\n", "\n").replace("\n", "<br>").replace("  ", "&nbsp; ")
        val strong = if (bold) "<b>$escaped</b>" else escaped
        val weighted = if (struck) "<s>$strong</s>" else strong
        val sized = if (small) "<small>$weighted</small>" else weighted
        val body = if (hexColor == null) sized else "<span style=\"color:#$hexColor\">$sized</span>"
        return "<html><body>$body</body></html>"
    }

    private class SafeFlavour : GFMFlavourDescriptor() {
        override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> =
            super.createHtmlGeneratingProviders(linkMap, baseURI) + mapOf(
                MarkdownElementTypes.HTML_BLOCK to Verbatim(block = true),
                MarkdownTokenTypes.HTML_TAG to Verbatim(block = false),
                MarkdownElementTypes.IMAGE to Verbatim(block = false),
            )
    }

    private class Verbatim(private val block: Boolean) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val escaped = escape(node.getTextInNode(text).toString())
            visitor.consumeHtml(if (block) "<p>$escaped</p>" else escaped)
        }
    }

    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
