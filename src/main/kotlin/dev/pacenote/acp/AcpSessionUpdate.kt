package dev.pacenote.acp

import dev.pacenote.acp.AcpJsonRpc.Companion.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** One block of a user turn. */
sealed interface AcpContent {
    data class Prose(val value: String) : AcpContent

    /** A file's text attached as context; [uri] may name lines in its fragment. */
    data class Resource(val uri: URI, val text: String) : AcpContent
}

/** What a `session/update` notification says, in the cases the plugin shows. */
sealed interface AcpSessionUpdate {
    /** A fragment of the agent's reply to the user. */
    data class MessageChunk(val text: String) : AcpSessionUpdate

    /** A fragment of the agent's reasoning. */
    data class ThoughtChunk(val text: String) : AcpSessionUpdate

    /** A tool call started, or changed title or status. */
    data class ToolCall(val id: String, val title: String?, val kind: AcpToolKind?, val status: AcpToolCallStatus?) : AcpSessionUpdate

    /** The agent's own plan, replaced whole. */
    data class Plan(val entries: List<String>) : AcpSessionUpdate

    /** Anything else: usage, commands, session info. */
    data class Other(val kind: String) : AcpSessionUpdate

    companion object {
        fun parse(update: JsonElement?): AcpSessionUpdate? {
            val body = update as? JsonObject ?: return null
            val kind = text(body["sessionUpdate"]) ?: return null
            return when (kind) {
                "agent_message_chunk" -> MessageChunk(contentText(body["content"]))
                "agent_thought_chunk" -> ThoughtChunk(contentText(body["content"]))
                "tool_call", "tool_call_update" -> {
                    val id = text(body["toolCallId"]) ?: return Other(kind)
                    ToolCall(
                        id,
                        text(body["title"]),
                        text(body["kind"])?.let(AcpPermissionPolicy::parseToolKind),
                        parseStatus(text(body["status"])),
                    )
                }
                "plan" -> Plan((body["entries"] as? JsonArray).orEmpty().mapNotNull { text((it as? JsonObject)?.get("content")) })
                else -> Other(kind)
            }
        }

        private fun parseStatus(status: String?): AcpToolCallStatus? = when (status) {
            "pending" -> AcpToolCallStatus.Pending
            "in_progress" -> AcpToolCallStatus.InProgress
            "completed" -> AcpToolCallStatus.Completed
            "failed" -> AcpToolCallStatus.Failed
            else -> null
        }

        private fun contentText(content: JsonElement?): String {
            val body = content as? JsonObject ?: return ""
            return if (text(body["type"]) == "text") text(body["text"]) ?: "" else ""
        }
    }
}

enum class AcpToolCallStatus { Pending, InProgress, Completed, Failed }

/** Why a prompt turn ended. */
enum class AcpStopReason { EndTurn, MaxTokens, MaxTurnRequests, Refusal, Cancelled }
