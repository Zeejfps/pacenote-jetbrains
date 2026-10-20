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

/** The user's agent presets and chat pick, kept across IDE restarts. Built-in presets stand in
 * until the user has saved a list of their own. */
@State(name = "PacenoteAgents", storages = [Storage("pacenote-agents.xml")])
class AgentSettings : PersistentStateComponent<AgentSettings.Stored>, AgentPreferences {
    /** The persisted shape: plain beans the platform's XML serializer reads and writes. */
    class Stored {
        /** Null until the user saves presets of their own: the built-ins apply. */
        var presets: MutableList<StoredPreset>? = null
        var chatAgent: String? = null
    }

    class StoredPreset {
        var id: String = ""
        var name: String = ""
        var kind: String = ""
        var permission: String = ""
        var arguments: MutableList<String> = mutableListOf()
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

    /** Replaces the presets the user runs agents from. */
    fun updatePresets(presets: List<AgentPreset>) {
        stored.presets = presets.mapTo(mutableListOf()) { preset ->
            StoredPreset().apply {
                id = preset.id.value
                name = preset.name
                kind = preset.kind.name
                permission = preset.permission.name
                arguments = preset.arguments.toMutableList()
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
            return AgentPreset(
                AgentPresetId(stored.id),
                stored.name.ifBlank { kind.label },
                kind,
                permission,
                stored.arguments.toList(),
            )
        }
    }
}
