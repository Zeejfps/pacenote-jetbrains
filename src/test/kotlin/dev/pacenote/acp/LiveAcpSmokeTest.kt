package dev.pacenote.acp

import dev.pacenote.mcp.AgentTool
import dev.pacenote.mcp.McpHttpServer
import dev.pacenote.mcp.McpServerIdentity
import dev.pacenote.mcp.ToolInvocation
import dev.pacenote.mcp.ToolJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Starts a real Claude Code adapter, opens a session against the plugin's MCP server, and has it
 * call a tool there. Runs only with PACENOTE_LIVE_ACP=1, since it needs npx, the network and a
 * Claude login. */
class LiveAcpSmokeTest {
    @Test
    fun claudeCode_opensASessionInItsAskingMode_andCallsThePluginsTool() {
        assumeTrue(System.getenv("PACENOTE_LIVE_ACP") == "1")
        val called = CopyOnWriteArrayList<String>()
        val asked = CopyOnWriteArrayList<String>()
        val echo = object : AgentTool {
            override val name = "echo"
            override val description = "Says back what it is given."
            override val jsonSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""
            override val isWrite = false

            override suspend fun invoke(args: JsonObject): ToolInvocation {
                called.add(ToolJson.string(args, "text").orEmpty())
                return ToolInvocation.ok("""{"said":"ok"}""")
            }
        }
        val directory = Files.createTempDirectory("acp-live")
        val server = McpHttpServer.start(McpServerIdentity("Pacenote", "1", "Smoke test."))
        val endpoint = server.register(listOf(echo))
        try {
            val started = runBlocking {
                AcpAgentConnection.start(
                    AcpHarness.ClaudeCode,
                    directory,
                    AcpMcpServer("pacenote", endpoint.url),
                    { request -> asked.add(request.title); null },
                    object : AcpConnectionEvents {
                        override fun traffic(traffic: AcpTraffic) = println("${traffic.direction} ${traffic.line.take(300)}")
                    },
                    AcpEnvironment(System.getenv()),
                )
            }
            val connection = when (started) {
                is AcpStart.Started -> started.connection
                is AcpStart.Failed -> return fail(started.reason)
            }
            try {
                val reason = runBlocking {
                    withTimeout(180_000) {
                        connection.prompt("Call the echo tool of the pacenote MCP server once with text \"hi\", then reply with one word.")
                    }
                }
                assertEquals(AcpStopReason.EndTurn, reason)
                assertEquals(listOf("hi"), called.toList())
                assertTrue("The policy should have allowed the plugin's own tool: $asked", asked.isEmpty())
            } finally {
                connection.dispose()
            }
        } finally {
            endpoint.close()
            server.close()
            directory.toFile().deleteRecursively()
        }
    }
}
