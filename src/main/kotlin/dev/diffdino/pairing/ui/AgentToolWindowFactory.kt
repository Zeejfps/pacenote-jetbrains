package dev.diffdino.pairing.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.diffdino.pairing.pairing.PairingProjectService
import dev.diffdino.pairing.ui.support.Lifetime
import dev.diffdino.pairing.ui.support.ReactiveSlot
import java.awt.BorderLayout
import javax.swing.JPanel

/** The "DiffDino Agent" tool window: the project's conversation with its agent, or the way to start one. */
class AgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable: Disposable = Disposer.newDisposable("DiffDino agent tool window")
        Disposer.register(toolWindow.disposable, disposable)
        val content = ContentFactory.getInstance().createContent(AgentPanel(project, Lifetime(disposable)), null, false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "DiffDino Agent"
    }
}

/** The tool window's content: the conversation with the agent, or the empty state without one. */
class AgentPanel(project: Project, lifetime: Lifetime) : JPanel(BorderLayout()) {
    init {
        val sessions = PairingProjectService.getInstance(project).sessions
        add(
            ReactiveSlot(lifetime, sessions.conversation) { conversation, life ->
                if (conversation == null) EmptyAgentPanel(project) else ConversationPanel(conversation, project, life)
            },
            BorderLayout.CENTER,
        )
    }
}
