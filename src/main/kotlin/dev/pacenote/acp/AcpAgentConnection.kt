package dev.pacenote.acp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import dev.pacenote.acp.AcpJsonRpc.Companion.text
import dev.pacenote.settings.ApprovalRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** Asks the user about a permission request the policy leaves open. Answers the chosen option id,
 * or null to cancel the tool call. */
fun interface AcpPermissionPrompt {
    suspend fun ask(request: AcpPermissionRequest): String?
}

/** What an agent run reports as it goes. Called on the reader's thread. */
interface AcpConnectionEvents {
    /** A fragment of the agent's output, a tool call, or its plan. */
    fun updated(update: AcpSessionUpdate) {}

    /** A permission request and what was answered, including the ones the user decided. */
    fun permissionDecided(request: AcpPermissionRequest, verdict: AcpPermissionVerdict) {}

    /** A protocol line in either direction, for a trace. */
    fun traffic(traffic: AcpTraffic) {}
}

/** The plugin's MCP server as a session is handed it. */
data class AcpMcpServer(val name: String, val url: URI)

/** How starting an agent went. */
sealed interface AcpStart {
    data class Started(val connection: AcpAgentConnection) : AcpStart

    data class Failed(val reason: String) : AcpStart
}

/**
 * One agent run over the Agent Client Protocol: the adapter process, its one session, and the write
 * guard. Every permission request goes through [AcpPermissionPolicy] (reads, shell commands and the
 * plugin's own MCP tools pass unless the user's rules say otherwise, file edits and the rest are
 * asked) and the session is put in its
 * harness's asking mode first, so that no write is decided inside the CLI.
 */
