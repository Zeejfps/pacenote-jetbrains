package dev.diffdino.pairing.editor

/** Which lines of a draft are still to be typed, and the last line of the typed ones. */
internal data class GhostLines(val lastTyped: Int, val remaining: List<Int>)

/** Which lines of a draft the reader has not typed yet. */
internal object GhostMatch {
    /**
     * [anchor] is the file line the draft hangs from (0 for the top of the file), [below] the line that
     * followed it when the draft was laid, wherever edits have moved it since: only the lines between
     * the two are the reader's. [line] reads a file line, 1-based.
     */
    fun remaining(draft: List<String>, anchor: Int, below: Int, lineCount: Int, line: (Int) -> String): GhostLines {
        // Lines already in the file are never taken for typed ones, however much they read like the
        // draft: a closing tag or a `return (` further down is the file's own.
        val lastTyped = (below - 1).coerceIn(anchor, maxOf(anchor, lineCount))
        val typed = HashMap<String, Int>()
        for (i in anchor + 1..lastTyped) {
            val text = line(i).trim()
            if (text.isEmpty()) continue
            typed.merge(text, 1, Int::plus)
        }

        val remaining = ArrayList<Int>(draft.size)
        for (i in draft.indices) {
            val key = draft[i].trim()
            val count = typed[key]
            if (key.isNotEmpty() && count != null && count > 0) {
                typed[key] = count - 1
                continue
            }

            remaining.add(i)
        }

        while (remaining.isNotEmpty() && draft[remaining.first()].isBlank()) remaining.removeAt(0)
        if (remaining.all { draft[it].isBlank() }) remaining.clear()
        return GhostLines(lastTyped, remaining)
    }
}
