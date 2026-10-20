package dev.diffdino.pairing.acp

import dev.diffdino.pairing.acp.AcpJsonRpc.Companion.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One option an agent offered on a permission request. */
data class AcpPermissionOption(val optionId: String, val name: String, val kind: AcpPermissionOptionKind)

enum class AcpPermissionOptionKind { AllowOnce, AllowAlways, RejectOnce, RejectAlways }

/** What a tool call does, as the agent classifies it. */
enum class AcpToolKind { Read, Edit, Delete, Move, Search, Execute, Think, Fetch, SwitchMode, Other }

/**
 * A `session/request_permission` as the policy reads it: the tool call it is about, the options
 * offered, and whatever the adapter says about which MCP server the tool belongs to. Adapters name
 * that differently (Claude on the tool call, Codex on the earlier `tool_call` update, Gemini only
 * in its "Always Allow <server>" option), so the parse collects every marker into [mcpServer].
 * [command] is the command line of a shell call, where the adapter passes it in the tool call's
 * [input]. [edits] are the changes a file edit would make, and [paths] every file the call names.
 */
data class AcpPermissionRequest(
    val toolCallId: String,
    val title: String,
    val kind: AcpToolKind,
    val mcpServer: String?,
    val isMcpApproval: Boolean,
    val options: List<AcpPermissionOption>,
    val command: String? = null,
    val edits: List<AcpFileEdit> = emptyList(),
    val paths: List<String> = emptyList(),
    /** The tool call's input, as the agent passed it. */
    val input: JsonObject? = null,
)

/** One change an edit would make to a file: the text it replaces, none when it writes the whole
 * file, and the text it puts there. */
data class AcpFileEdit(val path: String, val oldText: String?, val newText: String)

/** What the client answers a permission request with. */
sealed interface AcpPermissionDecision {
    data class Select(val optionId: String, val verdict: AcpPermissionVerdict) : AcpPermissionDecision

    /** The policy has no opinion: the user decides. */
    data object AskUser : AcpPermissionDecision
}

enum class AcpPermissionVerdict { Allowed, Rejected }

/**
 * The write guard for an agent run over ACP: reads and shell commands are allowed (the agent runs
 * the tests, formatters and generators itself) except a push, which is put to the user; file edits
 * are put to the user too, and the plugin's own MCP tools are allowed each time they are asked
 * about. The same rules for every harness, which is why the guard lives in the client rather than
 * in each CLI's flags.
 */
object AcpPermissionPolicy {
    fun decide(request: AcpPermissionRequest, ownServer: String): AcpPermissionDecision {
        // Once, every time, rather than always: Claude Code writes an "always" into the repository's
        // own .claude/settings.local.json, which is the user's file, not the session's.
        if (request.mcpServer == null && request.isMcpApproval) return AcpPermissionDecision.AskUser
        if (request.mcpServer != null && request.mcpServer.equals(ownServer, ignoreCase = true)) return allowOnce(request)

        // Codex files another server's MCP tool calls as execute: only a shell command is the agent's own.
        return when (request.kind) {
            AcpToolKind.Execute -> when {
                request.mcpServer != null -> AcpPermissionDecision.AskUser
                pushes(request.command ?: request.title) -> AcpPermissionDecision.AskUser
                else -> allowOnce(request)
            }
            AcpToolKind.Read, AcpToolKind.Search, AcpToolKind.Think, AcpToolKind.Fetch -> allowOnce(request)
            AcpToolKind.Edit, AcpToolKind.Delete, AcpToolKind.Move, AcpToolKind.SwitchMode, AcpToolKind.Other ->
                AcpPermissionDecision.AskUser
        }
    }

