package dev.diffdino.pairing.pairing

import dev.diffdino.pairing.acp.AcpToolCallStatus
import dev.diffdino.pairing.acp.AcpToolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A turn's activity: the steps still running, else what the agent last did, and since when. */
class AgentActivityTrackerTest {
    private var now = 1_000L
    private val tracker = AgentActivityTracker { now }
    private val activity: AgentActivity get() = tracker.activity.value!!

    @Test
    fun betweenTurns_thereIsNoActivity_andUpdatesAreIgnored() {
        tracker.toolCall("1", "ls", AcpToolKind.Execute, AcpToolCallStatus.InProgress)
        assertNull(tracker.activity.value)

        tracker.beginTurn()
        tracker.endTurn()

        assertNull(tracker.activity.value)
    }

    @Test
    fun aToolCall_isAStepUntilItCompletes() {
        tracker.beginTurn()
        now = 2_000
        tracker.toolCall("1", "`./gradlew test`", AcpToolKind.Execute, AcpToolCallStatus.Pending)

        assertEquals(AgentStep("1", "`./gradlew test`", AgentStepKind.Run, 2_000), activity.current)

        now = 9_000
        tracker.toolCall("1", null, null, AcpToolCallStatus.InProgress)
        assertEquals(2_000, activity.current!!.startedAt)

        tracker.toolCall("1", null, null, AcpToolCallStatus.Completed)
        assertTrue(activity.steps.isEmpty())
        assertEquals(AgentDoing.Working, activity.doing)
        assertEquals(9_000, activity.since)
    }

    @Test
    fun aFailedStep_endsToo_andALaterTitleReplacesTheFirst() {
        tracker.beginTurn()
        tracker.toolCall("1", "Read", AcpToolKind.Read, null)
        tracker.toolCall("1", "Read src/Client.kt", null, null)
        assertEquals("Read src/Client.kt", activity.current!!.title)

        tracker.toolCall("1", null, null, AcpToolCallStatus.Failed)

        assertTrue(activity.steps.isEmpty())
    }

    @Test
    fun aKindSentLater_isTaken() {
        tracker.beginTurn()
        tracker.toolCall("1", "ls", null, null)
        assertEquals(AgentStepKind.Other, activity.current!!.kind)

        tracker.toolCall("1", null, AcpToolKind.Execute, AcpToolCallStatus.InProgress)

        assertEquals(AgentStepKind.Run, activity.current!!.kind)
        assertEquals("ls", activity.current!!.title)
    }

    @Test
    fun stepsRunningTogether_showTheLatest_andTheEarlierOneWhenItIsLeft() {
        tracker.beginTurn()
        now = 2_000
        tracker.toolCall("a", "grep", AcpToolKind.Search, null)
        now = 3_000
        tracker.toolCall("b", "cat", AcpToolKind.Read, null)
        assertEquals("b", activity.current!!.id)
        assertEquals(2, activity.steps.size)

        tracker.toolCall("b", null, null, AcpToolCallStatus.Completed)

        assertEquals("a", activity.current!!.id)
        assertEquals(2_000, activity.since)
    }

    @Test
    fun aStepThatEndsUnannounced_onlyCountsAsHeard() {
        tracker.beginTurn()
        now = 5_000
        tracker.toolCall("x", null, null, AcpToolCallStatus.Completed)

        assertTrue(activity.steps.isEmpty())
        assertEquals(5_000, activity.lastHeard)
    }

    @Test
    fun reasoningAndProse_sayWhatItDoes_sinceItStarted() {
        tracker.beginTurn()
        now = 2_000
        tracker.thought()
        now = 4_000
        tracker.thought()
        assertEquals(AgentDoing.Thinking, activity.doing)
        assertEquals(2_000, activity.since)
        assertEquals(4_000, activity.lastHeard)

        now = 6_000
        tracker.wrote()

        assertEquals(AgentDoing.Writing, activity.doing)
        assertEquals(6_000, activity.since)
    }

    @Test
    fun stopping_isKeptUntilTheTurnEnds() {
        tracker.beginTurn()
        tracker.stopping()
        assertTrue(activity.stopping)

        tracker.endTurn()
        tracker.beginTurn()

        assertEquals(false, activity.stopping)
    }
}
