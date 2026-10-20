package dev.pacenote.settings

/**
 * What the user set the write guard to on top of its fixed rules: whether every shell command is
 * asked about, else the commands that are ([askBefore], each the words a command line holds), and
 * the MCP servers whose every tool is let through, by the name the agent gives them.
 */
data class ApprovalRules(
    val askEveryCommand: Boolean = false,
    val askBefore: List<String> = DefaultAskBefore,
    val allowedServers: List<String> = emptyList(),
) {
    fun allowsServer(server: String): Boolean = allowedServers.any { it.equals(server, ignoreCase = true) }

    companion object {
        val DefaultAskBefore: List<String> = listOf("git push", "npm publish", "rm -rf")

        val Default = ApprovalRules()
    }
}

/** The approval rules the guard reads, and the one the user can add from an approval card. */
interface ApprovalPreferences {
    val approvals: ApprovalRules

    /** Lets every tool of [server] through from now on, across restarts. */
    fun alwaysAllowServer(server: String)
}
