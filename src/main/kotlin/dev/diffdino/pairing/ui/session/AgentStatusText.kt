package dev.diffdino.pairing.ui.session

import dev.diffdino.pairing.model.PairingPhase
import dev.diffdino.pairing.pairing.AgentPhase
import dev.diffdino.pairing.ui.support.DiffDinoBundle

/** What the header says about the agent, and whether it is bad news. */
data class AgentStatus(val text: String, val trouble: Boolean)

object AgentStatusText {
    /** The session's phase while one runs, else the agent's own. */
    fun of(agent: String, phase: AgentPhase, session: PairingPhase?): AgentStatus {
        if (session != null) return when (session) {
            PairingPhase.Starting -> AgentStatus(DiffDinoBundle.message("status.starting", agent), false)
            is PairingPhase.Running ->
                if (session.waiting) AgentStatus(DiffDinoBundle.message("status.your.turn"), false)
                else AgentStatus(DiffDinoBundle.message("status.working", agent), false)
            is PairingPhase.Ended -> AgentStatus(DiffDinoBundle.message("status.session.ended"), false)
            is PairingPhase.Failed -> AgentStatus(DiffDinoBundle.message("status.session.failed"), true)
            is PairingPhase.Disconnected -> AgentStatus(DiffDinoBundle.message("status.gone"), true)
        }

        return when (phase) {
            AgentPhase.Starting -> AgentStatus(DiffDinoBundle.message("status.starting", agent), false)
            is AgentPhase.Running ->
                if (phase.busy) AgentStatus(DiffDinoBundle.message("status.working", agent), false)
                else AgentStatus(DiffDinoBundle.message("status.ready"), false)
            is AgentPhase.Gone -> AgentStatus(DiffDinoBundle.message("status.gone"), true)
        }
    }
}
