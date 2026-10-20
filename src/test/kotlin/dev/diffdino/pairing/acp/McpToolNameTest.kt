package dev.diffdino.pairing.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MCP tools by the names people read, not the ones agents call them by. */
class McpToolNameTest {
    @Test
    fun aConnectorsTool_readsAsItsServerAndName() {
        assertEquals(McpToolName("Claude Docs", "read"), McpToolName.parse("mcp__claude_ai_Claude_Docs__read"))
        assertEquals("Claude Docs: read", McpToolName.readable("mcp__claude_ai_Claude_Docs__read"))
    }

    @Test
    fun aServerAllInLowerCase_isCapitalised_andToolsAreInWords() {
        assertEquals("Linear: list issues", McpToolName.readable("mcp__linear__list_issues"))
        assertEquals("Github: get pull request", McpToolName.readable("mcp__github__getPullRequest"))
        assertEquals("Databricks: execute SQL", McpToolName.readable("mcp__databricks__execute_SQL"))
    }

    @Test
    fun anythingElse_isLeftAsItIs() {
        assertEquals("npm test", McpToolName.readable("npm test"))
        assertNull(McpToolName.parse("mcp__server"))
        assertNull(McpToolName.parse("mcp____tool"))
        assertNull(McpToolName.parse("mcp__server__"))
    }

    @Test
    fun aCallsInput_isOneArgumentALine() {
        val input = Json.parseToJsonElement("""{"id":"abc","limit":5,"filter":{"open":true},"skip":null}""").jsonObject

        assertEquals("id: abc\nlimit: 5\nfilter: {\"open\":true}", McpToolName.arguments(input))
        assertEquals("", McpToolName.arguments(null))
    }
}
