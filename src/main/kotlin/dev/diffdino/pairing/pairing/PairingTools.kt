package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.mcp.AgentTool
import dev.diffdino.pairing.mcp.ToolInvocation
import dev.diffdino.pairing.mcp.ToolJson
import dev.diffdino.pairing.model.DraftPlace
import dev.diffdino.pairing.model.DraftRequest
import dev.diffdino.pairing.model.DraftSpan
import dev.diffdino.pairing.model.EditorCaret
import dev.diffdino.pairing.model.Milestone
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.model.RoadmapChange
import dev.diffdino.pairing.model.StopLocation
import dev.diffdino.pairing.model.StopTarget
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The pairing loop's tools over the project's live session: revise the roadmap, open a stop, read
 * where the user is, end. None of them waits on the user: their moves reach the agent as its next
 * turn. Thin adapters over [PairingStore], whose state lives on the conversation's thread: every
 * call hops there first. None of them writes the user's code.
 */
object PairingTools {
    fun createAll(sessions: PairingSessionAccess, ui: CoroutineContext): List<AgentTool> {
        val target = PairingTarget(sessions, ui)
        return listOf(
            PairingStartTool(target),
            PairingRoadmapTool(target),
            PairingStopTool(target),
            PairingStateTool(target),
            PairingSayTool(target),
            PairingEndTool(target),
        )
    }

    internal fun JsonObjectBuilder.putCaret(caret: EditorCaret, relative: (String) -> String?) {
        put("caret", buildJsonObject {
            put("path", relative(caret.path) ?: caret.path)
            put("line", caret.at.line.value)
            put("column", caret.at.column + 1)
            if (caret.selectedText.isNotEmpty()) put("selection", caret.selectedText)
        })
    }
}

/** The project's live pairing session, reached on the conversation's thread. */
class PairingTarget(private val sessions: PairingSessionAccess, private val ui: CoroutineContext) {
    /** Runs [work] against the session's store on the conversation's thread, or answers that there
     * is no session. */
    suspend fun onStore(work: suspend (PairingStore) -> ToolInvocation): ToolInvocation = withContext(ui) {
        val store = sessions.storeFor()
            ?: return@withContext ToolInvocation.error(
                "No pairing session is running for this project. Call pairing_start once the user asks to pair, " +
                    "or they start one from the DiffDino tool window with New pairing session.")
        work(store)
    }

    /** Opens a session for the project on the conversation's thread. */
    suspend fun start(goal: String): AgentPairingStart = withContext(ui) { sessions.startByAgent(goal) }
}

/** Opens a pairing session in the conversation the user is having with the agent. */
internal class PairingStartTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_start"

    override val description =
        "Starts a pairing session on the repository, in the conversation the user is having with you in " +
            "the DiffDino tool window: call it once the user asks to pair on a change, or agrees to your offer to. " +
            "The Pairing panel shows the goal; you navigate with the other pairing_* tools and the user " +
            "writes the code. Returns how the session runs; follow it, starting with pairing_roadmap."

    override val jsonSchema =
        """{"type":"object","properties":{"goal":{"type":"string","description":"The change to make, in a sentence or two, as the user would put it."}},"required":["goal"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val goal = ToolJson.string(args, "goal")
        if (goal.isNullOrBlank()) return ToolInvocation.error("goal must be a non-empty string.")
        return when (val started = target.start(goal)) {
            is AgentPairingStart.Started -> ToolInvocation.ok(ToolJson.write { put("instructions", PairingInstructions.started) })
            is AgentPairingStart.AlreadyRunning -> ToolInvocation.error(
                "A pairing session is already running: \"${started.store.goal}\". Carry it on, or call pairing_end first.")
            AgentPairingStart.NoConversation -> ToolInvocation.error(
                "No conversation with an agent is open for this project in the DiffDino tool window, so there is nowhere " +
                    "to show the session. The user starts one there with New pairing session.")
        }
    }
}

/** Replaces the roadmap shown in the Pairing panel. */
internal class PairingRoadmapTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_roadmap"

    override val description =
        "Replaces the pairing roadmap: 3 to 7 coarse milestones toward the goal, no code. The panel " +
            "marks what this revision added and dropped. Send it at the start, and again whenever the " +
            "user's code departs from the plan. Mark milestones the user has finished as done."

    override val jsonSchema =
        """{"type":"object","properties":{"milestones":{"type":"array","minItems":1,"items":{"type":"object","properties":{"title":{"type":"string","description":"One line, no code."},"done":{"type":"boolean","description":"Finished by the user. Default false."}},"required":["title"],"additionalProperties":false},"description":"The milestones, in order."}},"required":["milestones"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val list = args["milestones"] as? JsonArray ?: return ToolInvocation.error("milestones must be an array.")
        val milestones = ArrayList<Milestone>()
        for ((index, item) in list.withIndex()) {
            val title = (item as? JsonObject)?.let { ToolJson.string(it, "title") }
            if (title.isNullOrEmpty()) return ToolInvocation.error("milestones[$index].title must be a non-empty string.")
            milestones.add(Milestone(title.trim(), ToolJson.bool(item, "done", false)))
        }

        if (milestones.isEmpty()) return ToolInvocation.error("Send at least one milestone.")
        return target.onStore { store ->
            store.setRoadmap(milestones)
            ToolInvocation.ok(ToolJson.write { put("milestones", milestones.size) })
        }
    }
}

