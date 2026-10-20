package dev.pacenote.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import dev.pacenote.pairing.PairingProjectService
import dev.pacenote.pairing.PairingSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the tool window and the conversation's panel state in step: the window comes up when the
 * core shows the panel and goes away when it hides it, and showing or hiding the window by hand
 * does the same to the panel.
 */
class ToolWindowVisibility : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = PairingProjectService.getInstance(project)
        withContext(Dispatchers.EDT) {
            val manager = ToolWindowManager.getInstance(project)
            val follower = PanelFollowsWindow(service.sessions, manager.getToolWindow(AgentToolWindowFactory.ID)?.isVisible == true)
            project.messageBus.connect(service).subscribe(ToolWindowManagerListener.TOPIC, follower)
            var wasShown = false
            service.sessions.shown.collect { conversation ->
                val window = ToolWindowManager.getInstance(project).getToolWindow(AgentToolWindowFactory.ID) ?: return@collect
                if (conversation != null) {
                    wasShown = true
                    if (!window.isVisible) window.activate(null)
                } else if (wasShown) {
                    wasShown = false
                    window.hide(null)
                }
            }
        }
    }

    /**
     * The public [ToolWindowManagerListener.stateChanged] does not say which window changed (the
     * overload that does is internal API), so this follows our window's visibility across events.
     */
    private class PanelFollowsWindow(
        private val sessions: PairingSessions,
        private var wasVisible: Boolean,
    ) : ToolWindowManagerListener {

        override fun stateChanged(toolWindowManager: ToolWindowManager) {
            val visible = toolWindowManager.getToolWindow(AgentToolWindowFactory.ID)?.isVisible == true
            if (visible == wasVisible) return
            wasVisible = visible
            if (visible) sessions.showPanel() else sessions.hidePanel()
        }
    }
}
