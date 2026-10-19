package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftSpan
import dev.diffdino.pairing.model.FileLine
import dev.diffdino.pairing.model.LineSpan
import dev.diffdino.pairing.model.StopDraft
import dev.diffdino.pairing.model.StopLocation

/** How the agent's code for a stop was placed in the stop's file. */
sealed interface DraftPlacement {
    /** Placed at [location]: the stop's own, or the namesake declaration the quoted lines are in. */
    data class Placed(val draft: StopDraft, val location: StopLocation) : DraftPlacement

    data class Refused(val message: String) : DraftPlacement
}

/**
 * Where the agent's code for a stop goes: in place of the stop's declaration, after the one a new
 * declaration follows, or as the first block of a new file, unless the agent quoted the lines it
 * replaces or numbered them. Quoted lines are found in the declaration as whole lines that read the
 * same but for trailing spaces, and must be there once; nothing that only looks like them is taken
 * instead. Lines at either end of a replacement that read the same as the file's are left out of
 * it, so only what changes is lit up, and code that only adds lines goes in as an insertion.
 */
object DraftPlacing {
    /** How many places quoted lines fit are listed, at most. */
    private const val MAX_LISTED = 5

    /** A declaration this long or shorter is shown, numbered, when quoted lines aren't in it. */
    private const val MAX_SHOWN_LINES = 80

    fun place(location: StopLocation, request: DraftRequest): DraftPlacement {
        val code = request.code.replace("\r\n", "\n").trimEnd('\n')
        return when (val span = request.span) {
            DraftSpan.Declaration -> when (location) {
                is StopLocation.NewFile -> placed(code, DraftPlace.Replace(LineSpan(1, 1)), location)
                is StopLocation.OnSymbol -> replacing(code, LineSpan(location.at.line.value, location.lastLine.value), location)
                is StopLocation.Insertion -> placed(code, DraftPlace.InsertAfter(location.at.line), location)
            }
            is DraftSpan.Lines -> when (location) {
                is StopLocation.NewFile -> newFileRefusal
                is StopLocation.OnSymbol, is StopLocation.Insertion -> {
                    val count = fileLines(location).size
                    if (span.from < 1 || span.to < span.from || span.to > count)
                        DraftPlacement.Refused("lines ${span.from}-${span.to} are not in the file, which has $count lines.")
                    else replacing(code, LineSpan(span.from, span.to), location)
                }
            }
            is DraftSpan.After -> when (location) {
                is StopLocation.NewFile -> newFileRefusal
                is StopLocation.OnSymbol, is StopLocation.Insertion -> {
                    val count = fileLines(location).size
                    if (span.line < 1 || span.line > count)
                        DraftPlacement.Refused("after_line ${span.line} is not in the file, which has $count lines.")
                    else placed(code, DraftPlace.InsertAfter(FileLine(span.line)), location)
                }
            }
            is DraftSpan.Quoted -> when (location) {
                is StopLocation.NewFile -> newFileRefusal
                is StopLocation.Insertion -> DraftPlacement.Refused(
                    "replaces quotes lines of a declaration that exists, and this stop is for a new one. Leave replaces " +
                        "out to put code after '${location.after}', or name the declaration that holds the lines as symbol.")
                is StopLocation.OnSymbol -> quoted(code, span.text, location)
            }
        }
    }

    /** [range] of [file], each line with its number, as a reader of the file numbers it. */
    fun numbered(file: List<String>, range: LineSpan): String =
        (maxOf(1, range.from)..minOf(range.to, file.size)).joinToString("\n") { "${it.toString().padStart(6)}\t${file[it - 1]}" }

    private val newFileRefusal =
        DraftPlacement.Refused("The file is new and empty: send its first block as code, without lines, after_line or replaces.")

    // The quoted lines, found in the stop's declaration, or else in the one namesake that holds them.
    private fun quoted(code: String, quote: String, location: StopLocation.OnSymbol): DraftPlacement {
        val wanted = quote.replace("\r\n", "\n").removeSuffix("\n").split('\n')
        if (wanted.all { it.isBlank() }) return DraftPlacement.Refused("replaces is empty: quote the lines your code replaces.")

        val file = location.lines
        val own = LineSpan(location.at.line.value, location.lastLine.value)
        val inOwn = find(file, wanted, own, ::same)
        if (inOwn.size > 1) return ambiguous(inOwn, wanted.size, "the declaration at lines ${own.from}-${own.to}")
        if (inOwn.size == 1) return replacing(code, span(inOwn.single(), wanted.size), location)

        val holders = location.namesakes.mapNotNull { site ->
            val found = find(file, wanted, LineSpan(site.at.line.value, site.lastLine.value), ::same)
            if (found.isEmpty()) null else site to found
        }
        if (holders.size == 1 && holders.single().second.size == 1) {
            val (site, found) = holders.single()
            return replacing(code, span(found.single(), wanted.size), location.on(site))
        }
        if (holders.isNotEmpty()) return ambiguous(holders.flatMap { it.second }, wanted.size, "the declarations of this name")

        return DraftPlacement.Refused(missing(file, wanted, own))
    }