class AcpAgentConnection private constructor(
    incoming: BufferedReader,
    outgoing: Writer,
    private val process: Process?,
    private val prompt: AcpPermissionPrompt,
    private val events: AcpConnectionEvents,
    private val ownServer: String,
    private val rules: () -> ApprovalRules,
) : AcpClientMessages {
    private val rpc = AcpJsonRpc(incoming, outgoing, this, events::traffic)
    private val announcedServers = HashMap<String, String>()
    private val stderr = ArrayDeque<String>()
    private val disposed = AtomicBoolean()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var modes: Set<String> = emptySet()

    /** Whether the agent takes a file's text attached to a turn as context, rather than only prose. */
    @Volatile
    var embedsContext: Boolean = false
        private set

    /** Completes when the agent's output ends: it exited, crashed, or was disposed. */
    val closed: Deferred<Unit> get() = rpc.closed

    /** The tail of what the adapter wrote to stderr, for a failure message. */
    val stderrTail: String
        get() = synchronized(stderr) { stderr.joinToString("\n") }

    /** Handshakes, opens the session, and puts it in [askingMode]. Answers why it could not, or null. */
    suspend fun open(workingDirectory: String, server: AcpMcpServer, askingMode: String, sessionMeta: JsonObject?): String? {
        rpc.start()
        return try {
            withTimeout(HANDSHAKE_TIMEOUT) {
                val initialized = rpc.request("initialize", buildJsonObject {
                    put("protocolVersion", 1)
                    put("clientCapabilities", buildJsonObject {
                        put("fs", buildJsonObject {
                            put("readTextFile", false)
                            put("writeTextFile", false)
                        })
                        put("terminal", false)
                    })
                    put("clientInfo", buildJsonObject {
                        put("name", "Pacenote")
                        put("version", "1")
                    })
                })
                embedsContext = (at(initialized, "agentCapabilities", "promptCapabilities", "embeddedContext") as? JsonPrimitive)
                    ?.booleanOrNull == true

                val session = rpc.request("session/new", buildJsonObject {
                    if (sessionMeta != null) put("_meta", sessionMeta)
                    put("cwd", workingDirectory)
                    put("mcpServers", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "http")
                            put("name", server.name)
                            put("url", server.url.toString())
                            put("headers", JsonArray(emptyList()))
                        })
                    })
                })

                val opened = text(at(session, "sessionId")) ?: return@withTimeout "The agent opened no session."
                sessionId = opened
                modes = offeredModes(session)
                if (!offers(askingMode)) return@withTimeout "The agent offers no '$askingMode' mode, so its writes can't be refused."
                rpc.request("session/set_mode", buildJsonObject {
                    put("sessionId", opened)
                    put("modeId", askingMode)
                })
                null
            }
        } catch (e: AcpRpcException) {
            "The agent refused to start: ${e.message}"
        } catch (_: AcpClosedException) {
            "The agent exited while starting."
        } catch (_: TimeoutCancellationException) {
            "The agent did not answer in time."
        }
    }

    private fun offeredModes(session: JsonElement?): Set<String> =
        (at(session, "modes", "availableModes") as? JsonArray).orEmpty()
            .mapNotNull { text((it as? JsonObject)?.get("id")) }
            .toSet()

    /** Whether the session offered [mode] when it opened. */
    fun offers(mode: String): Boolean = mode in modes

    /** Puts the session in [mode], which it must offer. */
    suspend fun setMode(mode: String) {
        val id = sessionId ?: throw IllegalStateException("The session is not open.")
        if (!offers(mode)) throw IllegalStateException("The agent offers no '$mode' mode.")
        rpc.request("session/set_mode", buildJsonObject {
            put("sessionId", id)
            put("modeId", mode)
        })
    }

    /** Sends one user turn and completes when the agent ends it. */
    suspend fun prompt(text: String): AcpStopReason = prompt(listOf(AcpContent.Prose(text)))

    /** Sends one user turn made of [content] and completes when the agent ends it. Attach a
     * [AcpContent.Resource] only where [embedsContext]. Cancelling the caller asks the agent to stop
     * the turn. */
    suspend fun prompt(content: List<AcpContent>): AcpStopReason {
        val id = sessionId ?: throw IllegalStateException("The session is not open.")
        val result = try {
            rpc.request("session/prompt", buildJsonObject {
                put("sessionId", id)
                put("prompt", JsonArray(content.map(::toJson)))
            })
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancelTurn() }
            throw e
        }

        return when (text(at(result, "stopReason"))) {
            "max_tokens" -> AcpStopReason.MaxTokens
            "max_turn_requests" -> AcpStopReason.MaxTurnRequests
            "refusal" -> AcpStopReason.Refusal
            "cancelled" -> AcpStopReason.Cancelled
            else -> AcpStopReason.EndTurn
        }
    }

    private fun toJson(content: AcpContent): JsonElement = when (content) {
        is AcpContent.Prose -> buildJsonObject {
            put("type", "text")
            put("text", content.value)
        }
        is AcpContent.Resource -> buildJsonObject {
            put("type", "resource")
            put("resource", buildJsonObject {
                put("uri", content.uri.toString())
                put("mimeType", "text/plain")
                put("text", content.text)
            })
        }
    }

    /** Asks the agent to stop the turn in progress; the turn then ends `cancelled`. */
    suspend fun cancelTurn() {
        val id = sessionId ?: return
        try {
            rpc.notify("session/cancel", buildJsonObject { put("sessionId", id) })
        } catch (_: AcpClosedException) {
        }
    }

    override fun onNotification(method: String, parameters: JsonElement?) {
        if (method != "session/update") return
        val update = (parameters as? JsonObject)?.get("update")
        val body = update as? JsonObject
        if (body != null && text(body["sessionUpdate"]) == "tool_call") {
            val toolCallId = text(body["toolCallId"])
            val announced = AcpPermissionPolicy.announcedServer(body)
            if (toolCallId != null && announced != null) synchronized(announcedServers) { announcedServers[toolCallId] = announced }
        }

        AcpSessionUpdate.parse(update)?.let(events::updated)
    }

    override suspend fun onRequest(method: String, parameters: JsonElement?): JsonElement? {
        if (method != "session/request_permission")
            throw AcpRpcException(AcpRpcException.METHOD_NOT_FOUND, "Pacenote does not offer $method.")

        val request = synchronized(announcedServers) { AcpPermissionPolicy.parse(parameters, announcedServers.toMap()) }
            ?: throw AcpRpcException(AcpRpcException.INVALID_PARAMS, "Unreadable permission request.")

        val chosen = when (val decision = AcpPermissionPolicy.decide(request, ownServer, rules())) {
            is AcpPermissionDecision.Select -> {
                events.permissionDecided(request, decision.verdict)
                decision.optionId
            }
            AcpPermissionDecision.AskUser -> {
                val answer = prompt.ask(request)
                events.permissionDecided(request, if (isAllow(request, answer)) AcpPermissionVerdict.Allowed else AcpPermissionVerdict.Rejected)
                answer
            }
        }

        return buildJsonObject {
            put("outcome", buildJsonObject {
                if (chosen == null) put("outcome", "cancelled")
                else {
                    put("outcome", "selected")
                    put("optionId", chosen)
                }
            })
        }
    }

    private fun isAllow(request: AcpPermissionRequest, optionId: String?): Boolean {
        val option = request.options.firstOrNull { it.optionId == optionId } ?: return false
        return option.kind == AcpPermissionOptionKind.AllowOnce || option.kind == AcpPermissionOptionKind.AllowAlways
    }

    private fun noteStderr(line: String) {
        synchronized(stderr) {
            stderr.addLast(line)
            while (stderr.size > STDERR_LINES) stderr.removeFirst()
        }
    }

    /** Ends the run. The process goes first: it is the process ending that lets the read loop finish. */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        process?.let { running ->
            running.descendants().forEach { it.destroyForcibly() }
            running.destroyForcibly()
        }
        rpc.dispose()
    }

    companion object {
        private const val STDERR_LINES = 40
        private val HANDSHAKE_TIMEOUT = 90.seconds

        /** Starts [harness]'s adapter in [workingDirectory] and opens its session with [server]. The
         * guard reads [rules] at each permission request, so a change to them takes effect at once.
         * Blocks on process creation, so call it off the EDT. */
        suspend fun start(
            harness: AcpHarness,
            workingDirectory: Path,
            server: AcpMcpServer,
            prompt: AcpPermissionPrompt,
            events: AcpConnectionEvents,
            environment: AcpEnvironment,
            rules: () -> ApprovalRules = { ApprovalRules.Default },
        ): AcpStart {
            // The command is looked up on the PATH the process gets, so a preset can set that too.
            val launched = environment.with(harness.environment)
            val executable = launched.resolveCommand(harness.command)
                ?: return AcpStart.Failed("'${harness.command}' was not found on PATH.")

            val process = try {
                GeneralCommandLine(executable)
                    .withParameters(harness.args)
                    .withWorkingDirectory(workingDirectory)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                    .withEnvironment(launched.variables)
                    .withCharset(StandardCharsets.UTF_8)
                    .createProcess()
            } catch (e: ExecutionException) {
                return AcpStart.Failed("'${harness.command}' could not be started: ${e.message}")
            }

            val connection = AcpAgentConnection(
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)),
                OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
                process,
                prompt,
                events,
                server.name,
                rules,
            )
            thread(isDaemon = true, name = "Pacenote ACP stderr") {
                try {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine(connection::noteStderr)
                } catch (_: IOException) {
                }
            }

            var started = false
            try {
                val failure = connection.open(workingDirectory.toString(), server, harness.askingMode, harness.sessionMeta)
                    ?: harness.chatMode?.takeIf { !connection.offers(it) }?.let { "The agent offers no '$it' mode." }
                if (failure == null) {
                    started = true
                    return AcpStart.Started(connection)
                }
                return failed(connection, failure)
            } finally {
                if (!started) connection.dispose()
            }
        }

        private fun failed(connection: AcpAgentConnection, failure: String): AcpStart {
            connection.dispose()
            val tail = connection.stderrTail
            return AcpStart.Failed(if (tail.isEmpty()) failure else "$failure\n$tail")
        }

        /** A connection over given streams, for tests: no process to own. */
        internal fun over(
            incoming: BufferedReader,
            outgoing: Writer,
            prompt: AcpPermissionPrompt,
            events: AcpConnectionEvents,
            ownServer: String,
            rules: () -> ApprovalRules = { ApprovalRules.Default },
        ): AcpAgentConnection = AcpAgentConnection(incoming, outgoing, null, prompt, events, ownServer, rules)

        private fun at(root: JsonElement?, vararg keys: String): JsonElement? {
            var node = root
            for (key in keys) node = (node as? JsonObject)?.get(key) ?: return null
            return node
        }
    }
}
