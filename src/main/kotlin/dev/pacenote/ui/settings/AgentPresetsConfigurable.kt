package dev.pacenote.ui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.panel.ComponentPanelBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.pacenote.acp.AcpHarness
import dev.pacenote.acp.McpToolName
import dev.pacenote.settings.AgentArguments
import dev.pacenote.settings.AgentArgumentsProblem
import dev.pacenote.settings.AgentKind
import dev.pacenote.settings.AgentPermission
import dev.pacenote.settings.AgentPresetId
import dev.pacenote.settings.AgentSettings
import dev.pacenote.settings.ApprovalRules
import dev.pacenote.ui.support.ChatColors
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.ScrollableColumn
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Tools | Pacenote: the agent presets the chat, pairing and Send to agent run, the agent the chat
 * opens with, and the approval rules the write guard adds to its own.
 */
class AgentPresetsConfigurable : Configurable {
    private val settings = AgentSettings.getInstance()
    private var model = PresetsEditorModel(settings.presets, settings.chatAgent)
    private var panel: JComponent? = null

    private val listModel = CollectionListModel<PresetDraft>()
    private val list = JBList(listModel)
    private val name = JBTextField()
    private val kind = ComboBox(DefaultComboBoxModel(AgentKind.entries.toTypedArray()))
    private val permission = ComboBox(DefaultComboBoxModel(AgentPermission.entries.toTypedArray()))
    private val arguments = JBTextField()
    private val command = JBTextField()
    private val environment = JBTextArea(3, 0)
    private val problem = JBLabel().apply { foreground = ChatColors.dangerText }
    private val chatAgent = ComboBox<PresetDraft>()
    private var loading = false

    private val allowCommands = JBRadioButton(PacenoteBundle.message("settings.commands.allow"))
    private val askCommands = JBRadioButton(PacenoteBundle.message("settings.commands.ask"))
    private val askBefore = JBTextArea(4, 0)
    private val serversModel = CollectionListModel<String>()
    private val servers = JBList(serversModel)

    override fun getDisplayName(): String = PacenoteBundle.message("settings.title")

    override fun createComponent(): JComponent {
        model = PresetsEditorModel(settings.presets, settings.chatAgent)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SimpleListCellRenderer.create("") { it.name.ifBlank { PacenoteBundle.message("settings.unnamed") } }
        kind.renderer = SimpleListCellRenderer.create("") { it.label }
        permission.renderer = SimpleListCellRenderer.create("") { permissionLabel(it) }
        chatAgent.renderer = SimpleListCellRenderer.create("") { it.name }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        name.document.addDocumentListener(edited { commit() })
        arguments.document.addDocumentListener(edited { commit() })
        command.document.addDocumentListener(edited { commit() })
        environment.document.addDocumentListener(edited { commit() })
        kind.addActionListener { commit() }
        permission.addActionListener { commit() }
        chatAgent.addActionListener { if (!loading) model.chooseChatAgent(chatAgent.item?.id) }

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addPreset() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = Dimension(JBUI.scale(180), JBUI.scale(200))

        val reset = JButton(PacenoteBundle.message("settings.reset")).apply { addActionListener { resetToDefaults() } }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(PacenoteBundle.message("settings.name"), name)
            .addLabeledComponent(PacenoteBundle.message("settings.agent"), kind)
            .addLabeledComponent(PacenoteBundle.message("settings.permission"), permission)
            .addLabeledComponent(PacenoteBundle.message("settings.arguments"), arguments)
            .addComponentToRightColumn(hint("settings.arguments.hint"))
            .addLabeledComponent(PacenoteBundle.message("settings.command"), command)
            .addComponentToRightColumn(hint("settings.command.hint"))
            .addLabeledComponent(PacenoteBundle.message("settings.environment"), JBScrollPane(environment), true)
            .addComponentToRightColumn(hint("settings.environment.hint"))
            .addComponentToRightColumn(problem)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val editor = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(decorated, BorderLayout.WEST)
            add(form, BorderLayout.CENTER)
        }

