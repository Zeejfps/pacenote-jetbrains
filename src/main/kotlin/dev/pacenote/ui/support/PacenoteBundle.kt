package dev.pacenote.ui.support

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.PacenoteBundle"
private const val MNEMONIC_MARKER = '\u001B'

/** The plugin's user-facing text. An ampersand in it is an ampersand, not a mnemonic. */
object PacenoteBundle : DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params).replace(MNEMONIC_MARKER, '&')
}
