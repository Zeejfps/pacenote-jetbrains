package dev.pacenote.editor

/** The lines of the agent's code as they are drawn and typed: no trailing line break, none for empty code. */
internal fun draftLinesOf(code: String): List<String> =
    if (code.isEmpty()) emptyList() else code.replace("\r\n", "\n").trimEnd('\n').split('\n')
