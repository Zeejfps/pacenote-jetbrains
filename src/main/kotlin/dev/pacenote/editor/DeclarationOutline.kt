package dev.pacenote.editor

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import dev.pacenote.model.FileLine
import dev.pacenote.model.TextPosition

/** A declaration in a file: where its name is, the last line it reaches, and the declarations inside it. */
internal data class OutlineNode(
    val name: String,
    val nameAt: TextPosition,
    val endLine: FileLine,
    val children: List<OutlineNode>,
)

/**
 * The declarations of a file in whatever language the IDE knows it in, nested the way the language's
 * own structure view nests them; for a language without a structure view, every named PSI element.
 */
internal object DeclarationOutline {
    private val log = Logger.getInstance(DeclarationOutline::class.java)

    /** The outline, or null when the file has no declarations the IDE can see. Needs a read action. */
    fun of(file: PsiFile, document: Document): List<OutlineNode>? {
        val nodes = fromStructureView(file, document).ifEmpty { psiNodes(file, document) }
        return nodes.takeIf { it.isNotEmpty() }
    }

    private fun fromStructureView(file: PsiFile, document: Document): List<OutlineNode> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: return emptyList()
        return try {
            val model = builder.createStructureViewModel(null)
            try {
                structureNodes(model.root.children.filterIsInstance<StructureViewTreeElement>(), file, document)
            } finally {
                Disposer.dispose(model)
            }
        } catch (e: IndexNotReadyException) {
            emptyList()
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            log.warn("Structure view of ${file.name} failed", e)
            emptyList()
        }
    }

    private fun structureNodes(elements: List<StructureViewTreeElement>, file: PsiFile, document: Document): List<OutlineNode> =
        elements.flatMap { element ->
            val children = structureNodes(element.children.filterIsInstance<StructureViewTreeElement>(), file, document)
            val psi = element.value as? PsiElement
            val node = psi?.takeIf { it.containingFile == file }?.let { node(it, children, document) }
            if (node != null) listOf(node) else children
        }

    private fun psiNodes(parent: PsiElement, document: Document): List<OutlineNode> =
        generateSequence(parent.firstChild) { it.nextSibling }.flatMap { child ->
            val inside = psiNodes(child, document)
            val node = if (child is PsiNameIdentifierOwner) node(child, inside, document) else null
            if (node != null) sequenceOf(node) else inside.asSequence()
        }.toList()

    private fun node(element: PsiElement, children: List<OutlineNode>, document: Document): OutlineNode? {
        val (name, nameOffset) = declaredName(element) ?: return null
        val range = element.textRange
        val lastOffset = maxOf(range.startOffset, range.endOffset - 1)
        return OutlineNode(name, document.positionOf(nameOffset), document.lineOf(lastOffset), children)
    }

    // A function expression is named by what it is assigned to: the name lives on an owner just
    // above it, a short run of type and `=` before it.
    private fun declaredName(element: PsiElement): Pair<String, Int>? {
        val owners = generateSequence(element) { it.parent }.take(3)
        val fileText = element.containingFile?.text.orEmpty()
        for (owner in owners) {
            if (owner !is PsiNameIdentifierOwner) continue
            val identifier = owner.nameIdentifier ?: continue
            val name = owner.name?.takeIf { it.isNotBlank() } ?: continue
            if (owner !== element && !assignedTo(fileText, identifier.textRange.endOffset, element.textRange.startOffset)) continue
            return name to identifier.textRange.startOffset
        }

        val name = (element as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() } ?: return null
        val at = element.text.indexOf(name).takeIf { it >= 0 } ?: return null
        return name to element.textRange.startOffset + at
    }

    private fun assignedTo(text: String, nameEnd: Int, valueStart: Int): Boolean {
        if (valueStart < nameEnd || valueStart - nameEnd > MAX_ASSIGNMENT_GAP || valueStart > text.length) return false
        return text.substring(nameEnd, valueStart).none { it == '{' || it == '}' || it == ';' }
    }

    private const val MAX_ASSIGNMENT_GAP = 80
}
