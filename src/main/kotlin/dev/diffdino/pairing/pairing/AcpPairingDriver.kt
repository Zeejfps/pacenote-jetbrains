package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.acp.AcpAgentConnection
import dev.diffdino.pairing.acp.AcpClosedException
import dev.diffdino.pairing.acp.AcpConnectionEvents
import dev.diffdino.pairing.acp.AcpContent
import dev.diffdino.pairing.acp.AcpDirection
import dev.diffdino.pairing.acp.AcpEnvironment
import dev.diffdino.pairing.acp.AcpHarness
import dev.diffdino.pairing.acp.AcpLaunch
import dev.diffdino.pairing.acp.AcpMcpServer
import dev.diffdino.pairing.acp.AcpPermissionOptionKind
import dev.diffdino.pairing.acp.AcpPermissionPrompt
import dev.diffdino.pairing.acp.AcpPermissionRequest
import dev.diffdino.pairing.acp.AcpPermissionVerdict
import dev.diffdino.pairing.acp.AcpRpcException
import dev.diffdino.pairing.acp.AcpSessionUpdate
import dev.diffdino.pairing.acp.AcpStart
import dev.diffdino.pairing.acp.AcpStopReason
import dev.diffdino.pairing.acp.AcpToolKind
import dev.diffdino.pairing.acp.AcpTraffic
import dev.diffdino.pairing.model.CodeQuote
import dev.diffdino.pairing.settings.AgentArgumentsProblem
import dev.diffdino.pairing.settings.AgentPreset
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/** Where an agent the plugin starts reaches the plugin's MCP server. */
sealed interface AgentEndpoint {
    data class Listening(val url: URI) : AgentEndpoint

    data class Unavailable(val reason: String) : AgentEndpoint
}

/** Hands out the MCP endpoint an agent the plugin starts is given, starting the server if need be. */
fun interface AgentEndpoints {
    suspend fun ensure(): AgentEndpoint
}

/**
 * Runs a conversation's agent over ACP: starts the adapter against the plugin's MCP server, sends
 * the opening turn, and streams what the agent says into the transcript. Each turn after that is
 * what the user did or said next, so an agent waiting on the user costs nothing. While a pairing
 * session is live, a turn that ends leaving the user neither a stop nor a reply, through the tools
 * or in prose, gets a nudge to carry on, and an agent that keeps doing that is reported gone from
 * the session. The write guard lives in the connection; what it refused is shown, and what it has
 * no rule for is asked of the user in the transcript. Each turn runs in the mode its moment calls
 * for: the asking mode while a pairing session is live, the preset's chat mode otherwise.
 */
