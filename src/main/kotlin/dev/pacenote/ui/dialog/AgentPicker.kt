package dev.pacenote.ui.dialog

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import dev.pacenote.pairing.AgentChat
import dev.pacenote.settings.AgentPreset
import javax.swing.DefaultComboBoxModel

/** The agent presets to choose from, opening on the one the chat last used. */
class AgentPicker(private val chat: AgentChat) : ComboBox<AgentPreset>() {
    init {
        renderer = SimpleListCellRenderer.create("") { it.name }
        reload()
    }

    /** The preset picked, which is always one of [AgentChat.presets]. */
    val picked: AgentPreset get() = item ?: chat.default

    /** Lists the presets as they are now, keeping the pick where it is still there. */
    fun reload() {
        val keep = item?.id ?: chat.default.id
        val presets = chat.presets
        model = DefaultComboBoxModel(presets.toTypedArray())
        presets.firstOrNull { it.id == keep }?.let { selectedItem = it }
    }

    override fun addNotify() {
        super.addNotify()
        reload()
    }
}
