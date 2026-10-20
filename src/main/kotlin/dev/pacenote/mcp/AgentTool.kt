package dev.pacenote.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/** One capability an agent reaches over the plugin's MCP server. */
interface AgentTool {
    val name: String

    /** What the tool does and when to reach for it: the model sees this verbatim. */
    val description: String

    /** The argument schema as a compact JSON object literal. */
    val jsonSchema: String

    /** Whether invoking this tool changes the project. */
    val isWrite: Boolean

    suspend fun invoke(args: JsonObject): ToolInvocation
}

/** What a tool hands back. A failed call is an error result the model can adapt to, not an
 * exception that ends the turn. */
data class ToolInvocation(val content: String, val isError: Boolean) {
    companion object {
        fun ok(content: String) = ToolInvocation(content, false)

        fun error(message: String) = ToolInvocation(message, true)
    }
}

/** Builds the compact JSON a tool hands back, and reads arguments off the model's input object. */
object ToolJson {
    fun write(body: JsonObjectBuilder.() -> Unit): String = buildJsonObject(body).toString()

    fun string(args: JsonObject, name: String): String? = string(args[name])

    fun string(node: JsonElement?): String? = (node as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number that is a whole 32-bit integer; a string spelling one doesn't count. */
    fun int(node: JsonElement?): Int? = (node as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    fun bool(args: JsonObject, name: String, fallback: Boolean): Boolean {
        val value = args[name] as? JsonPrimitive ?: return fallback
        return if (value.isString) value.content.lowercase().toBooleanStrictOrNull() ?: fallback else value.booleanOrNull ?: fallback
    }
}
