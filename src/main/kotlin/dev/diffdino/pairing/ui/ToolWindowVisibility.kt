package dev.diffdino.pairing.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.pairing.PairingSessions
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
            project.messageBus.connect(service).subscribe(ToolWindowManagerListener.TOPIC, PanelFollowsWindow(service.sessions))
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

    private class PanelFollowsWindow(private val sessions: PairingSessions) : ToolWindowManagerListener {
        override fun stateChanged(
            toolWindowManager: ToolWindowManager,
            toolWindow: ToolWindow,
            changeType: ToolWindowManagerEventType,
        ) {
            if (toolWindow.id != AgentToolWindowFactory.ID) return
            when (changeType) {
                ToolWindowManagerEventType.HideToolWindow -> sessions.hidePanel()
                ToolWindowManagerEventType.ShowToolWindow, ToolWindowManagerEventType.ActivateToolWindow -> sessions.showPanel()
                else -> Unit
            }
        }
    }
}
