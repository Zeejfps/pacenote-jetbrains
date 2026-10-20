package dev.pacenote.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.selected
import com.intellij.util.ui.JBUI
import dev.pacenote.acp.AcpEnvironment
import dev.pacenote.acp.AcpHarness
import dev.pacenote.acp.McpToolName
import dev.pacenote.settings.AgentArguments
import dev.pacenote.settings.AgentArgumentsParse
import dev.pacenote.settings.AgentArgumentsProblem
import dev.pacenote.settings.AgentKind
import dev.pacenote.settings.AgentPermission
import dev.pacenote.settings.AgentPresetId
import dev.pacenote.settings.AgentSettings
import dev.pacenote.settings.AgentVariables
import dev.pacenote.settings.AgentVariablesParse
import dev.pacenote.settings.ApprovalRules
import dev.pacenote.ui.support.PacenoteBundle
import dev.pacenote.ui.support.ScrollableColumn
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Tools | Pacenote: the agent presets the chat, pairing and Send to agent run, the one the chat
 * opens with, and the approval rules the write guard adds to its own. The presets are a list and the
 * form of the one selected; edits stay in a [PresetsEditorModel] until applied.
 */
class AgentPresetsConfigurable : Configurable {
    private val settings = AgentSettings.getInstance()
    private var model = PresetsEditorModel(settings.presets, settings.chatAgent)
    private var validation: Disposable? = null
    private var loading = false

    private val chatAgent = ComboBox<PresetDraft>()
    private val listModel = CollectionListModel<PresetDraft>()
    private val list = JBList(listModel)
    private val name = JBTextField()
    private val kind = ComboBox(DefaultComboBoxModel(AgentKind.entries.toTypedArray()))
    private val permission = ComboBox(DefaultComboBoxModel(AgentPermission.entries.toTypedArray()))
    private val arguments = JBTextField()
    private val command = JBTextField()
    private val environment = JBTextArea(3, 0)
    private lateinit var argumentsCell: Cell<JBTextField>

    private lateinit var allowCommands: JBRadioButton
    private lateinit var askCommands: JBRadioButton
    private val askBefore = JBTextArea(4, 0)
    private val serversModel = CollectionListModel<String>()
    private val servers = JBList(serversModel)

    override fun getDisplayName(): String = PacenoteBundle.message("settings.title")

    override fun createComponent(): JComponent {
        model = PresetsEditorModel(settings.presets, settings.chatAgent)
        wireFields()

        // Each field with its label above it and its hint below, so the form needs no label
        // column to line up and its fields take the width there is.
        val details = panel {
            row { cell(name).label(PacenoteBundle.message("settings.name"), LabelPosition.TOP).align(AlignX.FILL) }
            row { cell(kind).label(PacenoteBundle.message("settings.agent"), LabelPosition.TOP) }
            row {
                cell(permission).label(PacenoteBundle.message("settings.permission"), LabelPosition.TOP)
                    .comment(PacenoteBundle.message("settings.permission.hint"))
            }
            row {
                argumentsCell = cell(arguments).label(PacenoteBundle.message("settings.arguments"), LabelPosition.TOP).align(AlignX.FILL)
                    .comment(argumentsHint(AgentKind.ClaudeCode))
                    .validationOnInput { field -> argumentsProblem(field.text)?.let { error(describe(PresetProblem.Arguments(it))) } }
            }
            row {
                cell(command).label(PacenoteBundle.message("settings.command"), LabelPosition.TOP).align(AlignX.FILL)
                    .comment(PacenoteBundle.message("settings.command.hint"))
                    .validationOnInput { field ->
                        if (AgentArguments.split(field.text) == null) error(describe(PresetProblem.CommandQuote)) else null
                    }
            }
            row {
                scrollCell(environment).label(PacenoteBundle.message("settings.environment"), LabelPosition.TOP).align(AlignX.FILL)
                    .comment(environmentHint())
                    .validationOnInput { field ->
                        (AgentVariables.parse(field.text) as? AgentVariablesParse.Invalid)?.let { error(describe(PresetProblem.Environment(it.line))) }
                    }
            }
        }
        val editor = JPanel(BorderLayout(JBUI.scale(EDITOR_GAP), 0)).apply {
            isOpaque = false
            add(presetList(), BorderLayout.WEST)
            add(details, BorderLayout.CENTER)
        }

        val form = panel {
            group(PacenoteBundle.message("settings.agents")) {
                row { comment(PacenoteBundle.message("settings.description")) }
                row { cell(editor).align(AlignX.FILL) }
                row {
                    cell(chatAgent).label(PacenoteBundle.message("settings.chat.agent"), LabelPosition.TOP)
                        .comment(PacenoteBundle.message("settings.chat.agent.hint"))
                }.topGap(TopGap.SMALL)
            }

            group(PacenoteBundle.message("settings.approvals")) {
                row { comment(PacenoteBundle.message("settings.approvals.hint")) }
                buttonsGroup {
                    row { allowCommands = radioButton(PacenoteBundle.message("settings.commands.allow")).component }
                    indent {
                        row {
                            scrollCell(askBefore).align(AlignX.FILL).comment(PacenoteBundle.message("settings.commands.hint"))
                        }.enabledIf(allowCommands.selected)
                    }
                    row { askCommands = radioButton(PacenoteBundle.message("settings.commands.ask")).component }
                }
                row {
                    cell(serverList()).align(AlignX.FILL)
                        .label(PacenoteBundle.message("settings.servers"), LabelPosition.TOP)
                        .comment(PacenoteBundle.message("settings.servers.hint"))
                }.topGap(TopGap.SMALL)
            }
        }
        val disposable = Disposer.newDisposable("Pacenote settings validation")
        details.registerValidators(disposable)
        form.registerValidators(disposable)
        validation = disposable

        // The dialog scrolls the page; as wide as the dialog, the fields grow and shrink with it
        // instead of the page keeping its preferred width behind a sideways scroll bar.
        val wrapped = ScrollableColumn(0).apply { add(form) }
        reload(select = model.drafts.firstOrNull()?.id)
        showApprovals(settings.approvals)
        return wrapped
    }

