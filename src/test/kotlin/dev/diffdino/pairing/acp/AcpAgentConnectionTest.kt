package dev.diffdino.pairing.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The ACP client against a scripted agent on in-process pipes: the handshake puts the session in
 * the asking mode, a turn streams its updates, and the agent's permission requests are answered by
 * the write guard without reaching the user unless the guard has no opinion. */
class AcpAgentConnectionTest {
    private val server = AcpMcpServer("diffdino", URI("http://127.0.0.1:5577/token/mcp"))
    private val agent = ScriptedAgent()
    private val prompt = RecordingPrompt()
    private val events = RecordingEvents()
    private val connection = AcpAgentConnection.over(agent.clientReads, agent.clientWrites, prompt, events, server.name)

    @After
    fun tearDown() {
        connection.dispose()
        agent.close()
    }

    private fun <T> within(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun string(node: JsonElement?): String? = (node as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun open_initializesOpensTheSessionWithTheServer_andSetsTheAskingMode() = within {
        val failure = connection.open("C:/repo", server, "default", null)

        assertNull(failure)
        assertEquals(listOf("initialize", "session/new", "session/set_mode"), agent.methods)
        val session = agent.params("session/new").jsonObject
        assertEquals("C:/repo", string(session["cwd"]))
        val mcp = (session["mcpServers"] as JsonArray)[0].jsonObject
        assertEquals("http", string(mcp["type"]))
        assertEquals(server.url.toString(), string(mcp["url"]))
        assertEquals("default", string(agent.params("session/set_mode").jsonObject["modeId"]))
        val capabilities = agent.params("initialize").jsonObject["clientCapabilities"]!!.jsonObject
        assertEquals("false", capabilities["terminal"]!!.jsonPrimitive.content)
        assertEquals("false", capabilities["fs"]!!.jsonObject["writeTextFile"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAgentThatEmbedsContext_getsTheSelectionAsAnAttachedResource() = within {
        agent.capabilities = """{"promptCapabilities":{"embeddedContext":true}}"""
        assertNull(connection.open("C:/repo", server, "default", null))
        assertTrue(connection.embedsContext)

        connection.prompt(listOf(AcpContent.Prose("Why?"), AcpContent.Resource(URI("file:///C:/repo/a.cs#L3:5"), "line 3")))

        val sent = agent.params("session/prompt").jsonObject["prompt"] as JsonArray
        assertEquals("text", string(sent[0].jsonObject["type"]))
        assertEquals("resource", string(sent[1].jsonObject["type"]))
        assertEquals("file:///C:/repo/a.cs#L3:5", string(sent[1].jsonObject["resource"]!!.jsonObject["uri"]))
        assertEquals("line 3", string(sent[1].jsonObject["resource"]!!.jsonObject["text"]))
    }

    @Test
    fun anAgentThatSaysNothingAboutContext_isNotSentResources() = within {
        assertNull(connection.open("C:/repo", server, "default", null))

        assertFalse(connection.embedsContext)
    }

    @Test
    fun open_withoutTheAskingMode_fails() = within {
        val failure = connection.open("C:/repo", server, "read-only", null)

        assertTrue(failure!!.contains("read-only"))
        assertFalse("session/set_mode" in agent.methods)
    }

    @Test
    fun setMode_movesTheSessionToAModeItOffered_andRefusesOneItDidNot() = within {
        assertNull(connection.open("C:/repo", server, "default", null))

        connection.setMode("auto")

        assertEquals("auto", string(agent.params("session/set_mode").jsonObject["modeId"]))
        assertTrue(connection.offers("default"))
        assertFalse(connection.offers("yolo"))
        try {
            connection.setMode("yolo")
            fail("A mode the agent did not offer was set.")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun open_sendsTheSessionMeta() = within {
        val meta = Json.parseToJsonElement("""{"claudeCode":{"options":{"model":"opus"}}}""").jsonObject
        assertNull(connection.open("C:/repo", server, "default", meta))

        val sent = agent.params("session/new").jsonObject["_meta"]!!.jsonObject["claudeCode"]!!.jsonObject["options"]!!.jsonObject
        assertEquals("opus", string(sent["model"]))
    }

    @Test
    fun prompt_streamsUpdates_andPutsAWriteToTheUser() = within {
        assertNull(connection.open("C:/repo", server, "default", null))
        prompt.answer = "no"
        agent.onPrompt = { scripted ->
            scripted.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Looking."}}""")
            val answer = scripted.requestPermission(
                """{"toolCall":{"toolCallId":"t1","title":"Write a.txt","kind":"edit"},
                   "options":[{"optionId":"yes","name":"Yes","kind":"allow_once"},{"optionId":"no","name":"No","kind":"reject_once"}]}""")
            assertEquals("no", string(answer?.jsonObject?.get("outcome")?.jsonObject?.get("optionId")))
            "end_turn"
        }

        val stop = connection.prompt("go")

        assertEquals(AcpStopReason.EndTurn, stop)
        assertTrue(events.updates.contains(AcpSessionUpdate.MessageChunk("Looking.")))
        assertEquals(listOf("Write a.txt" to AcpPermissionVerdict.Rejected), events.decided)
        assertEquals(listOf("Write a.txt"), prompt.asked)
    }

    @Test
    fun prompt_ownMcpTool_isAllowedWithoutAskingTheUser() = within {
        assertNull(connection.open("C:/repo", server, "default", null))
        var answer: JsonElement? = null
        agent.onPrompt = { scripted ->
            scripted.update("""{"sessionUpdate":"tool_call","toolCallId":"exec-1","rawInput":{"server":"diffdino","tool":"pairing_stop"},"_meta":{"is_mcp_tool_call":true}}""")
            answer = scripted.requestPermission(
                """{"toolCall":{"toolCallId":"exec-1","kind":"execute","status":"pending"},"_meta":{"is_mcp_tool_approval":true},
                   "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"},{"optionId":"cancel","name":"Cancel","kind":"reject_once"}]}""")
            "end_turn"
        }

        connection.prompt("go")

        assertTrue(prompt.asked.isEmpty())
        assertEquals("allow_once", string(answer?.jsonObject?.get("outcome")?.jsonObject?.get("optionId")))
    }

    @Test
    fun prompt_anUnclassifiedTool_isAskedOfTheUser() = within {
        assertNull(connection.open("C:/repo", server, "default", null))
        prompt.answer = "yes"
        var answer: JsonElement? = null
        agent.onPrompt = { scripted ->
            answer = scripted.requestPermission(
                """{"toolCall":{"toolCallId":"t1","title":"Switch mode","kind":"switch_mode"},
                   "options":[{"optionId":"yes","name":"Yes","kind":"allow_once"},{"optionId":"no","name":"No","kind":"reject_once"}]}""")
            "end_turn"
        }

        connection.prompt("go")

        assertEquals(listOf("Switch mode"), prompt.asked)
        assertEquals("yes", string(answer?.jsonObject?.get("outcome")?.jsonObject?.get("optionId")))
    }

    @Test
    fun prompt_cancelledTurn_reportsCancelled() = within {
        assertNull(connection.open("C:/repo", server, "default", null))
        agent.onPrompt = { "cancelled" }

        assertEquals(AcpStopReason.Cancelled, connection.prompt("go"))
    }

    @Test
    fun agentExit_closesTheConnection_andFailsTheTurn() = within {
        assertNull(connection.open("C:/repo", server, "default", null))
        agent.onPrompt = { scripted ->
            scripted.exit()
            CompletableDeferred<String>().await()
        }

        try {
            connection.prompt("go")
            fail("The turn outlived the agent.")
        } catch (_: AcpClosedException) {
        }
        connection.closed.await()
    }

    private class RecordingPrompt : AcpPermissionPrompt {
        val asked = mutableListOf<String>()

        @Volatile
        var answer: String? = null

        override suspend fun ask(request: AcpPermissionRequest): String? {
            synchronized(asked) { asked.add(request.title) }
            return answer
        }
    }

    private class RecordingEvents : AcpConnectionEvents {
        val updates = mutableListOf<AcpSessionUpdate>()
        val decided = mutableListOf<Pair<String, AcpPermissionVerdict>>()

        override fun updated(update: AcpSessionUpdate) {
            synchronized(updates) { updates.add(update) }
        }

        override fun permissionDecided(request: AcpPermissionRequest, verdict: AcpPermissionVerdict) {
            synchronized(decided) { decided.add(request.title to verdict) }
        }
    }
}

/** A one-way pipe of text: what is written to [writer] is read from [reader]; closing the writer
 * ends the reader's stream. */
class TextPipe {
    private val chunks = LinkedBlockingQueue<String>()
    private val end = "\u0000end"

    val writer: Writer = object : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            chunks.put(String(cbuf, off, len))
        }

        override fun flush() {}

        override fun close() {
            chunks.put(end)
        }
    }

    val reader: Reader = object : Reader() {
        private var current = ""
        private var position = 0
        private var ended = false

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= current.length) {
                if (ended) return -1
                val next = chunks.take()
                if (next === end) {
                    ended = true
                    chunks.put(end)
                    return -1
                }
                current = next
                position = 0
            }
            val count = minOf(len, current.length - position)
            current.toCharArray(cbuf, off, position, position + count)
            position += count
            return count
        }

        override fun close() {}
    }
}

/** An agent on the far end of two pipes, answering the handshake itself and a prompt through
 * [onPrompt]. */
class ScriptedAgent : AutoCloseable {
    private val toClient = TextPipe()
    private val fromClient = TextPipe()
    private val received = mutableListOf<Pair<String, JsonElement?>>()
    private val replies = HashMap<Long, CompletableDeferred<JsonElement?>>()
    private val nextId = AtomicLong(100)
    private val agentReads = BufferedReader(fromClient.reader)

    val clientReads = BufferedReader(toClient.reader)
    val clientWrites: Writer = fromClient.writer

    @Volatile
    var onPrompt: (suspend (ScriptedAgent) -> String)? = null

    /** The `agentCapabilities` initialize answers with. */
    @Volatile
    var capabilities = "{}"

    init {
        thread(isDaemon = true, name = "scripted agent") { loop() }
    }

    val methods: List<String> get() = synchronized(received) { received.map { it.first } }

    fun params(method: String): JsonElement =
        synchronized(received) { received.last { it.first == method }.second } ?: throw AssertionError("$method had no params")

    fun update(update: String) = send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "session/update")
        put("params", buildJsonObject {
            put("sessionId", "s1")
            put("update", Json.parseToJsonElement(update))
        })
    })

    suspend fun requestPermission(parameters: String): JsonElement? {
        val id = nextId.incrementAndGet()
        val reply = CompletableDeferred<JsonElement?>()
        synchronized(replies) { replies[id] = reply }
        val body = JsonObject(Json.parseToJsonElement(parameters).jsonObject + ("sessionId" to JsonPrimitive("s1")))
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "session/request_permission")
            put("params", body)
        })
        return reply.await()
    }

    fun exit() = toClient.writer.close()

    private fun loop() {
        while (true) {
            val line = agentReads.readLine() ?: return
            val message = Json.parseToJsonElement(line).jsonObject
            val method = (message["method"] as? JsonPrimitive)?.content
            if (method == null) {
                val id = (message["id"] as JsonPrimitive).longOrNull ?: continue
                val reply = synchronized(replies) { replies.remove(id) }
                reply?.complete(message["result"])
                continue
            }

            synchronized(received) { received.add(method to message["params"]) }
            val id = message["id"] ?: continue
            thread(isDaemon = true) { answer(id, method) }
        }
    }

    private fun answer(id: JsonElement, method: String) {
        val result: JsonElement = when (method) {
            "initialize" -> Json.parseToJsonElement("""{"protocolVersion":1,"agentCapabilities":$capabilities,"authMethods":[]}""")
            "session/new" -> Json.parseToJsonElement(
                """{"sessionId":"s1","modes":{"currentModeId":"auto","availableModes":[{"id":"default","name":"Manual"},{"id":"auto","name":"Auto"}]}}""")
            "session/set_mode" -> JsonObject(emptyMap())
            "session/prompt" -> buildJsonObject {
                put("stopReason", onPrompt?.let { run -> runBlocking { run(this@ScriptedAgent) } } ?: "end_turn")
            }
            else -> JsonNull
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        })
    }

    private fun send(message: JsonObject) = synchronized(toClient) {
        toClient.writer.write(message.toString() + "\n")
    }

    override fun close() {
        toClient.writer.close()
        fromClient.writer.close()
    }
}
