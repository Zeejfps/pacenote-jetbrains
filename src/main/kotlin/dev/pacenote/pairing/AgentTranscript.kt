package dev.pacenote.pairing

import dev.pacenote.acp.AcpFileEdit
import dev.pacenote.model.CodeQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user and the agent said to each other, across every pairing session of the conversation
 * and between them. Confined to the conversation's thread.
 */
class AgentTranscript {
    private val _messages = MutableStateFlow<List<PairingMessage>>(emptyList())
    private val _openNarration = MutableStateFlow<MutableStateFlow<String>?>(null)
    private var narrationBroken = false
    private var repliedThisTurn = false

    val messages: StateFlow<List<PairingMessage>> = _messages.asStateFlow()

    /** The text the agent's current turn is streaming onto, if any. */
    val openNarration: StateFlow<StateFlow<String>?> = _openNarration.asStateFlow()

    val count: Int get() = _messages.value.size

    /** The agent's turn begins: prose it writes is shown again. */
    fun beginAgentTurn() {
        repliedThisTurn = false
    }

    /** Streams the agent's prose onto the message its current turn is writing. Prose after a reply
     * in the same turn is not shown: it recaps the reply the user just read. */
    fun appendNarration(text: String) {
        if (repliedThisTurn) return
        val open = _openNarration.value
        if (open != null) {
            if (narrationBroken && text.isNotBlank()) {
                narrationBroken = false
                open.value = open.value.trimEnd() + "\n\n" + text.trimStart()
                return
            }
            open.value += text
            return
        }

        val lead = text.trimStart()
        if (lead.isEmpty()) return
        val opened = MutableStateFlow(lead)
        _openNarration.value = opened
        _messages.value += PairingMessage.Narration(opened.asStateFlow())
    }

    /** The agent did something between two runs of prose, a tool call: the next run starts a
     * paragraph of its own, since the agent's pieces don't end in a line break and would otherwise
     * run together, closing a code fence onto the next sentence. */
    fun breakNarration() {
        narrationBroken = _openNarration.value != null
    }

    /** The agent's turn ended; its next prose is a message of its own. */
    fun closeNarration() {
        narrationBroken = false
        _openNarration.value = null
    }

    /** A whole reply from the agent: a message of its own, whatever its turn streamed before it. */
    fun addReply(markdown: String) {
        if (markdown.isBlank()) return
        add(PairingMessage.Narration(MutableStateFlow(markdown.trim()).asStateFlow()))
        repliedThisTurn = true
    }

    fun addFromUser(text: String, quote: CodeQuote? = null) = add(PairingMessage.FromUser(text, quote))

    fun addNotice(text: String, tone: NoticeTone) = add(PairingMessage.Notice(text, tone))

    /** Puts a tool call the write guard has no rule for in front of the user. [allow], where given,
     * is what answering "all of these" covers, and [grant] what it grants on top of approving. */
    fun askPermission(
        title: String,
        details: String,
        preview: List<EditPreviewLine> = emptyList(),
        allow: AllowanceScope? = null,
        grant: () -> Unit = {},
        edits: List<AcpFileEdit> = emptyList(),
    ): PendingToolApproval {
        val pending = PendingToolApproval(title, details)
        add(PairingMessage.Approval(pending, preview, allow?.let { Allowance(pending, it, grant) }, edits))
        return pending
    }

    fun add(message: PairingMessage) {
        closeNarration()
        _messages.value += message
    }
}
