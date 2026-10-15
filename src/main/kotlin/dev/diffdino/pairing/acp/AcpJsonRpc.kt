package dev.diffdino.pairing.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What the agent sent the client unasked: a notification, or a request the client must answer. */
interface AcpClientMessages {
    /** Called on the reader's thread, in the order the agent sent them. */
    fun onNotification(method: String, parameters: JsonElement?)

    /** Answers a request from the agent. Throwing [AcpRpcException] answers with that error. */
    suspend fun onRequest(method: String, parameters: JsonElement?): JsonElement?
}

/** An error the other side answered a request with, or one this side answers with. */
class AcpRpcException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** The agent's side of the pipe is gone. */
class AcpClosedException : Exception("The agent's connection is closed.")

enum class AcpDirection { Incoming, Outgoing }

data class AcpTraffic(val direction: AcpDirection, val line: String)

/**
 * JSON-RPC 2.0 over newline-delimited JSON, the framing the Agent Client Protocol uses on an
 * agent's stdio. Owns id allocation, response matching and the read loop; it knows nothing of what
 * the methods mean. A line that is not a JSON object is skipped: adapters log to stdout.
 */
class AcpJsonRpc(
    private val incoming: BufferedReader,
    private val outgoing: Writer,
    private val handler: AcpClientMessages,
    private val traffic: (AcpTraffic) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private val writeLock = Mutex()
    private val started = AtomicBoolean()
    private val nextId = AtomicLong()
    private val closedSignal = CompletableDeferred<Unit>()

    /** Completes when the incoming stream ends or the connection is disposed. */
    val closed: Deferred<Unit> get() = closedSignal

    fun start() {
        if (started.compareAndSet(false, true)) scope.launch { readLoop() }
    }

    suspend fun request(method: String, parameters: JsonElement?): JsonElement? {
        val id = nextId.incrementAndGet()
        val completion = CompletableDeferred<JsonElement?>()
        pending[id] = completion
        if (closedSignal.isCompleted) {
            pending.remove(id)
            throw AcpClosedException()
        }

        try {
            write(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (parameters != null) put("params", parameters)
            })
            return completion.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, parameters: JsonElement?) = write(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (parameters != null) put("params", parameters)
    })

    private fun readLoop() {
        try {
            while (true) {
                val line = incoming.readLine() ?: break
                if (line.isEmpty()) continue
                traffic(AcpTraffic(AcpDirection.Incoming, line))
                val message = parse(line) ?: continue
                dispatch(message)
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun parse(line: String): JsonObject? = try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun dispatch(message: JsonObject) {
        val method = text(message["method"])
        val id = message["id"]

        if (method == null) {
            val number = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return
            val waiting = pending.remove(number) ?: return
            when (val error = message["error"]) {
                is JsonObject -> waiting.completeExceptionally(AcpRpcException(
                    (error["code"] as? JsonPrimitive)?.intOrNull ?: AcpRpcException.INTERNAL_ERROR,
                    text(error["message"]) ?: "The agent answered with an error.",
                ))
                else -> waiting.complete(message["result"]?.takeIf { it !is JsonNull })
            }
            return
        }

        val parameters = message["params"]?.takeIf { it !is JsonNull }
        if (id == null || id is JsonNull) {
            handler.onNotification(method, parameters)
            return
        }

        scope.launch { answer(id, method, parameters) }
    }

    private suspend fun answer(id: JsonElement, method: String, parameters: JsonElement?) {
        val reply = try {
            val result = handler.onRequest(method, parameters)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result ?: JsonNull)
            }
        } catch (e: AcpRpcException) {
            errorReply(id, e.code, e.message ?: "")
        }

        try {
            write(reply)
        } catch (_: AcpClosedException) {
        }
    }

    private fun errorReply(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private suspend fun write(message: JsonObject) {
        val line = message.toString()
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                if (closedSignal.isCompleted) throw AcpClosedException()
                try {
                    outgoing.write(line + "\n")
                    outgoing.flush()
                } catch (_: IOException) {
                    close()
                    throw AcpClosedException()
                }
            }
        }
        traffic(AcpTraffic(AcpDirection.Outgoing, line))
    }

    private fun close() {
        if (!closedSignal.complete(Unit)) return
        for (id in pending.keys) pending.remove(id)?.completeExceptionally(AcpClosedException())
    }

    /** Stops answering and fails every request still waiting. The read loop ends with the stream,
     * which the owner closes. */
    fun dispose() {
        close()
        scope.cancel()
    }

    internal companion object {
        fun text(node: JsonElement?): String? = (node as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
