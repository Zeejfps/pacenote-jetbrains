package dev.diffdino.pairing.ui.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.pairing.AgentPhase

class AgentStatusTextTest : BasePlatformTestCase() {
    fun testAnAgentWithNoSessionReportsItsOwnPhase() {
        assertEquals(AgentStatus("Starting…", false), AgentStatusText.of(AgentPhase.Starting, null))
        assertEquals(AgentStatus("Ready", false), AgentStatusText.of(AgentPhase.Running(false), null))
        assertEquals(AgentStatus("Working…", false), AgentStatusText.of(AgentPhase.Running(true), null))
        assertEquals(AgentStatus("Agent went away", true), AgentStatusText.of(AgentPhase.Gone("exited"), null))
    }

    fun testASessionsPhaseTakesOver() {
        val running = AgentPhase.Running(false)
        assertEquals(AgentStatus("Your turn", false), AgentStatusText.of(running, PairingPhase.Running(waiting = true)))
        assertEquals(AgentStatus("Working…", false), AgentStatusText.of(running, PairingPhase.Running(waiting = false)))
        assertEquals(AgentStatus("Session failed", true), AgentStatusText.of(running, PairingPhase.Failed("boom")))
        assertEquals(AgentStatus("Agent went away", true), AgentStatusText.of(running, PairingPhase.Disconnected("gone")))
        assertEquals(AgentStatus("Session ended", false), AgentStatusText.of(running, PairingPhase.Ended(null)))
    }
}
