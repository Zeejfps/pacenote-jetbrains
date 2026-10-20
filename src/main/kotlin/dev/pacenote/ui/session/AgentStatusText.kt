package dev.pacenote.ui.session

import dev.pacenote.model.PairingPhase
import dev.pacenote.pairing.AgentPhase
import dev.pacenote.ui.support.PacenoteBundle

/** What the header says about the agent, and whether it is bad news. */
data class AgentStatus(val text: String, val trouble: Boolean)

object AgentStatusText {
    /** The session's phase while one runs, else the agent's own. */
    fun of(phase: AgentPhase, session: PairingPhase?): AgentStatus {
        if (session != null) return when (session) {
            PairingPhase.Starting -> AgentStatus(PacenoteBundle.message("status.starting"), false)
            is PairingPhase.Running ->
                if (session.waiting) AgentStatus(PacenoteBundle.message("status.your.turn"), false)
                else AgentStatus(PacenoteBundle.message("status.working"), false)
            is PairingPhase.Ended -> AgentStatus(PacenoteBundle.message("status.session.ended"), false)
            is PairingPhase.Failed -> AgentStatus(PacenoteBundle.message("status.session.failed"), true)
            is PairingPhase.Disconnected -> AgentStatus(PacenoteBundle.message("status.gone"), true)
        }

        return when (phase) {
            AgentPhase.Starting -> AgentStatus(PacenoteBundle.message("status.starting"), false)
            is AgentPhase.Running ->
                if (phase.busy) AgentStatus(PacenoteBundle.message("status.working"), false)
                else AgentStatus(PacenoteBundle.message("status.ready"), false)
            is AgentPhase.Gone -> AgentStatus(PacenoteBundle.message("status.gone"), true)
        }
    }
}
