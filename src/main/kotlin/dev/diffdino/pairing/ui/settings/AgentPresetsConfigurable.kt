package dev.diffdino.pairing.ui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.diffdino.pairing.settings.AgentArgumentsProblem
import dev.diffdino.pairing.settings.AgentKind
import dev.diffdino.pairing.settings.AgentPermission
import dev.diffdino.pairing.settings.AgentPresetId
import dev.diffdino.pairing.settings.AgentSettings
import dev.diffdino.pairing.ui.support.ChatColors
import dev.diffdino.pairing.ui.support.DiffDinoBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Tools | DiffDino: the agent presets the chat, pairing and Send to agent run, and the agent the chat opens with. */
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
    private val problem = JBLabel().apply { foreground = ChatColors.dangerText }
    private val chatAgent = ComboBox<PresetDraft>()
    private var loading = false

    override fun getDisplayName(): String = DiffDinoBundle.message("settings.title")

    override fun createComponent(): JComponent {
        model = PresetsEditorModel(settings.presets, settings.chatAgent)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SimpleListCellRenderer.create("") { it.name.ifBlank { DiffDinoBundle.message("settings.unnamed") } }
        kind.renderer = SimpleListCellRenderer.create("") { it.label }
        permission.renderer = SimpleListCellRenderer.create("") { permissionLabel(it) }
        chatAgent.renderer = SimpleListCellRenderer.create("") { it.name }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        name.document.addDocumentListener(edited { commit() })
        arguments.document.addDocumentListener(edited { commit() })
        kind.addActionListener { commit() }
        permission.addActionListener { commit() }
        chatAgent.addActionListener { if (!loading) model.chooseChatAgent(chatAgent.item?.id) }

        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addPreset() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = Dimension(JBUI.scale(180), JBUI.scale(200))

        val reset = JButton(DiffDinoBundle.message("settings.reset")).apply { addActionListener { resetToDefaults() } }
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(DiffDinoBundle.message("settings.name"), name)
            .addLabeledComponent(DiffDinoBundle.message("settings.agent"), kind)
            .addLabeledComponent(DiffDinoBundle.message("settings.permission"), permission)
            .addLabeledComponent(DiffDinoBundle.message("settings.arguments"), arguments)
            .addComponentToRightColumn(JBLabel(DiffDinoBundle.message("settings.arguments.hint")).apply { foreground = ChatColors.secondaryText })
            .addComponentToRightColumn(problem)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val editor = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(decorated, BorderLayout.WEST)
            add(form, BorderLayout.CENTER)
        }
        val root = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(DiffDinoBundle.message("settings.description")).apply { foreground = ChatColors.secondaryText })
            .addComponent(editor)
            .addComponent(reset)
            .addLabeledComponent(DiffDinoBundle.message("settings.chat.agent"), chatAgent)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = root
        reload(select = model.drafts.firstOrNull()?.id)
        return root
    }

    override fun isModified(): Boolean = model.isModified(settings.presets, settings.chatAgent)

    override fun apply() {
        when (val checked = model.check()) {
            is PresetsCheck.Valid -> {
                settings.updatePresets(checked.presets)
                model.chatAgent?.let(settings::rememberChatAgent)
            }
            is PresetsCheck.Invalid -> {
                val first = checked.problems.entries.first()
                val shown = model.drafts.first { it.id == first.key }.name.ifBlank { DiffDinoBundle.message("settings.unnamed") }
                throw ConfigurationException(describe(first.value), shown)
            }
        }
    }

    override fun reset() {
        model = PresetsEditorModel(settings.presets, settings.chatAgent)
        reload(select = model.drafts.firstOrNull()?.id)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun resetToDefaults() {
        model.resetToDefaults()
        reload(select = model.drafts.firstOrNull()?.id)
    }

    private fun addPreset() {
        val created = model.add(DiffDinoBundle.message("settings.new.name"))
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
            for (field in listOf<JComponent>(name, kind, permission, arguments)) field.isEnabled = draft != null
            if (draft != null) {
                name.text = draft.name
                kind.selectedItem = draft.kind
                permission.selectedItem = draft.permission
                arguments.text = draft.arguments
            }
        } finally {
            loading = false
        }
        showProblem()
    }

    private fun commit() {
        if (loading) return
        val selected = list.selectedValue ?: return
        val edited = selected.copy(
            name = name.text,
            kind = kind.item,
            permission = permission.item,
            arguments = arguments.text,
        )
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
        PresetProblem.NameRequired -> DiffDinoBundle.message("settings.problem.name")
        is PresetProblem.Arguments -> when (val argument = problem.problem) {
            AgentArgumentsProblem.UnclosedQuote -> DiffDinoBundle.message("settings.problem.quote")
            is AgentArgumentsProblem.NotAFlag -> DiffDinoBundle.message("settings.problem.flag", argument.token)
        }
    }

    private fun permissionLabel(permission: AgentPermission): String = when (permission) {
        AgentPermission.Ask -> DiffDinoBundle.message("settings.permission.ask")
        AgentPermission.AcceptEdits -> DiffDinoBundle.message("settings.permission.accept.edits")
        AgentPermission.Bypass -> DiffDinoBundle.message("settings.permission.bypass")
    }

    private fun edited(changed: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = changed()
        override fun removeUpdate(e: DocumentEvent) = changed()
        override fun changedUpdate(e: DocumentEvent) = changed()
    }
}
