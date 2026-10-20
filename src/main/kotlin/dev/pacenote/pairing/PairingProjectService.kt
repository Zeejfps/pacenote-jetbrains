package dev.pacenote.pairing

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import dev.pacenote.acp.AcpEnvironment
import dev.pacenote.mcp.AgentToolServer
import dev.pacenote.mcp.McpEndpoint
import dev.pacenote.model.CodeSpotlights
import dev.pacenote.model.PairingPresentation
import dev.pacenote.settings.AgentSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/**
 * The project's agent conversation and the way into it, for the tool window to build on. State is
 * confined to the EDT: read and command [sessions] and [chat] there. The editor side is reached
 * through the project's [PairingPresentation] and [CodeSpotlights] services.
 */
class PairingProjectService(private val project: Project, scope: CoroutineScope) : Disposable {
    private val ui = Dispatchers.EDT
    private val uiScope = scope + ui
    private val root: Path = RepoRoot.of(Path.of(project.basePath ?: System.getProperty("user.home")))
    private var endpoint: McpEndpoint? = null

    val sessions: PairingSessions = PairingSessions(PairingSessions.factory(
        root = root,
        presentation = {
            project.getService(PairingPresentation::class.java)
                ?: throw IllegalStateException("No PairingPresentation service is registered for the project.")
        },
        workspace = GitPairingWorkspace(root, WorkingTreeSnapshots()),
        endpoints = ::ensureEndpoint,
        environment = { AcpEnvironment(EnvironmentUtil.getEnvironmentMap()) },
        scope = uiScope,
        ui = ui,
    ))

    val chat: AgentChat = AgentChat(sessions, AgentSettings.getInstance())

    private suspend fun ensureEndpoint(): AgentEndpoint = withContext(ui) {
        endpoint?.let { return@withContext AgentEndpoint.Listening(it.url) }
        try {
            val tools = PairingTools.createAll(sessions, ui) +
                EditorPresentationTools.createAll(root, { project.getService(CodeSpotlights::class.java) }, ui)
            val registered = AgentToolServer.getInstance().register(tools)
            endpoint = registered
            AgentEndpoint.Listening(registered.url)
        } catch (e: IOException) {
            AgentEndpoint.Unavailable(e.message ?: "The server could not listen.")
        }
    }

    override fun dispose() {
        sessions.dispose()
        endpoint?.close()
        endpoint = null
    }

    companion object {
        fun getInstance(project: Project): PairingProjectService = project.getService(PairingProjectService::class.java)
    }
}
