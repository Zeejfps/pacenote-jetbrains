package dev.pacenote.acp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The environment an adapter starts in: the variables it inherits, and the `PATH` its command is
 * looked up on. Wherever the variables came from (the IDE's process, a login shell, a test),
 * finding the executable works the same way.
 */
class AcpEnvironment(val variables: Map<String, String>, private val windows: Boolean = isWindows) {
    fun resolveCommand(command: String): String? {
        if (command.isBlank()) return null
        if (Path.of(command).isAbsolute) return command.takeIf { Files.isRegularFile(Path.of(it)) }

        val path = variables.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value
        if (path.isNullOrEmpty()) return null

        for (directory in path.split(File.pathSeparatorChar).filter { it.isNotEmpty() })
            for (candidate in candidates(directory, command))
                if (Files.isRegularFile(candidate)) return candidate.toString()
        return null
    }

    /** This environment with [overrides] set over it: a name already here is replaced whatever its
     * case on Windows, and a value's `$NAME`, `${NAME}` (and `%NAME%` on Windows) take the variable
     * from here, or nothing. */
    fun with(overrides: Map<String, String>): AcpEnvironment {
        if (overrides.isEmpty()) return this
        val merged = LinkedHashMap(variables)
        for ((name, value) in overrides) {
            val expanded = expand(value)
            if (windows) merged.keys.removeIf { it.equals(name, ignoreCase = true) }
            merged[name] = expanded
        }
        return AcpEnvironment(merged, windows)
    }

    private fun expand(value: String): String = reference.replace(value) { match ->
        val (braced, bare, percent) = match.destructured
        if (percent.isNotEmpty() && !windows) match.value
        else lookup(braced.ifEmpty { bare }.ifEmpty { percent }).orEmpty()
    }

    private fun lookup(name: String): String? =
        variables[name] ?: if (windows) variables.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else null

    // On Windows the configured name is normally bare and the executable is not: npx is npx.cmd.
    private fun candidates(directory: String, command: String): List<Path> = try {
        if (!windows) listOf(Path.of(directory, command))
        else listOf(".exe", ".cmd", ".bat", "").map { Path.of(directory, command + it) }
    } catch (_: java.nio.file.InvalidPathException) {
        emptyList()
    }

    companion object {
        private val reference = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)|%([A-Za-z_][A-Za-z0-9_()]*)%""")

        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
