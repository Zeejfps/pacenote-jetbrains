package dev.diffdino.pairing.ui.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.pairing.AgentPhase

class AgentStatusTextTest : BasePlatformTestCase() {
    fun testAnAgentWithNoSessionReportsItsOwnPhase() {
        assertEquals(AgentStatus("Starting Codex…", false), AgentStatusText.of("Codex", AgentPhase.Starting, null))
        assertEquals(AgentStatus("Ready", false), AgentStatusText.of("Codex", AgentPhase.Running(false), null))
        assertEquals(AgentStatus("Codex is working…", false), AgentStatusText.of("Codex", AgentPhase.Running(true), null))
        assertEquals(AgentStatus("Agent went away", true), AgentStatusText.of("Codex", AgentPhase.Gone("exited"), null))
    }

    fun testASessionsPhaseTakesOver() {
        val running = AgentPhase.Running(false)
        assertEquals(AgentStatus("Your turn", false), AgentStatusText.of("Codex", running, PairingPhase.Running(waiting = true)))
        assertEquals(AgentStatus("Codex is working…", false), AgentStatusText.of("Codex", running, PairingPhase.Running(waiting = false)))
        assertEquals(AgentStatus("Session failed", true), AgentStatusText.of("Codex", running, PairingPhase.Failed("boom")))
        assertEquals(AgentStatus("Agent went away", true), AgentStatusText.of("Codex", running, PairingPhase.Disconnected("gone")))
        assertEquals(AgentStatus("Session ended", false), AgentStatusText.of("Codex", running, PairingPhase.Ended(null)))
    }
}
