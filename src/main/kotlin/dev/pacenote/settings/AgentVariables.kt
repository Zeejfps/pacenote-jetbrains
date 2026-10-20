package dev.pacenote.settings

/** How reading a preset's environment as typed came out. */
sealed interface AgentVariablesParse {
    data class Ok(val variables: Map<String, String>) : AgentVariablesParse

    /** [line] is not `NAME=value`. */
    data class Invalid(val line: String) : AgentVariablesParse
}

/**
 * A preset's environment between the text the user types and the variables the agent gets: one
 * `NAME=value` a line, blank lines and `#` comments skipped, the value taken as typed.
 */
object AgentVariables {
    private val name = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String): AgentVariablesParse {
        val variables = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val equals = line.indexOf('=')
            if (equals <= 0) return AgentVariablesParse.Invalid(line)
            val key = line.substring(0, equals).trim()
            if (!name.matches(key)) return AgentVariablesParse.Invalid(line)
            variables[key] = line.substring(equals + 1).trim()
        }
        return AgentVariablesParse.Ok(variables)
    }

    /** The text that [parse] reads back as [variables]. */
    fun format(variables: Map<String, String>): String = variables.entries.joinToString("\n") { "${it.key}=${it.value}" }
}
