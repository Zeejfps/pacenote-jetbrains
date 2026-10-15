package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.mcp.AgentTool
import dev.diffdino.pairing.mcp.ToolInvocation
import dev.diffdino.pairing.mcp.ToolJson
import dev.diffdino.pairing.model.CodeSpotlights
import dev.diffdino.pairing.model.ShowOutcome
import dev.diffdino.pairing.model.ShowTarget
import dev.diffdino.pairing.model.SpotlightRun
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The tools that point the user at code in the project's editor (open a file on a declaration or a
 * line, light runs of lines up) whether or not a pairing session is running. They change the
 * screen, never the project, so none pauses for approval.
 */
object EditorPresentationTools {
    /** [spotlights] answers the editor's side, or null where the IDE offers none. */
    fun createAll(root: Path, spotlights: () -> CodeSpotlights?, ui: CoroutineContext): List<AgentTool> {
        val access = EditorAccess(root, spotlights, ui)
        return listOf(EditorShowTool(access), EditorSpotlightTool(access), EditorClearTool(access))
    }
}

/** Reaches the project's editor. */
internal class EditorAccess(private val root: Path, private val spotlights: () -> CodeSpotlights?, private val ui: CoroutineContext) {
    val noEditor = "The DiffDino plugin has no editor to show code in for this project."

    /** A repo-relative path argument, normalized, or null when it leads outside the project. */
    fun relative(given: String): String? {
        val path = given.trim().replace('\\', '/').trimStart('/')
        return try {
            val base = root.toAbsolutePath().normalize()
            if (base.resolve(path).normalize().startsWith(base)) path else null
        } catch (_: InvalidPathException) {
            null
        }
    }

    suspend fun <T> withEditor(work: suspend (CodeSpotlights) -> T): T? {
        val editor = withContext(ui) { spotlights() } ?: return null
        return work(editor)
    }

    suspend fun clear(): Boolean = withContext(ui) {
        val editor = spotlights() ?: return@withContext false
        editor.clear()
        true
    }

    fun report(outcome: ShowOutcome): ToolInvocation = when (outcome) {
        is ShowOutcome.Shown -> ToolInvocation.ok(ToolJson.write {
            put("path", outcome.relativePath)
            put("line", outcome.line)
            put("on_screen", outcome.onScreen)
            if (!outcome.onScreen)
                put("note", "The IDE window is not in front; the file is open there for when the user comes back.")
        })
        is ShowOutcome.Missed -> ToolInvocation.error(outcome.message)
    }

    companion object {
        /** A line argument: absent, or a number of 1 or more. */
        fun parseLine(args: JsonObject, name: String): LineArgument {
            val value = args[name]
            if (value == null || value is JsonNull) return LineArgument.Absent
            val number = ToolJson.int(value)
            return if (number != null && number >= 1) LineArgument.Given(number)
            else LineArgument.Invalid("Argument '$name' must be a line number of 1 or more (got $value).")
        }
    }
}

internal sealed interface LineArgument {
    data object Absent : LineArgument

    data class Given(val line: Int) : LineArgument

    data class Invalid(val message: String) : LineArgument
}

/** Opens a file for the user to look at, on a declaration or a line. */
internal class EditorShowTool(private val access: EditorAccess) : AgentTool {
    override val name = "editor_show"

    override val description =
        "Opens a file in the user's IDE for them to look at, with the caret on a " +
            "declaration or a line: where something happens, a caller, a test — whatever they asked " +
            "to see. Name a declaration in symbol, or pass line; with neither, the file opens at its " +
            "top. Works any time, in a pairing session or not; during one it only moves the editor and " +
            "the open stop stays open. Returns the line it landed on — check it is the one you meant. " +
            "To light up the lines themselves, use editor_spotlight instead."

