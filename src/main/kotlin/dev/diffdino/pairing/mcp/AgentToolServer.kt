package dev.diffdino.pairing.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import dev.diffdino.pairing.pairing.PairingInstructions
import java.io.IOException

/** The plugin's one MCP server, started the first time a project hands an agent its tools and
 * stopped with the IDE. Each project serves its tools at an endpoint of its own. */
class AgentToolServer : Disposable {
    private var server: McpHttpServer? = null

    /** Serves [tools] at a fresh endpoint, starting the server if it isn't running. */
    @Synchronized
    @Throws(IOException::class)
    fun register(tools: List<AgentTool>): McpEndpoint {
        val running = server ?: McpHttpServer.start(McpServerIdentity("DiffDino", "1", PairingInstructions.server)).also { server = it }
        return running.register(tools)
    }

    @Synchronized
    override fun dispose() {
        server?.close()
        server = null
    }

    companion object {
        fun getInstance(): AgentToolServer = ApplicationManager.getApplication().getService(AgentToolServer::class.java)
    }
}