    private fun wireFields() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = PRESET_ROWS
        list.cellRenderer = textListCellRenderer { it.name.ifBlank { PacenoteBundle.message("settings.unnamed") } }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        kind.renderer = textListCellRenderer { it.label }
        permission.renderer = textListCellRenderer { permissionLabel(it) }
        chatAgent.renderer = textListCellRenderer { it?.name.orEmpty() }
        chatAgent.addActionListener { if (!loading) model.chooseChatAgent(chatAgent.item?.id) }
        for (field in listOf(name, arguments, command)) field.document.addDocumentListener(edited { commit() })
        environment.document.addDocumentListener(edited { commit() })
        kind.addActionListener { commit() }
        permission.addActionListener { commit() }

        servers.cellRenderer = textListCellRenderer { server ->
            val read = McpToolName.serverName(server)
            if (read == server) server else "$read ($server)"
        }
        servers.emptyText.text = PacenoteBundle.message("settings.servers.empty")
        servers.emptyText.appendSecondaryText(PacenoteBundle.message("settings.servers.empty.add"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            addServer()
        }
    }

    private fun presetList(): JComponent = ToolbarDecorator.createDecorator(list)
        .setAddAction { addPreset() }
        .setRemoveAction { removeSelected() }
        .setRemoveActionUpdater { model.drafts.size > 1 }
        .addExtraAction(object : DumbAwareAction(PacenoteBundle.message("settings.reset"), null, AllIcons.Actions.Rollback) {
            override fun actionPerformed(e: AnActionEvent) = restoreBuiltIns()

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = Dimension(JBUI.scale(PRESET_LIST_WIDTH), preferredSize.height) }

    private fun serverList(): JComponent = ToolbarDecorator.createDecorator(servers)
        .setAddAction { addServer() }
        .setRemoveAction { servers.selectedIndices.reversed().forEach(serversModel::remove) }
        .disableUpDownActions()
        .createPanel()
        .apply { preferredSize = Dimension(JBUI.scale(SERVER_LIST_WIDTH), JBUI.scale(SERVER_LIST_HEIGHT)) }

    override fun isModified(): Boolean = model.isModified(settings.presets, settings.chatAgent) || approvalsDraft() != settings.approvals

    override fun apply() {
        when (val checked = model.check()) {
            is PresetsCheck.Valid -> {
                settings.updatePresets(checked.presets)
                settings.updateChatAgent(model.chatAgent?.takeIf { pick -> checked.presets.any { it.id == pick } })
            }
            is PresetsCheck.Invalid -> {
                val (id, problem) = checked.problems.entries.first()
                showProblem(id, problem)
                val shown = model.drafts.first { it.id == id }.name.ifBlank { PacenoteBundle.message("settings.unnamed") }
                throw ConfigurationException(describe(problem), shown)
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
        validation?.let(Disposer::dispose)
        validation = null
    }

    // Takes the user to what stops the presets from being saved: the preset, and the field.
    private fun showProblem(id: AgentPresetId, problem: PresetProblem) {
        model.drafts.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
        val field: JComponent = when (problem) {
            PresetProblem.NameRequired -> name
            is PresetProblem.Arguments -> arguments
            PresetProblem.CommandQuote -> command
            is PresetProblem.Environment -> environment
        }
        IdeFocusManager.getGlobalInstance().requestFocus(field, true)
    }

    private fun showApprovals(rules: ApprovalRules) {
        allowCommands.isSelected = !rules.askEveryCommand
        askCommands.isSelected = rules.askEveryCommand
        askBefore.text = rules.askBefore.joinToString("\n")
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

    // Replaces every preset, so it asks first when that would drop any the user made or changed.
    private fun restoreBuiltIns() {
        if (!model.isBuiltIn) {
            val answer = Messages.showOkCancelDialog(
                list,
                PacenoteBundle.message("settings.reset.confirm"),
                PacenoteBundle.message("settings.reset.confirm.title"),
                PacenoteBundle.message("settings.reset.confirm.ok"),
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.OK) return
        }
        model.resetToDefaults()
        reload(select = model.drafts.firstOrNull()?.id)
    }

    private fun addPreset() {
        val created = model.add(PacenoteBundle.message("settings.new.name"))
        reload(select = created.id)
        name.selectAll()
        IdeFocusManager.getGlobalInstance().requestFocus(name, true)
    }

    private fun removeSelected() {
        val selected = list.selectedValue ?: return
        val index = list.selectedIndex
        if (!model.remove(selected.id)) return
        reload(select = model.drafts[index.coerceAtMost(model.drafts.size - 1)].id)
    }

    private fun reload(select: AgentPresetId?) {
        quietly {
            listModel.replaceAll(model.drafts)
            model.drafts.indexOfFirst { it.id == select }.takeIf { it >= 0 }?.let { list.selectedIndex = it }
            showChatAgent()
        }
        showSelected()
    }

    // With no pick of its own the chat opens with the first preset, so that is the one shown.
    private fun showChatAgent() {
        chatAgent.model = DefaultComboBoxModel(model.drafts.toTypedArray())
        chatAgent.selectedItem = model.drafts.firstOrNull { it.id == model.chatAgent } ?: model.drafts.firstOrNull()
    }

    private fun showSelected() {
        val draft = list.selectedValue
        quietly {
            for (field in listOf<JComponent>(name, kind, permission, arguments, command, environment)) field.isEnabled = draft != null
            if (draft != null) {
                name.text = draft.name
                kind.selectedItem = draft.kind
                permission.selectedItem = draft.permission
                arguments.text = draft.arguments
                command.text = draft.command
                environment.text = draft.environment
            }
            showKind()
        }
    }

    // What the fields say depends on the agent: its built-in command, and how it takes arguments.
    private fun showKind() {
        val agent = kind.item ?: return
        command.emptyText.text = AgentArguments.format(AcpHarness.builtInCommand(agent))
        argumentsCell.comment?.text = argumentsHint(agent)
    }

    private fun commit() {
        if (loading) return
        val selected = list.selectedValue ?: return
        showKind()
        val edited = selected.copy(
            name = name.text,
            kind = kind.item,
            permission = permission.item,
            arguments = arguments.text,
            command = command.text,
            environment = environment.text,
        )
        if (edited == selected) return
        model.update(edited)
        val index = list.selectedIndex
        quietly {
            listModel.setElementAt(edited, index)
            showChatAgent()
        }
    }

    private fun argumentsProblem(text: String): AgentArgumentsProblem? =
        (AgentArguments.parse(text, kind.item ?: AgentKind.ClaudeCode) as? AgentArgumentsParse.Invalid)?.problem

    private fun argumentsHint(agent: AgentKind): String = when (agent) {
        AgentKind.ClaudeCode -> PacenoteBundle.message("settings.arguments.hint")
        AgentKind.Codex, AgentKind.Gemini -> PacenoteBundle.message("settings.arguments.hint.other")
    }

    // Written the way the IDE's own system names variables.
    private fun environmentHint(): String =
        if (AcpEnvironment.isWindows) PacenoteBundle.message("settings.environment.hint", "%NAME%", "PATH=C:\\tools\\node;%PATH%")
        else PacenoteBundle.message("settings.environment.hint", "\$NAME", "PATH=/opt/node/bin:\$PATH")

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

    // Fills fields without their listeners taking it as the user's edit. Nests: showing the selected
    // preset happens inside a reload, through the list's own listener.
    private fun quietly(fill: () -> Unit) {
        val was = loading
        loading = true
        try {
            fill()
        } finally {
            loading = was
        }
    }

    private fun edited(changed: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = changed()
        override fun removeUpdate(e: DocumentEvent) = changed()
        override fun changedUpdate(e: DocumentEvent) = changed()
    }

    private companion object {
        const val EDITOR_GAP = 16
        const val PRESET_ROWS = 6
        const val PRESET_LIST_WIDTH = 180
        const val SERVER_LIST_WIDTH = 240
        const val SERVER_LIST_HEIGHT = 96
    }
}