class AcpPairingDriver private constructor(
    private val conversation: AgentConversation,
    private val opening: AgentPrompt?,
    private val preset: AgentPreset,
    private val endpoints: AgentEndpoints,
    private val environment: () -> AcpEnvironment,
    private val ui: CoroutineContext,
) : AgentDriver, AcpPermissionPrompt, AcpConnectionEvents {
    private val label = preset.name
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<AgentPrompt>(Channel.UNLIMITED)
    private val allowedEdits = EditAllowances(conversation.root)
    private val refusedThisTurn = AtomicInteger()
    private val narratedThisTurn = AtomicInteger()
    private val trace = Trace.open()

    @Volatile
    private var connection: AcpAgentConnection? = null

    @Volatile
    private var stopped = false

    override fun tell(prompt: AgentPrompt) {
        inbox.trySend(prompt)
    }

    // A quote goes as the file's text attached to the turn where the agent takes that, and as
    // markdown in the prose where it doesn't.
    private fun content(connection: AcpAgentConnection, prompt: AgentPrompt): List<AcpContent> {
        val file = prompt.quote as? CodeQuote.InFile
        if (file == null || !connection.embedsContext) return listOf(AcpContent.Prose(prompt.toMarkdown(conversation.root)))
        val path = AgentPrompt.relativeTo(conversation.root, file.absolutePath)
        return listOf(
            AcpContent.Prose(prompt.text + "\n\n(Attached: what I selected in `$path`, ${file.lines}.)"),
            AcpContent.Resource(file.uri, file.text),
        )
    }

    private suspend fun run() {
        try {
            val harness = when (val launch = AcpHarness.forPreset(preset)) {
                is AcpLaunch.Ready -> launch.harness
                is AcpLaunch.Invalid -> return post { conversation.fail("$label could not be started. ${describe(launch.problem)}") }
            }

            val url = when (val endpoint = endpoints.ensure()) {
                is AgentEndpoint.Listening -> endpoint.url
                is AgentEndpoint.Unavailable -> return post { conversation.fail("DiffDino's MCP server is not available: ${endpoint.reason}") }
            }

            val started = AcpAgentConnection.start(
                harness, conversation.root, AcpMcpServer(SERVER_NAME, url), this, this, environment())
            val connection = when (started) {
                is AcpStart.Started -> started.connection
                is AcpStart.Failed -> return post { conversation.fail("$label could not be started. ${started.reason}") }
            }

            this.connection = connection
            if (stopped) return connection.dispose()
            scope.launch {
                connection.closed.await()
                onClosed(connection)
            }
            post { conversation.markRunning() }

            converse(connection, harness)
        } catch (_: AcpClosedException) {
            // onClosed reports it.
        } catch (_: ClosedReceiveChannelException) {
            // Disposed while waiting on the user.
        } catch (e: AcpRpcException) {
            post { conversation.fail("$label failed: ${e.message}") }
        }
    }

    private suspend fun converse(connection: AcpAgentConnection, harness: AcpHarness) {
        var prompt = opening ?: inbox.receive()
        var idle = 0
        var current = harness.askingMode
        while (!stopped) {
            refusedThisTurn.set(0)
            narratedThisTurn.set(0)
            val mode = harness.modeFor(onUi { conversation.isPairing })
            if (mode != current) {
                connection.setMode(mode)
                current = mode
            }

            post { conversation.beginTurn() }
            val reason = connection.prompt(content(connection, prompt))
            post { conversation.endTurn() }
            if (stopped) return

            var pairing = onUi { conversation.isPairing }
            if (reason == AcpStopReason.Refusal) {
                val refused = "$label refused to continue."
                if (pairing) post { conversation.session.value?.store?.markDisconnected(refused) }
                else post { conversation.transcript.addNotice(refused, NoticeTone.Error) }
                pairing = false
            }

            // What the user said, or a session they started, while the turn ran comes first.
            val queued = inbox.tryReceive().getOrNull()
            if (queued != null) {
                idle = 0
                prompt = queued
                continue
            }

            if (pairing) {
                val handedOver = narratedThisTurn.get() > 0 || onUi { conversation.session.value?.store?.hasHandedOver ?: true }
                idle = if (handedOver) 0 else idle + 1
                if (idle in 1 until IDLE_TURN_LIMIT) {
                    prompt = AgentPrompt(
                        if (refusedThisTurn.get() > 0) "The user declined: the code of the change goes through stops. " + PairingInstructions.CONTINUE
                        else PairingInstructions.CONTINUE)
                    continue
                }

                if (idle >= IDLE_TURN_LIMIT)
                    post { conversation.session.value?.store?.markDisconnected("$label stopped giving you stops or replies.") }
            }

            idle = 0
            prompt = inbox.receive()
        }
    }

    override fun updated(update: AcpSessionUpdate) {
        when (update) {
            is AcpSessionUpdate.MessageChunk -> {
                if (update.text.isEmpty()) return
                if (update.text.isNotBlank()) narratedThisTurn.set(1)
                post { conversation.transcript.appendNarration(update.text) }
            }
            is AcpSessionUpdate.ToolCall -> post { conversation.transcript.breakNarration() }
            is AcpSessionUpdate.ThoughtChunk, is AcpSessionUpdate.Plan, is AcpSessionUpdate.Other -> {}
        }
    }

    override fun permissionDecided(request: AcpPermissionRequest, verdict: AcpPermissionVerdict) {
        if (verdict != AcpPermissionVerdict.Rejected) return
        refusedThisTurn.incrementAndGet()
        val what = request.title.ifEmpty { request.kind.name }
        post { conversation.transcript.addNotice("Refused ${describe(request.kind)}: $what", NoticeTone.Refused) }
    }

    override fun traffic(traffic: AcpTraffic) {
        trace?.write(traffic)
    }

    override suspend fun ask(request: AcpPermissionRequest): String? {
        val pending = onUi { ask(request, conversation) } ?: return optionFor(request, approved = true)
        val approved = try {
            pending.await()
        } catch (e: CancellationException) {
            post { pending.cancel() }
            throw e
        }
        return optionFor(request, approved)
    }

    /** Puts the request in front of the user, or answers it here with none when it is an edit to
     * files they already allowed. On the conversation's thread. */
    private fun ask(request: AcpPermissionRequest, conversation: AgentConversation): PendingToolApproval? {
        val isEdit = request.kind == AcpToolKind.Edit && request.paths.isNotEmpty()
        if (isEdit && allowedEdits.cover(request.paths)) {
            val what = request.title.ifEmpty { request.paths.joinToString(", ") }
            conversation.transcript.add(PairingMessage.AllowedEdit(what, EditPreview.of(request.edits, conversation.root)))
            return null
        }

        return conversation.transcript.askPermission(
            request.title,
            request.command ?: request.kind.name,
            EditPreview.of(request.edits, conversation.root),
            if (isEdit) ({ allowedEdits.grant(request.paths) }) else null,
        )
    }

    private fun optionFor(request: AcpPermissionRequest, approved: Boolean): String? {
        val wanted = if (approved) listOf(AcpPermissionOptionKind.AllowOnce, AcpPermissionOptionKind.AllowAlways)
        else listOf(AcpPermissionOptionKind.RejectOnce, AcpPermissionOptionKind.RejectAlways)
        for (kind in wanted)
            for (option in request.options)
                if (option.kind == kind) return option.optionId
        return null
    }

    private fun onClosed(connection: AcpAgentConnection) {
        trace?.close()
        if (stopped) return
        val tail = connection.stderrTail
        post { conversation.fail(if (tail.isEmpty()) "$label exited." else "$label exited.\n$tail") }
    }

    private fun post(action: () -> Unit) {
        scope.launch(ui) { action() }
    }

    private suspend fun <T> onUi(work: () -> T): T = withContext(ui) { work() }

    /** Stops the agent: cancels the turn in flight and ends the process, in the background. */
    override fun dispose() {
        if (stopped) return
        stopped = true
        inbox.close()
        scope.cancel()
        val running = connection
        CoroutineScope(Dispatchers.IO).launch {
            running?.cancelTurn()
            running?.dispose()
            trace?.close()
        }
    }

    /** DIFFDINO_ACP_TRACE names a directory to write each session's protocol traffic into, one file
     * per session: the only way to see what an agent actually sent when the panel shows otherwise. */
    private class Trace(private val writer: PrintWriter) {
        private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun write(traffic: AcpTraffic) = synchronized(this) {
            val arrow = when (traffic.direction) {
                AcpDirection.Incoming -> "<<"
                AcpDirection.Outgoing -> ">>"
            }
            writer.println("${LocalDateTime.now().format(time)} $arrow ${traffic.line}")
            writer.flush()
        }

        fun close() = synchronized(this) { writer.close() }

        companion object {
            fun open(): Trace? {
                val directory = System.getenv("DIFFDINO_ACP_TRACE")?.takeIf { it.isNotEmpty() } ?: return null
                return try {
                    val folder = Files.createDirectories(Path.of(directory))
                    val name = "acp-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.log"
                    Trace(PrintWriter(Files.newBufferedWriter(folder.resolve(name))))
                } catch (_: IOException) {
                    null
                }
            }
        }
    }

    companion object {
        /** Turns in a row that leave the user nothing before the agent counts as gone. */
        private const val IDLE_TURN_LIMIT = 3

        /** The name the plugin's MCP server goes by in the session; the write guard allows its tools. */
        const val SERVER_NAME = "diffdino"

        /** Starts the agent for a conversation, with [opening] as its first turn, or with none to
         * wait for the user's. [ui] is the conversation's thread; [environment] is read off it. */
        fun start(
            conversation: AgentConversation,
            opening: AgentPrompt?,
            preset: AgentPreset,
            endpoints: AgentEndpoints,
            environment: () -> AcpEnvironment,
            ui: CoroutineContext,
        ): AcpPairingDriver {
            val driver = AcpPairingDriver(conversation, opening, preset, endpoints, environment, ui)
            driver.scope.launch { driver.run() }
            return driver
        }

        private fun describe(problem: AgentArgumentsProblem): String = when (problem) {
            AgentArgumentsProblem.UnclosedQuote -> "Its extra arguments leave a quote open."
            is AgentArgumentsProblem.NotAFlag -> "Its extra argument '${problem.token}' is not a --flag."
        }

        private fun describe(kind: AcpToolKind): String = when (kind) {
            AcpToolKind.Edit -> "an edit"
            AcpToolKind.Delete -> "a delete"
            AcpToolKind.Move -> "a move"
            AcpToolKind.Execute -> "a command"
            AcpToolKind.Read, AcpToolKind.Search, AcpToolKind.Think, AcpToolKind.Fetch, AcpToolKind.SwitchMode, AcpToolKind.Other ->
                "a tool call"
        }
    }
}
