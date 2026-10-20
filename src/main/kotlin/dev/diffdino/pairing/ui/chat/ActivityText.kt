package dev.diffdino.pairing.ui.chat

import dev.diffdino.pairing.pairing.AgentActivity
import dev.diffdino.pairing.pairing.AgentDoing
import dev.diffdino.pairing.pairing.AgentStepKind
import dev.diffdino.pairing.ui.support.DiffDinoBundle

/** The activity line as words: what the agent is at, how long it has been at it, and whether it has
 * gone quiet. [full] is the step's whole title where [text] cut it short. */
data class ActivityLine(
    val kind: AgentStepKind?,
    val text: String,
    val elapsed: String,
    val more: String?,
    val quiet: String?,
    val full: String? = null,
)

object ActivityText {
    /** Prose this fresh is its own sign of life, so the line stays away while it streams. */
    const val WRITING_MILLIS = 2_000L

    /** Nothing heard for this long, with no step running, is said. */
    const val QUIET_MILLIS = 90_000L

    /** A step's title past this many characters is cut short. */
    const val MAX_TITLE = 80

    /** The line to show at [now], or null for none: between turns, and, unless a step still runs,
     * while the agent's prose streams or it waits on the user. A running step is always shown. */
    fun of(activity: AgentActivity?, working: Boolean, now: Long): ActivityLine? {
        if (activity == null) return null
        val step = activity.current
        if (activity.stopping) return ActivityLine(null, DiffDinoBundle.message("activity.stopping"), "", null, null)
        if (step != null) {
            val more = (activity.steps.size - 1).takeIf { it > 0 }?.let { DiffDinoBundle.message("activity.more", it) }
            val title = title(step.title, step.kind)
            val shown = if (title.length > MAX_TITLE) title.take(MAX_TITLE - 1).trimEnd() + "…" else title
            return ActivityLine(step.kind, shown, duration(now - step.startedAt), more, null, title.takeIf { shown != it })
        }

        if (!working) return null
        val sinceHeard = now - activity.lastHeard
        if (activity.doing == AgentDoing.Writing && sinceHeard < WRITING_MILLIS) return null
        val (text, from) = when (activity.doing) {
            AgentDoing.Thinking -> DiffDinoBundle.message("activity.thinking") to activity.since
            AgentDoing.Working -> DiffDinoBundle.message("activity.working") to activity.since
            AgentDoing.Writing -> DiffDinoBundle.message("activity.working") to activity.lastHeard
        }
        val quiet = if (sinceHeard >= QUIET_MILLIS) DiffDinoBundle.message("activity.quiet", sinceHeard / 60_000) else null
        return ActivityLine(null, text, duration(now - from), null, quiet)
    }

    /** A step's title on one line, without the backticks the agent marks code with; or what it does, without one. */
    fun title(title: String, kind: AgentStepKind): String {
        val plain = title.replace("`", "").replace(Regex("\\s+"), " ").trim()
        return plain.ifEmpty {
            DiffDinoBundle.message(
                when (kind) {
                    AgentStepKind.Run -> "activity.step.run"
                    AgentStepKind.Read -> "activity.step.read"
                    AgentStepKind.Edit -> "activity.step.edit"
                    AgentStepKind.Search -> "activity.step.search"
                    AgentStepKind.Fetch -> "activity.step.fetch"
                    AgentStepKind.Think -> "activity.thinking"
                    AgentStepKind.Other -> "activity.step.other"
                },
            )
        }
    }

    /** m:ss, or h:mm:ss past an hour. */
    fun duration(millis: Long): String {
        val seconds = maxOf(0L, millis) / 1000
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
