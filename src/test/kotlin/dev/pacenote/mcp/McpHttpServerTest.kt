package dev.pacenote.mcp

import dev.pacenote.pairing.AgentPairingStart
import dev.pacenote.pairing.PairingSessionAccess
import dev.pacenote.pairing.PairingStore
import dev.pacenote.pairing.PairingTools
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The plugin's MCP server over real HTTP: the secret path is the only way in, and a client gets
 * the handshake, the tool list and tool calls as plain JSON. */
class McpHttpServerTest {
    private val server = McpHttpServer.start(McpServerIdentity("Pacenote", "1", "Pair with the user."))
    private val echo = object : AgentTool {
        override val name = "echo"
        override val description = "Says back what it is given."
        override val jsonSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""
        override val isWrite = false

        override suspend fun invoke(args: JsonObject): ToolInvocation {
            val text = ToolJson.string(args, "text") ?: return ToolInvocation.error("text must be a string.")
            return ToolInvocation.ok(ToolJson.write { put("said", text) })
        }
    }
    private val endpoint = server.register(listOf(echo) + PairingTools.createAll(NoSession, Dispatchers.Default))
    private val http = HttpClient.newHttpClient()

    @After
    fun tearDown() {
        endpoint.close()
        server.close()
    }

    private object NoSession : PairingSessionAccess {
        override fun storeFor(): PairingStore? = null

        override fun startByAgent(goal: String): AgentPairingStart = AgentPairingStart.NoConversation
    }

    private fun post(body: String, url: URI = endpoint.url, origin: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { if (origin != null) header("Origin", origin) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun rpc(id: Int, method: String, params: String = "{}"): JsonObject {
        val response = post("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        val reply = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals(JsonPrimitive(id), reply["id"])
        return reply
    }

    private fun string(node: JsonElement?): String? = (node as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun theUrl_isOnLoopback_withASecretPath() {
        assertEquals("127.0.0.1", endpoint.url.host)
        assertTrue(endpoint.url.path.matches(Regex("/[0-9a-f]{48}/mcp")))
    }

    @Test
    fun aWrongToken_isNotFound() {
        val wrong = URI("http://127.0.0.1:${server.port}/${"0".repeat(48)}/mcp")

        assertEquals(404, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", wrong).statusCode())
    }

    @Test
    fun aClosedEndpoint_isNotFound() {
        val other = server.register(listOf(echo))
        other.close()

        assertEquals(404, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", other.url).statusCode())
    }

    @Test
    fun aPageFromElsewhere_isTurnedAway() {
        assertEquals(403, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin = "https://evil.example").statusCode())
    }

    @Test
    fun initialize_answersWithTheToolsCapabilityAndInstructions() {
        val result = rpc(1, "initialize",
            """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")["result"]!!.jsonObject

        assertEquals("2025-03-26", string(result["protocolVersion"]))
        assertEquals("Pair with the user.", string(result["instructions"]))
        assertEquals("Pacenote", string(result["serverInfo"]!!.jsonObject["name"]))
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
    }

    @Test
    fun anUnknownProtocolVersion_getsTheLatest() {
        val result = rpc(1, "initialize", """{"protocolVersion":"1999-01-01"}""")["result"]!!.jsonObject

        assertEquals("2025-06-18", string(result["protocolVersion"]))
    }

    @Test
    fun aNotification_isAccepted_withNoBody() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertEquals(202, response.statusCode())
        assertEquals("", response.body())
    }

    @Test
    fun ping_answersEmpty() {
        assertEquals(JsonObject(emptyMap()), rpc(7, "ping")["result"])
    }

    @Test
    fun toolsList_namesEveryToolWithItsSchema() {
        val tools = rpc(2, "tools/list")["result"]!!.jsonObject["tools"] as JsonArray
        val names = tools.map { string(it.jsonObject["name"]) }

        assertEquals(listOf("echo", "pairing_start", "pairing_roadmap", "pairing_stop", "pairing_state", "pairing_say", "pairing_end"), names)
        val stop = tools.first { string(it.jsonObject["name"]) == "pairing_stop" }.jsonObject
        assertEquals(Json.parseToJsonElement(PairingTools.createAll(NoSession, Dispatchers.Default)[2].jsonSchema), stop["inputSchema"])
        assertEquals("true", (stop["annotations"]!!.jsonObject["readOnlyHint"] as JsonPrimitive).content)
    }

    @Test
    fun toolsCall_runsTheTool() {
        val result = rpc(3, "tools/call", """{"name":"echo","arguments":{"text":"hi"}}""")["result"]!!.jsonObject

        assertEquals("false", (result["isError"] as JsonPrimitive).content)
        assertEquals("""{"said":"hi"}""", string((result["content"] as JsonArray)[0].jsonObject["text"]))
    }

    @Test
    fun aToolThatFails_isAnErrorResult() {
        val result = rpc(4, "tools/call", """{"name":"pairing_state","arguments":{}}""")["result"]!!.jsonObject

        assertEquals("true", (result["isError"] as JsonPrimitive).content)
        assertTrue(string((result["content"] as JsonArray)[0].jsonObject["text"])!!.contains("No pairing session is running"))
    }

    @Test
    fun anUnknownTool_isAProtocolError() {
        val reply = rpc(5, "tools/call", """{"name":"nope","arguments":{}}""")

        assertEquals("-32602", (reply["error"]!!.jsonObject["code"] as JsonPrimitive).content)
    }

    @Test
    fun anUnknownMethod_isMethodNotFound() {
        assertEquals("-32601", (rpc(6, "resources/list")["error"]!!.jsonObject["code"] as JsonPrimitive).content)
    }

    @Test
    fun aGet_isNotAllowed() {
        val response = http.send(HttpRequest.newBuilder(endpoint.url).GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(405, response.statusCode())
    }
}
