package dev.diffdino.pairing.ui.support

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapUtil

/** The keys bound to an action another part of the plugin registers, found by id. */
object ShortcutHint {
    /** The first keyboard shortcut of the action [actionId], or null when it isn't there or has none. */
    fun of(actionId: String): String? =
        ActionManager.getInstance().getAction(actionId)?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }?.takeIf { it.isNotEmpty() }

    /** [label] with the shortcut of [actionId] after it in brackets, if it has one. */
    fun label(label: String, actionId: String): String = of(actionId)?.let { "$label ($it)" } ?: label
}