        ButtonGroup().apply {
            add(allowCommands)
            add(askCommands)
        }
        allowCommands.addActionListener { askBefore.isEnabled = allowCommands.isSelected }
        askCommands.addActionListener { askBefore.isEnabled = allowCommands.isSelected }
        servers.cellRenderer = SimpleListCellRenderer.create("") { server ->
            val read = McpToolName.serverName(server)
            if (read == server) server else "$read ($server)"
        }
        val serverList = ToolbarDecorator.createDecorator(servers)
            .setAddAction { addServer() }
            .setRemoveAction { servers.selectedIndices.reversed().forEach(serversModel::remove) }
            .disableUpDownActions()
            .createPanel()
        serverList.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(100))

        val root = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(PacenoteBundle.message("settings.agents")))
            .addComponent(hint("settings.description"))
            .addComponent(editor)
            .addComponent(reset)
            .addLabeledComponent(PacenoteBundle.message("settings.chat.agent"), chatAgent)
            .addComponent(TitledSeparator(PacenoteBundle.message("settings.approvals")))
            .addComponent(hint("settings.approvals.hint"))
            .addComponent(allowCommands)
            .addComponent(JBScrollPane(askBefore))
            .addComponent(hint("settings.commands.hint"))
            .addComponent(askCommands)
            .addVerticalGap(JBUI.scale(8))
            .addComponent(JBLabel(PacenoteBundle.message("settings.servers")))
            .addComponent(serverList)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        // The dialog scrolls the page; as wide as the dialog, the fields grow and shrink with it
        // instead of the page keeping its preferred width behind a sideways scroll bar.
        val page = ScrollableColumn(0).apply { add(root) }
        panel = page
        reload(select = model.drafts.firstOrNull()?.id)
        showApprovals(settings.approvals)
        return page
    }

    override fun isModified(): Boolean = model.isModified(settings.presets, settings.chatAgent) || approvalsDraft() != settings.approvals

    override fun apply() {
        when (val checked = model.check()) {
            is PresetsCheck.Valid -> {
                settings.updatePresets(checked.presets)
                model.chatAgent?.let(settings::rememberChatAgent)
            }
            is PresetsCheck.Invalid -> {
                val first = checked.problems.entries.first()
                val shown = model.drafts.first { it.id == first.key }.name.ifBlank { PacenoteBundle.message("settings.unnamed") }
                throw ConfigurationException(describe(first.value), shown)
            }
        }
        settings.updateApprovals(approvalsDraft())
    }

    override fun reset() {
        model = PresetsEditorModel(settings.presets, settings.chatAgent)
        reload(select = model.drafts.firstOrNull()?.id)
        showApprovals(settings.approvals)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun showApprovals(rules: ApprovalRules) {
        allowCommands.isSelected = !rules.askEveryCommand
        askCommands.isSelected = rules.askEveryCommand
        askBefore.text = rules.askBefore.joinToString("\n")
        askBefore.isEnabled = !rules.askEveryCommand
        serversModel.replaceAll(rules.allowedServers)
    }

    private fun approvalsDraft() = ApprovalRules(
        askEveryCommand = askCommands.isSelected,
        askBefore = askBefore.text.lines().map(String::trim).filter(String::isNotEmpty),
        allowedServers = serversModel.items.toList(),
    )

    private fun addServer() {
        val typed = Messages.showInputDialog(
            servers,
            PacenoteBundle.message("settings.servers.add"),
            PacenoteBundle.message("settings.servers.add.title"),
            null,
        )?.trim()
        if (typed.isNullOrEmpty() || serversModel.items.any { it.equals(typed, ignoreCase = true) }) return
        serversModel.add(typed)
    }

    private fun resetToDefaults() {
        model.resetToDefaults()
        reload(select = model.drafts.firstOrNull()?.id)
    }

    private fun addPreset() {
        val created = model.add(PacenoteBundle.message("settings.new.name"))
        reload(select = created.id)
    }

    private fun removeSelected() {
        val selected = list.selectedValue ?: return
        val index = list.selectedIndex
        if (!model.remove(selected.id)) return
        reload(select = model.drafts[index.coerceAtMost(model.drafts.size - 1)].id)
    }

    private fun reload(select: AgentPresetId?) {
        loading = true
        try {
            listModel.replaceAll(model.drafts)
            model.drafts.indexOfFirst { it.id == select }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
            chatAgent.model = DefaultComboBoxModel(model.drafts.toTypedArray())
            model.drafts.firstOrNull { it.id == model.chatAgent }?.let { chatAgent.selectedItem = it }
        } finally {
            loading = false
        }
        showSelected()
    }

    private fun showSelected() {
        val draft = list.selectedValue
        loading = true
        try {
            for (field in listOf<JComponent>(name, kind, permission, arguments, command, environment)) field.isEnabled = draft != null
            if (draft != null) {
                name.text = draft.name
                kind.selectedItem = draft.kind
                permission.selectedItem = draft.permission
                arguments.text = draft.arguments
                command.text = draft.command
                environment.text = draft.environment
            }
            showBuiltInCommand()
        } finally {
            loading = false
        }
        showProblem()
    }

    // The empty command field shows the command it stands for.
    private fun showBuiltInCommand() {
        val agent = kind.item ?: return
        command.emptyText.text = AgentArguments.format(AcpHarness.builtInCommand(agent))
    }

    private fun commit() {
        if (loading) return
        val selected = list.selectedValue ?: return
        val edited = selected.copy(
            name = name.text,
            kind = kind.item,
            permission = permission.item,
            arguments = arguments.text,
            command = command.text,
            environment = environment.text,
        )
        showBuiltInCommand()
        if (edited == selected) return
        model.update(edited)
        val index = list.selectedIndex
        loading = true
        try {
            listModel.setElementAt(edited, index)
            val chosen = chatAgent.item?.id
            chatAgent.model = DefaultComboBoxModel(model.drafts.toTypedArray())
            model.drafts.firstOrNull { it.id == chosen }?.let { chatAgent.selectedItem = it }
        } finally {
            loading = false
        }
        showProblem()
    }

    private fun showProblem() {
        val selected = list.selectedValue
        val found = when (val checked = model.check()) {
            is PresetsCheck.Valid -> null
            is PresetsCheck.Invalid -> checked.problems[selected?.id]
        }
        problem.text = found?.let(::describe).orEmpty()
    }

    private fun describe(problem: PresetProblem): String = when (problem) {
        PresetProblem.NameRequired -> PacenoteBundle.message("settings.problem.name")
        is PresetProblem.Arguments -> when (val argument = problem.problem) {
            AgentArgumentsProblem.UnclosedQuote -> PacenoteBundle.message("settings.problem.quote")
            is AgentArgumentsProblem.NotAFlag -> PacenoteBundle.message("settings.problem.flag", argument.token)
        }
        PresetProblem.CommandQuote -> PacenoteBundle.message("settings.problem.command.quote")
        is PresetProblem.Environment -> PacenoteBundle.message("settings.problem.environment", problem.line)
    }

    private fun permissionLabel(permission: AgentPermission): String = when (permission) {
        AgentPermission.Ask -> PacenoteBundle.message("settings.permission.ask")
        AgentPermission.AcceptEdits -> PacenoteBundle.message("settings.permission.accept.edits")
        AgentPermission.Bypass -> PacenoteBundle.message("settings.permission.bypass")
    }

    // A comment as the platform's settings pages draw them: wrapped, so a long one doesn't set the
    // page's width and leave the fields to the dialog's scroll bar.
    private fun hint(key: String): JComponent = ComponentPanelBuilder.createCommentComponent(PacenoteBundle.message(key), true)

    private fun edited(changed: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = changed()
        override fun removeUpdate(e: DocumentEvent) = changed()
        override fun changedUpdate(e: DocumentEvent) = changed()
    }
}
