package dev.pacenote.ui.chat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pacenote.pairing.AgentActivity
import dev.pacenote.pairing.AgentDoing
import dev.pacenote.pairing.AgentStep
import dev.pacenote.pairing.AgentStepKind

/** What the activity line says, and when it says nothing. */
class ActivityTextTest : BasePlatformTestCase() {
    private fun activity(
        steps: List<AgentStep> = emptyList(),
        doing: AgentDoing = AgentDoing.Working,
        since: Long = 0,
        lastHeard: Long = 0,
        stopping: Boolean = false,
    ) = AgentActivity(steps, doing, since, lastHeard, stopping)

    private fun step(id: String, title: String, at: Long = 0, kind: AgentStepKind = AgentStepKind.Run) = AgentStep(id, title, kind, at)

    fun testARunningStepIsNamedWithHowLongItHasRun() {
        val line = ActivityText.of(activity(listOf(step("1", "`./gradlew test`", at = 1_000))), working = true, now = 73_000)!!

        assertEquals(AgentStepKind.Run, line.kind)
        assertEquals("./gradlew test", line.text)
        assertEquals("1:12", line.elapsed)
        assertNull(line.more)
        assertNull(line.quiet)
    }

    fun testOtherStepsRunningAreCounted() {
        val line = ActivityText.of(activity(listOf(step("1", "grep"), step("2", "cat"))), working = true, now = 0)!!

        assertEquals("cat", line.text)
        assertEquals("+1 more", line.more)
    }

    fun testAStepShowsEvenWhileTheUserHasTheMove() {
        assertNotNull(ActivityText.of(activity(listOf(step("1", "ls"))), working = false, now = 0))
        assertNull(ActivityText.of(activity(), working = false, now = 0))
    }

    fun testALongTitleIsCutShort_andKeptWholeForItsTooltip() {
        val long = "echo " + "x".repeat(200)

        val line = ActivityText.of(activity(listOf(step("1", long))), working = true, now = 0)!!

        assertEquals(ActivityText.MAX_TITLE, line.text.length)
        assertTrue(line.text.endsWith("…"))
        assertEquals(long, line.full)
    }

    fun testAStepWithNoTitleSaysWhatItDoes() {
        assertEquals("Running a command", ActivityText.title(" `` \n", AgentStepKind.Run))
        assertEquals("Reading", ActivityText.title("", AgentStepKind.Read))
    }

    fun testStreamingProseHidesTheLine_untilItPauses() {
        val writing = activity(doing = AgentDoing.Writing, since = 0, lastHeard = 10_000)

        assertNull(ActivityText.of(writing, working = true, now = 11_000))
        val paused = ActivityText.of(writing, working = true, now = 15_000)!!
        assertEquals("Working", paused.text)
        assertEquals("0:05", paused.elapsed)
    }

    fun testThinkingIsSaid() {
        assertEquals("Thinking", ActivityText.of(activity(doing = AgentDoing.Thinking), working = true, now = 0)!!.text)
    }

    fun testALongSilenceWithNoStepIsSaid() {
        val line = ActivityText.of(activity(lastHeard = 0), working = true, now = 125_000)!!

        assertEquals("no word from the agent for 2 min", line.quiet)
    }

    fun testStoppingSaysSo() {
        val line = ActivityText.of(activity(listOf(step("1", "ls")), stopping = true), working = true, now = 0)!!

        assertEquals("Stopping…", line.text)
        assertNull(line.kind)
    }

    fun testDurationsPastAnHourShowTheHours() {
        assertEquals("0:00", ActivityText.duration(-5))
        assertEquals("59:59", ActivityText.duration(3_599_000))
        assertEquals("1:00:01", ActivityText.duration(3_601_000))
    }
}