/** Opens the next stop: the file, the declaration, and why this is the next place. */
internal class PairingStopTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_stop"

    override val description =
        "Takes the user to the next place to change: opens the file in the IDE's editor with the " +
            "caret on the named declaration, shows a card with the title and the reason, and draws " +
            "your code for this stop into the file as grey suggested lines. The user accepts it with " +
            "one click or types it themselves; either way it comes back as done. code is ONE block for " +
            "this stop only. By default it replaces the whole declaration from the line with its name " +
            "to its last line (so it starts with the signature, without doc comments or attributes " +
            "above it); for a declaration that doesn't exist yet it goes in after the one named in " +
            "after; for a file that doesn't exist yet the file is created empty and code is its first " +
            "block only: the imports and the outline of its type, with members added at later stops. " +
            "For a small edit inside a " +
            "large declaration, pass lines (the lines code replaces) or after_line (the line code goes " +
            "in after) instead, as numbered in the file now. Lines at either end of code that read the " +
            "same as the file are trimmed off, so the lines the reply names can be narrower than what " +
            "you sent: that is expected, the stop is shown as it should be, and it needs no resending. " +
            "Returns at once; then end your turn: the user's move comes to you as your next message. " +
            "Send a stop again with replace: true only when the user asks for different code, or the " +
            "reply shows it landed in a different declaration than you meant. Name the declaration, " +
            "never a line number: \"Class.Method\" or \"Method\". Fails while another stop is open, " +
            "unless replace is true."

    override val jsonSchema =
        """{"type":"object","properties":{"path":{"type":"string","description":"Repo-relative path of the file."},"symbol":{"type":"string","description":"The declaration to put the user on, e.g. Client.Fetch."},"after":{"type":"string","description":"For a declaration that doesn't exist yet: the declaration it goes after."},"title":{"type":"string","description":"One line: what to do here."},"reason":{"type":"string","description":"Markdown: why this is the next place, and what the change has to achieve. No code: the code goes in code."},"code":{"type":"string","description":"Your code for this stop: one block, exactly as it should read in the file, indented to fit."},"lines":{"type":"object","properties":{"from":{"type":"integer","minimum":1},"to":{"type":"integer","minimum":1}},"required":["from","to"],"additionalProperties":false,"description":"The lines code replaces, 1-based and inclusive, when not the whole declaration."},"after_line":{"type":"integer","minimum":1,"description":"The line code goes in after, when it replaces nothing."},"replace":{"type":"boolean","description":"Take back the open stop and open this one instead. Default false."}},"required":["path","symbol","title","reason","code"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        fun required(name: String): String? = ToolJson.string(args, name)?.takeIf { it.isNotEmpty() }
        val givenPath = required("path") ?: return ToolInvocation.error("path must be a non-empty string.")
        val symbol = required("symbol") ?: return ToolInvocation.error("symbol must be a non-empty string.")
        val givenTitle = required("title") ?: return ToolInvocation.error("title must be a non-empty string.")
        val givenReason = required("reason") ?: return ToolInvocation.error("reason must be a non-empty string.")

        val code = ToolJson.string(args, "code") ?: return ToolInvocation.error("code must be a string.")

        val linesArg = args["lines"]
        val afterArg = args["after_line"]
        if (linesArg != null && afterArg != null) return ToolInvocation.error("Pass lines or after_line, not both.")
        val span = when {
            linesArg != null -> {
                val lines = linesArg as? JsonObject
                val from = ToolJson.int(lines?.get("from"))
                val to = ToolJson.int(lines?.get("to"))
                if (from == null || to == null) return ToolInvocation.error("lines needs integers from and to.")
                DraftSpan.Lines(from, to)
            }
            afterArg != null -> DraftSpan.After(ToolJson.int(afterArg) ?: return ToolInvocation.error("after_line must be an integer."))
            else -> DraftSpan.Declaration
        }

        val path = givenPath.trim().replace('\\', '/').trimStart('/')
        val stopTarget = StopTarget(path, symbol.trim(), ToolJson.string(args, "after")?.trim())
        val title = givenTitle.trim()
        val reason = givenReason.trim()
        val replace = ToolJson.bool(args, "replace", false)

        return target.onStore { store ->
            when (val opening = store.openStop(stopTarget, title, reason, DraftRequest(code, span), replace)) {
                is StopOpening.Opened -> ToolInvocation.ok(describe(opening.stop, stopTarget.path))
                is StopOpening.Refused -> ToolInvocation.error(opening.message)
            }
        }
    }

    private fun describe(open: OpenStop, path: String): String = ToolJson.write {
        put("stop", open.stop.number)
        put("path", path)
        when (val location = open.location) {
            is StopLocation.OnSymbol -> {
                put("placed", "on_symbol")
                put("line", location.at.line.value)
                put("line_text", location.lineText)
            }
            is StopLocation.Insertion -> {
                put("placed", "after")
                put("line", location.at.line.value)
                put("after", location.after)
            }
            is StopLocation.NewFile -> {
                put("placed", "new_file")
                put("created", "The file was created empty and opened; your code is its first block.")
            }
        }

        when (val place = open.draft.place) {
            is DraftPlace.Replace -> put("code_replaces", "lines ${place.lines.from}-${place.lines.to}")
            is DraftPlace.InsertAfter -> put("code_goes", "after line ${place.line.value}")
        }

        put("next",
            "The stop is shown. Lines your code left as they were are trimmed, so the span above can be narrower than what " +
                "you sent; don't send it again. End your turn: the user's move comes to you as your next message.")
    }
}

/** Where the session and the user are. */
internal class PairingStateTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_state"

    override val description =
        "Reads the pairing session: the goal, the roadmap, the open stop, and where the user's caret " +
            "is with what they have selected."

    override val jsonSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation = target.onStore { store ->
        ToolInvocation.ok(ToolJson.write {
            put("goal", store.goal)
            put("phase", when (store.phase.value) {
                PairingPhase.Starting -> "starting"
                is PairingPhase.Running -> "running"
                is PairingPhase.Disconnected -> "disconnected"
                is PairingPhase.Ended -> "ended"
                is PairingPhase.Failed -> "failed"
            })
            putJsonArray("roadmap") {
                for (entry in store.roadmap.value) {
                    if (entry.change == RoadmapChange.Removed) continue
                    addJsonObject {
                        put("title", entry.title)
                        put("done", entry.done)
                    }
                }
            }
            store.stop.value?.let { open ->
                put("stop", buildJsonObject {
                    put("number", open.stop.number)
                    put("title", open.stop.title)
                    put("path", open.stop.target.path)
                    put("symbol", open.stop.target.symbol)
                })
            }
            store.caret.value?.let { caret -> with(PairingTools) { putCaret(caret, store::relative) } }
        })
    }
}

/** Ends the session. */
internal class PairingEndTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_end"

    override val description =
        "Ends the pairing session once the roadmap is done, or when the user asks to stop. The summary " +
            "stays up in the Pairing panel."

    override val jsonSchema =
        """{"type":"object","properties":{"summary_md":{"type":"string","description":"Markdown: what was built, and anything left for later."}},"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val summary = ToolJson.string(args, "summary_md")
        return target.onStore { store ->
            store.end(summary)
            ToolInvocation.ok(ToolJson.write { put("ok", true) })
        }
    }
}

/** Says something to the user in the Pairing panel's conversation. */
internal class PairingSayTool(private val target: PairingTarget) : AgentTool {
    override val name = "pairing_say"

    override val description =
        "Says something to the user in the Pairing panel's conversation: the answer to a message, or " +
            "a short remark about their diff. Say it here, and only here: prose outside the tools may never " +
            "reach the user, and where it does, repeating it there shows them the same answer twice. Markdown; keep it brief: the stop's code goes in pairing_stop, " +
            "not here. Returns at once; end your turn once you have nothing more to do, and the user's answer comes to you as your next message."

    override val jsonSchema =
        """{"type":"object","properties":{"text_md":{"type":"string","description":"What to say, as markdown."}},"required":["text_md"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val text = ToolJson.string(args, "text_md")
        if (text.isNullOrEmpty()) return ToolInvocation.error("text_md must be a non-empty string.")
        return target.onStore { store ->
            store.addReply(text)
            ToolInvocation.ok(ToolJson.write { put("ok", true) })
        }
    }
}
