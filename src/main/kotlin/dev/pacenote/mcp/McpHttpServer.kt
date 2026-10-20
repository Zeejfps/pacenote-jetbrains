package dev.pacenote.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Where one set of tools is served: the URL an agent is handed, with its secret in the path.
 * Closing it stops serving the tools there. */
class McpEndpoint internal constructor(val url: URI, private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}

/**
 * A minimal MCP server over Streamable HTTP on the loopback interface: each registered tool set is
 * served at its own secret path, answering `initialize`, `ping`, `tools/list` and `tools/call` as
 * plain JSON (no event streams). Any other path is a 404, so only an agent the plugin handed a URL
 * reaches the tools.
 */
class McpHttpServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    private val identity: McpServerIdentity,
) : AutoCloseable {
    private val endpoints = ConcurrentHashMap<String, List<AgentTool>>()

    val port: Int get() = server.address.port

    /** Serves [tools] at a fresh secret path until the endpoint is closed. */
    fun register(tools: List<AgentTool>): McpEndpoint {
        val token = newToken()
        endpoints[token] = tools.toList()
        return McpEndpoint(URI("http://127.0.0.1:$port/$token/mcp")) { endpoints.remove(token) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            try {
                route(exchange)
            } catch (_: IOException) {
            }
        }
    }

    private fun route(exchange: HttpExchange) {
        val tools = toolsFor(exchange.requestURI.rawPath)
        if (tools == null) return respond(exchange, 404, null)
        if (!fromLoopbackPage(exchange)) return respond(exchange, 403, null)
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            return respond(exchange, 405, null)
        }

        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return respond(exchange, 400, error(JsonNull, PARSE_ERROR, "The body is not JSON."))
        } catch (_: IllegalArgumentException) {
            return respond(exchange, 400, error(JsonNull, PARSE_ERROR, "The body is not JSON."))
        }

        val replies = when (parsed) {
            is JsonArray -> parsed.mapNotNull { answer(it, tools) }
            else -> listOfNotNull(answer(parsed, tools))
        }
        when {
            replies.isEmpty() -> respond(exchange, 202, null)
            parsed is JsonArray -> respond(exchange, 200, JsonArray(replies))
            else -> respond(exchange, 200, replies.single())
        }
    }

    private fun toolsFor(path: String): List<AgentTool>? {
        val parts = path.trim('/').split('/')
        if (parts.size != 2 || parts[1] != "mcp") return null
        return endpoints[parts[0]]
    }

    // A browser page can post to loopback; one served from anywhere else is turned away.
    private fun fromLoopbackPage(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val host = try {
            URI(origin).host
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        return host == "127.0.0.1" || host == "localhost" || host == "[::1]"
    }

    /** The reply to one JSON-RPC message, or null for a notification or a response. */
    private fun answer(message: JsonElement, tools: List<AgentTool>): JsonElement? {
        val body = message as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Not a JSON-RPC message.")
        val method = ToolJson.string(body["method"])
        val id = body["id"]
        if (method == null) return if (id == null) error(JsonNull, INVALID_REQUEST, "No method.") else null
        if (id == null || id is JsonNull) return null

        val params = body["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools.map(::describe))) })
            "tools/call" -> call(id, params, tools)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = ToolJson.string(params["protocolVersion"])
        return buildJsonObject {
            put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", identity.name)
                put("version", identity.version)
            })
            put("instructions", identity.instructions)
        }
    }

    private fun describe(tool: AgentTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("inputSchema", Json.parseToJsonElement(tool.jsonSchema))
        put("annotations", buildJsonObject {
            put("readOnlyHint", !tool.isWrite)
            put("destructiveHint", false)
        })
    }

    private fun call(id: JsonElement, params: JsonObject, tools: List<AgentTool>): JsonElement {
        val name = ToolJson.string(params["name"]) ?: return error(id, INVALID_PARAMS, "tools/call needs a tool name.")
        val tool = tools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val arguments = when (val given = params["arguments"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> given
            else -> return error(id, INVALID_PARAMS, "arguments must be an object.")
        }

        // A tool that throws is reported to the agent as a failed call rather than ending the server's worker.
        val outcome = try {
            runBlocking { tool.invoke(arguments) }
        } catch (e: RuntimeException) {
            ToolInvocation.error("${tool.name} failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return result(id, buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", outcome.content)
                })
            })
            put("isError", outcome.isError)
        })
    }

    private fun result(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonElement?) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602

        private val PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val random = SecureRandom()

        /** Listens on 127.0.0.1 on a port the system picks. */
        fun start(identity: McpServerIdentity): McpHttpServer {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            val threads = AtomicInteger()
            val executor = Executors.newCachedThreadPool { work ->
                Thread(work, "Pacenote MCP ${threads.incrementAndGet()}").apply { isDaemon = true }
            }
            server.executor = executor
            val mcp = McpHttpServer(server, executor, identity)
            server.createContext("/") { mcp.handle(it) }
            server.start()
            return mcp
        }

        private fun newToken(): String {
            val bytes = ByteArray(24)
            random.nextBytes(bytes)
            return HexFormat.of().formatHex(bytes)
        }
    }
}

/** What the server says of itself on `initialize`. */
data class McpServerIdentity(val name: String, val version: String, val instructions: String)
