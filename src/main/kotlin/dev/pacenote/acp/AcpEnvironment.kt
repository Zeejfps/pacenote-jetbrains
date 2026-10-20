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

    // On Windows the configured name is normally bare and the executable is not: npx is npx.cmd.
    private fun candidates(directory: String, command: String): List<Path> = try {
        if (!windows) listOf(Path.of(directory, command))
        else listOf(".exe", ".cmd", ".bat", "").map { Path.of(directory, command + it) }
    } catch (_: java.nio.file.InvalidPathException) {
        emptyList()
    }

    companion object {
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
