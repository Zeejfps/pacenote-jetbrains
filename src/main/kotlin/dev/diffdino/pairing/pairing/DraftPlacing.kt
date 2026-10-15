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
    data class Placed(val draft: StopDraft) : DraftPlacement

    data class Refused(val message: String) : DraftPlacement
}

/**
 * Where the agent's code for a stop goes: in place of the stop's declaration, after the one a new
 * declaration follows, or as the first block of a new file, unless the agent named the lines
 * itself. Lines at either end of a replacement that read the same as the file's are left out of
 * it, so only what changes is lit up, and code that only adds lines goes in as an insertion.
 */
object DraftPlacing {
    fun place(location: StopLocation, request: DraftRequest): DraftPlacement {
        val code = request.code.replace("\r\n", "\n").trimEnd('\n')
        return when (val span = request.span) {
            DraftSpan.Declaration -> when (location) {
                is StopLocation.NewFile -> placed(code, DraftPlace.Replace(LineSpan(1, 1)))
                is StopLocation.OnSymbol -> replacing(code, LineSpan(location.at.line.value, location.lastLine.value), location.lines)
                is StopLocation.Insertion -> placed(code, DraftPlace.InsertAfter(location.at.line))
            }
            is DraftSpan.Lines -> when (location) {
                is StopLocation.NewFile -> newFileRefusal
                is StopLocation.OnSymbol, is StopLocation.Insertion -> {
                    val count = fileLines(location).size
                    if (span.from < 1 || span.to < span.from || span.to > count)
                        DraftPlacement.Refused("lines ${span.from}-${span.to} are not in the file, which has $count lines.")
                    else replacing(code, LineSpan(span.from, span.to), fileLines(location))
                }
            }
            is DraftSpan.After -> when (location) {
                is StopLocation.NewFile -> newFileRefusal
                is StopLocation.OnSymbol, is StopLocation.Insertion -> {
                    val count = fileLines(location).size
                    if (span.line < 1 || span.line > count)
                        DraftPlacement.Refused("after_line ${span.line} is not in the file, which has $count lines.")
                    else placed(code, DraftPlace.InsertAfter(FileLine(span.line)))
                }
            }
        }
    }

    private val newFileRefusal =
        DraftPlacement.Refused("The file is new and empty: send its first block as code, without lines or after_line.")

    private fun placed(code: String, place: DraftPlace): DraftPlacement =
        if (code.isEmpty() && place !is DraftPlace.Replace)
            DraftPlacement.Refused("code is empty. Send the code for this stop; an empty code only deletes the lines it replaces.")
        else DraftPlacement.Placed(StopDraft(code, place))

    private fun replacing(code: String, span: LineSpan, file: List<String>): DraftPlacement {
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
        return if (from > to) placed(kept, DraftPlace.InsertAfter(FileLine(to)))
        else placed(kept, DraftPlace.Replace(LineSpan(from, to)))
    }

    private fun same(fileLine: String, draftLine: String): Boolean = fileLine.trimEnd() == draftLine.trimEnd()

    private fun fileLines(location: StopLocation): List<String> = when (location) {
        is StopLocation.OnSymbol -> location.lines
        is StopLocation.Insertion -> location.lines
        is StopLocation.NewFile -> emptyList()
    }
}