    override val jsonSchema =
        """{"type":"object","properties":{"path":{"type":"string","description":"Repo-relative path of the file."},"symbol":{"type":"string","description":"The declaration to put the caret on, e.g. Client.Fetch."},"line":{"type":"integer","minimum":1,"description":"The 1-based line to put the caret on, when no symbol is named."}},"required":["path"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val given = ToolJson.string(args, "path")?.takeIf { it.isNotEmpty() } ?: return ToolInvocation.error("Argument 'path' is required.")
        val path = access.relative(given) ?: return ToolInvocation.error("$given is outside the repository.")
        val symbol = ToolJson.string(args, "symbol")?.trim()?.takeIf { it.isNotEmpty() }
        val target = if (symbol != null) ShowTarget.Symbol(symbol) else when (val line = EditorAccess.parseLine(args, "line")) {
            LineArgument.Absent -> ShowTarget.Line(1)
            is LineArgument.Given -> ShowTarget.Line(line.line)
            is LineArgument.Invalid -> return ToolInvocation.error(line.message)
        }

        val outcome = access.withEditor { it.show(path, target) } ?: return ToolInvocation.error(access.noEditor)
        return access.report(outcome)
    }
}

/** Opens a file and lights up runs of lines in it with numbered pins. */
internal class EditorSpotlightTool(private val access: EditorAccess) : AgentTool {
    override val name = "editor_spotlight"

    override val description =
        "Opens a file in the user's IDE and lights up runs of lines in it with numbered pins, " +
            "scrolled to the first run. Use it to answer " +
            "\"where does X happen?\": light the lines that do it, with a short note on each, then " +
            "explain in your reply by pin number. Each run is a declaration by name (symbol) or a line " +
            "range (from, to). One file at a time: a later call replaces the set, editor_clear removes " +
            "it. The runs follow the lines as the user types. Returns where the first run landed — check " +
            "it is what you meant."

    override val jsonSchema =
        """{"type":"object","properties":{"path":{"type":"string","description":"Repo-relative path of the file."},"spotlights":{"type":"array","minItems":1,"items":{"type":"object","properties":{"symbol":{"type":"string","description":"A declaration to light whole, e.g. Client.Fetch. Instead of from/to."},"from":{"type":"integer","minimum":1,"description":"First line, 1-based."},"to":{"type":"integer","minimum":1,"description":"Last line, inclusive. Default: from."},"note":{"type":"string","description":"A few words on what this run is, drawn beside its pin."}},"additionalProperties":false},"description":"The runs to light, numbered in order."}},"required":["path","spotlights"],"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation {
        val given = ToolJson.string(args, "path")?.takeIf { it.isNotEmpty() } ?: return ToolInvocation.error("Argument 'path' is required.")
        val path = access.relative(given) ?: return ToolInvocation.error("$given is outside the repository.")
        val entries = args["spotlights"] as? JsonArray
        if (entries == null || entries.isEmpty())
            return ToolInvocation.error("Argument 'spotlights' must be a non-empty array of {symbol} or {from, to?}, each with an optional note.")

        val runs = ArrayList<SpotlightRun>(entries.size)
        for ((index, entry) in entries.withIndex()) {
            when (val parsed = parse(entry, index)) {
                is RunParse.Ok -> runs.add(parsed.run)
                is RunParse.Invalid -> return ToolInvocation.error(parsed.message)
            }
        }

        val outcome = access.withEditor { it.spotlight(path, runs) } ?: return ToolInvocation.error(access.noEditor)
        return access.report(outcome)
    }

    private sealed interface RunParse {
        data class Ok(val run: SpotlightRun) : RunParse

        data class Invalid(val message: String) : RunParse
    }

    // One entry parsed at the boundary: the run, or the precise complaint about it.
    private fun parse(entry: JsonElement, index: Int): RunParse {
        val where = "spotlights[$index]"
        val body = entry as? JsonObject ?: return RunParse.Invalid("$where must be an object {symbol} or {from, to?}, with an optional note.")

        val note = ToolJson.string(body, "note")?.trim().orEmpty()
        val symbol = ToolJson.string(body, "symbol")?.trim()?.takeIf { it.isNotEmpty() }
        if (symbol != null) return RunParse.Ok(SpotlightRun.Symbol(symbol, note))

        val first = when (val from = EditorAccess.parseLine(body, "from")) {
            LineArgument.Absent -> return RunParse.Invalid("$where: name a symbol, or pass 'from'.")
            is LineArgument.Given -> from.line
            is LineArgument.Invalid -> return RunParse.Invalid("$where: ${from.message}")
        }
        val last = when (val to = EditorAccess.parseLine(body, "to")) {
            LineArgument.Absent -> null
            is LineArgument.Given -> to.line
            is LineArgument.Invalid -> return RunParse.Invalid("$where: ${to.message}")
        }
        if (last != null && last < first) return RunParse.Invalid("$where: 'to' ($last) is before 'from' ($first).")
        return RunParse.Ok(SpotlightRun.Lines(first, last, note))
    }
}

internal class EditorClearTool(private val access: EditorAccess) : AgentTool {
    override val name = "editor_clear"

    override val description = "Removes the lines editor_spotlight lit up. The file stays open where it is."

    override val jsonSchema = """{"type":"object","properties":{},"additionalProperties":false}"""

    override val isWrite = false

    override suspend fun invoke(args: JsonObject): ToolInvocation =
        if (access.clear()) ToolInvocation.ok("""{"ok":true}""") else ToolInvocation.error(access.noEditor)
}
