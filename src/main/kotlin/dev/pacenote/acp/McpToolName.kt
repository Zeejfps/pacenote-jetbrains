package dev.pacenote.acp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An MCP tool as a person reads it. Claude Code titles a call to one by the name the agent calls it
 * by, mcp__<server>__<tool>, and claude.ai's connectors put claude_ai_ before the server's name: so
 * mcp__claude_ai_Claude_Docs__read reads as "Claude Docs: read".
 */
data class McpToolName(val server: String, val tool: String) {
    override fun toString() = "$server: $tool"

    companion object {
        private const val PREFIX = "mcp__"
        private const val CONNECTOR = "claude_ai_"

        /** The tool [name] stands for, or null when it isn't one. */
        fun parse(name: String): McpToolName? {
            val trimmed = name.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val end = trimmed.indexOf("__", PREFIX.length)
            if (end <= PREFIX.length || end + 2 >= trimmed.length) return null
            return McpToolName(serverName(trimmed.substring(PREFIX.length, end)), words(trimmed.substring(end + 2)))
        }

        /** [title] as read, where it names an MCP tool; else as it is. */
        fun readable(title: String): String = parse(title)?.toString() ?: title

        /** A server's name as read: without the connector's prefix, in words, capitalised when it is all lower case. */
        fun serverName(raw: String): String {
            val bare = if (raw.startsWith(CONNECTOR, ignoreCase = true) && raw.length > CONNECTOR.length) raw.substring(CONNECTOR.length) else raw
            val spoken = bare.split('_', '-', ' ').filter(String::isNotEmpty).joinToString(" ")
            return if (spoken == spoken.lowercase()) spoken.replaceFirstChar(Char::uppercaseChar) else spoken
        }

        /** A call's input one argument a line, strings as they are and anything else as JSON. */
        fun arguments(input: JsonObject?): String = input.orEmpty().entries
            .filter { it.value !is JsonNull }
            .joinToString("\n") { (key, value) ->
                "$key: ${(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()}"
            }

        private fun words(name: String): String =
            name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").split('_', '-', ' ').filter(String::isNotEmpty).joinToString(" ") {
                if (it.length > 1 && it.all(Char::isUpperCase)) it else it.lowercase()
            }
    }
}
