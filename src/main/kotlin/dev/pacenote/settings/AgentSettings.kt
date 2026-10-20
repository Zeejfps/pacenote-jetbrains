package dev.pacenote.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** The agent preferences the pairing core reads: the presets to run agents from, and the one the
 * user picked last for the chat. */
interface AgentPreferences {
    val presets: List<AgentPreset>

    val chatAgent: AgentPresetId?

    fun rememberChatAgent(id: AgentPresetId)
}

/** The user's agent presets, chat pick and approval rules, kept across IDE restarts. Built-in
 * presets and approval defaults stand in until the user has saved their own. */
@State(name = "PacenoteAgents", storages = [Storage("pacenote-agents.xml")])
class AgentSettings : PersistentStateComponent<AgentSettings.Stored>, AgentPreferences, ApprovalPreferences {
    /** The persisted shape: plain beans the platform's XML serializer reads and writes. */
    class Stored {
        /** Null until the user saves presets of their own: the built-ins apply. */
        var presets: MutableList<StoredPreset>? = null
        var chatAgent: String? = null
        var askEveryCommand: Boolean = false

        /** Null until the user saves a list of their own: the defaults apply. */
        var askBefore: MutableList<String>? = null
        var allowedServers: MutableList<String> = mutableListOf()
    }

    class StoredPreset {
        var id: String = ""
        var name: String = ""
        var kind: String = ""
        var permission: String = ""
        var arguments: MutableList<String> = mutableListOf()
        var command: MutableList<String> = mutableListOf()

        /** NAME=value, one a variable. */
        var environment: MutableList<String> = mutableListOf()
    }

    @Volatile
    private var stored = Stored()

    override fun getState(): Stored = stored

    override fun loadState(state: Stored) {
        stored = state
    }

    override val presets: List<AgentPreset>
        get() = stored.presets?.mapNotNull(::parse) ?: AgentPreset.BuiltIn

    override val chatAgent: AgentPresetId?
        get() = stored.chatAgent?.takeIf { it.isNotBlank() }?.let(::AgentPresetId)

    override fun rememberChatAgent(id: AgentPresetId) {
        stored.chatAgent = id.value
    }

    // Read from the agent's thread as the guard decides, so each list is replaced whole, not changed.
    override val approvals: ApprovalRules
        get() {
            val current = stored
            return ApprovalRules(
                current.askEveryCommand,
                current.askBefore?.toList() ?: ApprovalRules.DefaultAskBefore,
                current.allowedServers.toList(),
            )
        }

    override fun alwaysAllowServer(server: String) {
        if (approvals.allowsServer(server)) return
        stored.allowedServers = (stored.allowedServers + server).toMutableList()
    }

    /** Replaces the approval rules the guard reads. */
    fun updateApprovals(rules: ApprovalRules) {
        stored.askEveryCommand = rules.askEveryCommand
        stored.askBefore = rules.askBefore.toMutableList()
        stored.allowedServers = rules.allowedServers.toMutableList()
    }

    /** Replaces the presets the user runs agents from. */
    fun updatePresets(presets: List<AgentPreset>) {
        stored.presets = presets.mapTo(mutableListOf()) { preset ->
            StoredPreset().apply {
                id = preset.id.value
                name = preset.name
                kind = preset.kind.name
                permission = preset.permission.name
                arguments = preset.arguments.toMutableList()
                command = preset.command.toMutableList()
                environment = preset.environment.entries.mapTo(mutableListOf()) { "${it.key}=${it.value}" }
            }
        }
    }

    companion object {
        fun getInstance(): AgentSettings = ApplicationManager.getApplication().getService(AgentSettings::class.java)

        /** A stored preset as the core uses it, or null when the file holds something this build
         * doesn't know. */
        internal fun parse(stored: StoredPreset): AgentPreset? {
            if (stored.id.isBlank()) return null
            val kind = AgentKind.entries.firstOrNull { it.name == stored.kind } ?: return null
            val permission = AgentPermission.entries.firstOrNull { it.name == stored.permission } ?: return null
            val environment = (AgentVariables.parse(stored.environment.joinToString("\n")) as? AgentVariablesParse.Ok)?.variables.orEmpty()
            return AgentPreset(
                AgentPresetId(stored.id),
                stored.name.ifBlank { kind.label },
                kind,
                permission,
                stored.arguments.toList(),
                stored.command.toList(),
                environment,
            )
        }
    }
}
