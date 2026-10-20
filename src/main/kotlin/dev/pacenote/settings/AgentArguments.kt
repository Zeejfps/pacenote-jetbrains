package dev.pacenote.settings

/** Why a preset's arguments can't be passed to its agent. */
sealed interface AgentArgumentsProblem {
    data object UnclosedQuote : AgentArgumentsProblem

    /** Claude Code's adapter hands arguments on as long flags, each with at most one value;
     * [token] is neither. */
    data class NotAFlag(val token: String) : AgentArgumentsProblem
}

sealed interface AgentArgumentsParse {
    data class Ok(val arguments: List<String>) : AgentArgumentsParse

    data class Invalid(val problem: AgentArgumentsProblem) : AgentArgumentsParse
}

/** A long flag as Claude Code's adapter passes it: `--name`, or `--name value`. */
data class ClaudeFlag(val name: String, val value: String?)

/** How reading arguments as Claude Code flags came out. */
sealed interface ClaudeFlags {
    data class Ok(val flags: List<ClaudeFlag>) : ClaudeFlags

    data class Invalid(val problem: AgentArgumentsProblem) : ClaudeFlags
}

/**
 * A preset's extra arguments between the text the user types and the list the agent gets: split on
 * whitespace, with single or double quotes grouping, and no escapes so Windows paths pass through as
 * typed.
 */
object AgentArguments {
    fun parse(text: String, kind: AgentKind): AgentArgumentsParse {
        val arguments = split(text) ?: return AgentArgumentsParse.Invalid(AgentArgumentsProblem.UnclosedQuote)
        return when (val problem = check(arguments, kind)) {
            null -> AgentArgumentsParse.Ok(arguments)
            else -> AgentArgumentsParse.Invalid(problem)
        }
    }

    /** What stops [arguments] from being passed to a [kind] agent, or null. */
    fun check(arguments: List<String>, kind: AgentKind): AgentArgumentsProblem? = when (kind) {
        AgentKind.ClaudeCode -> when (val flags = claudeFlags(arguments)) {
            is ClaudeFlags.Ok -> null
            is ClaudeFlags.Invalid -> flags.problem
        }
        AgentKind.Codex, AgentKind.Gemini -> null
    }

    /** Reads [arguments] as Claude Code flags. A flag takes the token after it as its value unless
     * that token is a flag too; `--name=value` always does. */
    fun claudeFlags(arguments: List<String>): ClaudeFlags {
        val flags = ArrayList<ClaudeFlag>(arguments.size)
        var i = 0
        while (i < arguments.size) {
            val token = arguments[i]
            if (!token.startsWith("--") || token.length == 2 || token[2] == '=')
                return ClaudeFlags.Invalid(AgentArgumentsProblem.NotAFlag(token))

            val equals = token.indexOf('=')
            if (equals > 0) {
                flags.add(ClaudeFlag(token.substring(2, equals), token.substring(equals + 1)))
                i++
                continue
            }

            if (i + 1 < arguments.size && !arguments[i + 1].startsWith('-')) {
                flags.add(ClaudeFlag(token.substring(2), arguments[i + 1]))
                i += 2
                continue
            }

            flags.add(ClaudeFlag(token.substring(2), null))
            i++
        }
        return ClaudeFlags.Ok(flags)
    }

    /** The tokens in [text], or null for a quote left open. */
    fun split(text: String): List<String>? {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        for (c in text) {
            val open = quote
            if (open != null) {
                if (c == open) quote = null else current.append(c)
                continue
            }

            when {
                c == '"' || c == '\'' -> {
                    quote = c
                    inToken = true
                }
                c.isWhitespace() -> {
                    if (!inToken) continue
                    tokens.add(current.toString())
                    current.setLength(0)
                    inToken = false
                }
                else -> {
                    current.append(c)
                    inToken = true
                }
            }
        }

        if (quote != null) return null
        if (inToken) tokens.add(current.toString())
        return tokens
    }

    /** The text that [split] reads back as [arguments]. */
    fun format(arguments: List<String>): String = arguments.joinToString(" ") { quote(it) }

    private fun quote(token: String): String {
        if (token.isNotEmpty() && token.none { it.isWhitespace() || it == '"' || it == '\'' }) return token
        if (!token.contains('"')) return "\"$token\""
        if (!token.contains('\'')) return "'$token'"
        return token.split('"').joinToString("'\"'") { "\"$it\"" }
    }
}