    private fun span(first: Int, size: Int) = LineSpan(first, first + size - 1)

    // The first line of each run within [range] that reads, line for line, as [wanted] does.
    private fun find(file: List<String>, wanted: List<String>, range: LineSpan, equal: (String, String) -> Boolean): List<Int> {
        val last = minOf(range.to, file.size) - wanted.size + 1
        return (maxOf(1, range.from)..last).filter { first -> wanted.indices.all { equal(file[first - 1 + it], wanted[it]) } }
    }

    private fun spans(starts: List<Int>, size: Int): String {
        val listed = starts.take(MAX_LISTED).joinToString(", ") { "lines $it-${it + size - 1}" }
        return if (starts.size > MAX_LISTED) "$listed and ${starts.size - MAX_LISTED} more" else listed
    }

    private fun ambiguous(starts: List<Int>, size: Int, where: String) = DraftPlacement.Refused(
        "replaces fits ${starts.size} places in $where: ${spans(starts, size)}. Quote more of the lines around the ones " +
            "you change, so it fits one place, and keep those lines in code as they are.")

    // Why the quote isn't in the declaration, what in the file comes close, and the declaration as it reads now.
    private fun missing(file: List<String>, wanted: List<String>, own: LineSpan): String {
        val whole = LineSpan(1, file.size)
        val elsewhere = find(file, wanted, whole, ::same)
        val reindented = find(file, wanted, whole) { a, b -> a.trim() == b.trim() }
        val first = wanted.first { it.isNotBlank() }.trim()
        val inside = file.indexOfFirst { first in it && it.trim() != first }
        val hint = when {
            elsewhere.isNotEmpty() ->
                "It is at ${spans(elsewhere, wanted.size)}, outside this declaration: name the declaration that holds it as symbol."
            reindented.isNotEmpty() ->
                "${spans(reindented, wanted.size).replaceFirstChar { it.uppercase() }} read the same but for indentation: " +
                    "quote the lines exactly as they are indented."
            inside >= 0 -> "Line ${inside + 1} holds the first quoted line as part of it: replaces takes whole lines, in full."
            else -> "The file may have changed since you read it: editor_read reads it as the user has it, unsaved edits included."
        }
        val shown = if (own.to - own.from + 1 <= MAX_SHOWN_LINES) "\nThe declaration reads now:\n" + numbered(file, own) else ""
        return "replaces is not in the declaration at lines ${own.from}-${own.to}. $hint$shown"
    }

    private fun placed(code: String, place: DraftPlace, location: StopLocation): DraftPlacement =
        if (code.isEmpty() && place !is DraftPlace.Replace)
            DraftPlacement.Refused("code is empty. Send the code for this stop; an empty code only deletes the lines it replaces.")
        else DraftPlacement.Placed(StopDraft(code, place), location)

    private fun replacing(code: String, span: LineSpan, location: StopLocation): DraftPlacement {
        val file = fileLines(location)
        val draft = if (code.isEmpty()) emptyList() else code.split('\n')
        var from = span.from
        var to = span.to
        var first = 0
        var end = draft.size
        while (from <= to && first < end && same(file[from - 1], draft[first])) {
            from++
            first++
        }

        while (to >= from && end > first && same(file[to - 1], draft[end - 1])) {
            to--
            end--
        }

        if (from > to && first == end)
            return DraftPlacement.Refused("code reads the same as lines ${span.from}-${span.to}: it changes nothing.")

        // Nothing left to replace, and nowhere above to hang an insertion from: keep one line.
        if (from > to && from == 1) {
            to = 1
            end = minOf(draft.size, end + 1)
        }

        val kept = draft.subList(first, end).joinToString("\n")
        return if (from > to) placed(kept, DraftPlace.InsertAfter(FileLine(to)), location)
        else placed(kept, DraftPlace.Replace(LineSpan(from, to)), location)
    }

    private fun same(fileLine: String, draftLine: String): Boolean = fileLine.trimEnd() == draftLine.trimEnd()

    private fun fileLines(location: StopLocation): List<String> = when (location) {
        is StopLocation.OnSymbol -> location.lines
        is StopLocation.Insertion -> location.lines
        is StopLocation.NewFile -> emptyList()
    }
}