    private val gitPush = Regex(
        """(?<![\w-])git(?:\.exe)?(?:\s+(?:-C|-c|--git-dir|--work-tree)\s+\S+|\s+-\S+)*\s+push\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a command line runs `git push` anywhere in it, global options between the two words
     * included. */
    fun pushes(command: String): Boolean = gitPush.containsMatchIn(command)

    private fun allowOnce(request: AcpPermissionRequest): AcpPermissionDecision {
        for (wanted in listOf(AcpPermissionOptionKind.AllowOnce, AcpPermissionOptionKind.AllowAlways))
            for (option in request.options)
                if (option.kind == wanted) return AcpPermissionDecision.Select(option.optionId, AcpPermissionVerdict.Allowed)
        return AcpPermissionDecision.AskUser
    }

    /** Parses a request's params. [announcedServers] maps the tool call ids the agent announced in
     * `tool_call` updates to the MCP server they named, if any. */
    fun parse(parameters: JsonElement?, announcedServers: Map<String, String>): AcpPermissionRequest? {
        val root = parameters as? JsonObject ?: return null
        val toolCall = root["toolCall"] as? JsonObject ?: return null
        val id = text(toolCall["toolCallId"]) ?: return null

        val options = ArrayList<AcpPermissionOption>()
        for (node in (root["options"] as? JsonArray).orEmpty()) {
            val option = node as? JsonObject ?: continue
            val optionId = text(option["optionId"]) ?: continue
            val kind = parseOptionKind(text(option["kind"])) ?: continue
            options.add(AcpPermissionOption(optionId, text(option["name"]) ?: optionId, kind))
        }

        val server = text(path(toolCall, "_meta", "claudeCode", "mcpServer", "name"))
            ?: claudeToolServer(text(toolCall["name"]))
            ?: claudeToolServer(text(toolCall["title"]))
            ?: announcedServers[id]
            ?: geminiServer(root["options"])
        val isMcpApproval = (path(root, "_meta", "is_mcp_tool_approval") as? JsonPrimitive)?.booleanOrNull == true

        val edits = editsOf(toolCall)
        val paths = edits.map { it.path }.toMutableList()
        for (location in (toolCall["locations"] as? JsonArray).orEmpty())
            text((location as? JsonObject)?.get("path"))?.let(paths::add)

        return AcpPermissionRequest(
            id,
            text(toolCall["title"]) ?: "",
            parseToolKind(text(toolCall["kind"])),
            server,
            isMcpApproval,
            options,
            commandOf(toolCall["rawInput"]),
            edits,
            paths.distinct(),
            toolCall["rawInput"] as? JsonObject,
        )
    }

    // ACP's diff content where the adapter sends it; Claude Code's own Edit, MultiEdit and Write
    // input where it doesn't.
    private fun editsOf(toolCall: JsonObject): List<AcpFileEdit> {
        val edits = ArrayList<AcpFileEdit>()
        for (node in (toolCall["content"] as? JsonArray).orEmpty()) {
            val content = node as? JsonObject ?: continue
            if (text(content["type"]) != "diff") continue
            val path = text(content["path"]) ?: continue
            val newText = text(content["newText"]) ?: continue
            edits.add(AcpFileEdit(path, text(content["oldText"]), newText))
        }

        if (edits.isNotEmpty()) return edits
        val input = toolCall["rawInput"] as? JsonObject ?: return edits
        val file = text(input["file_path"]) ?: return edits
        val replacement = text(input["new_string"])
        val written = text(input["content"])
        val many = input["edits"] as? JsonArray
        when {
            replacement != null -> edits.add(AcpFileEdit(file, text(input["old_string"]) ?: "", replacement))
            written != null -> edits.add(AcpFileEdit(file, null, written))
            many != null -> for (edit in many) {
                val each = edit as? JsonObject ?: continue
                val newString = text(each["new_string"]) ?: continue
                edits.add(AcpFileEdit(file, text(each["old_string"]) ?: "", newString))
            }
        }
        return edits
    }

    // Claude and Gemini pass the command as a string; Codex as an argv array, which may be a shell
    // running a script.
    private fun commandOf(input: JsonElement?): String? = when (val command = (input as? JsonObject)?.get("command")) {
        is JsonPrimitive -> text(command)
        is JsonArray -> command.mapNotNull(::text).joinToString(" ")
        else -> null
    }

    /** The MCP server a `tool_call` update names, where the adapter says so there (Codex puts it in
     * `rawInput.server`). */
    fun announcedServer(update: JsonElement?): String? {
        val body = update as? JsonObject ?: return null
        if ((path(body, "_meta", "is_mcp_tool_call") as? JsonPrimitive)?.booleanOrNull == true)
            return text(path(body, "rawInput", "server"))
        return text(path(body, "_meta", "claudeCode", "mcpServer", "name"))
    }

    fun parseToolKind(kind: String?): AcpToolKind = when (kind) {
        "read" -> AcpToolKind.Read
        "edit" -> AcpToolKind.Edit
        "delete" -> AcpToolKind.Delete
        "move" -> AcpToolKind.Move
        "search" -> AcpToolKind.Search
        "execute" -> AcpToolKind.Execute
        "think" -> AcpToolKind.Think
        "fetch" -> AcpToolKind.Fetch
        "switch_mode" -> AcpToolKind.SwitchMode
        else -> AcpToolKind.Other
    }

    private fun parseOptionKind(kind: String?): AcpPermissionOptionKind? = when (kind) {
        "allow_once" -> AcpPermissionOptionKind.AllowOnce
        "allow_always" -> AcpPermissionOptionKind.AllowAlways
        "reject_once" -> AcpPermissionOptionKind.RejectOnce
        "reject_always" -> AcpPermissionOptionKind.RejectAlways
        else -> null
    }

    // Claude Code names MCP tools mcp__<server>__<tool>.
    private fun claudeToolServer(name: String?): String? {
        val prefix = "mcp__"
        if (name == null || !name.startsWith(prefix)) return null
        val end = name.indexOf("__", prefix.length)
        return if (end > prefix.length) name.substring(prefix.length, end) else null
    }

    // Gemini offers "Always Allow <server>" under the option id proceed_always_server.
    private fun geminiServer(options: JsonElement?): String? {
        val prefix = "Always Allow "
        for (node in (options as? JsonArray).orEmpty()) {
            val option = node as? JsonObject ?: continue
            if (text(option["optionId"]) != "proceed_always_server") continue
            val name = text(option["name"])
            if (name != null && name.startsWith(prefix)) return name.substring(prefix.length)
        }
        return null
    }

    private fun path(root: JsonObject, vararg keys: String): JsonElement? {
        var node: JsonElement? = root
        for (key in keys) node = (node as? JsonObject)?.get(key) ?: return null
        return node
    }
}
