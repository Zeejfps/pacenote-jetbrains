package dev.pacenote.ui.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import dev.pacenote.settings.AgentSettings
import dev.pacenote.settings.ApprovalRules

/** The settings page over the real settings: it opens on what is saved, and applies the approval
 * rules as set on it. */
class AgentPresetsConfigurableTest : BasePlatformTestCase() {
    private val settings get() = AgentSettings.getInstance()
    private lateinit var saved: ApprovalRules

    override fun setUp() {
        super.setUp()
        saved = settings.approvals
        settings.updateApprovals(ApprovalRules.Default)
    }

    override fun tearDown() {
        try {
            settings.updateApprovals(saved)
        } finally {
            super.tearDown()
        }
    }

    fun testItOpensUnmodified_andAppliesTheApprovalRulesSetOnIt() {
        val configurable = AgentPresetsConfigurable()
        val panel = configurable.createComponent()
        configurable.reset()
        assertFalse(configurable.isModified)

        val askBefore = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.text.contains("git push") }
        assertEquals("git push\nnpm publish\nrm -rf", askBefore.text)
        askBefore.text = "git push\nterraform apply"
        UIUtil.findComponentsOfType(panel, JBRadioButton::class.java).single { it.text == "Ask for every command" }.doClick()
        assertTrue(configurable.isModified)

        configurable.apply()

        assertEquals(ApprovalRules(askEveryCommand = true, askBefore = listOf("git push", "terraform apply")), settings.approvals)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun testThePageFitsASettingsDialog_andItsFieldsTakeTheWidthThereIs() {
        val configurable = AgentPresetsConfigurable()
        val page = configurable.createComponent()
        configurable.reset()
        val asked = page.preferredSize.width
        val scroll = JBScrollPane(page)
        val name = UIUtil.findComponentsOfType(page, JBTextField::class.java).first()

        fun widthAt(dialog: Int): Int {
            scroll.setSize(dialog, 600)
            repeat(2) { UIUtil.layoutRecursively(scroll) }
            return name.width
        }

        assertTrue("The page asks for ${asked}px", asked <= 760)
        val fitting = widthAt(asked + 20)
        val wide = widthAt(asked + 320)
        assertTrue("$fitting then $wide", wide - fitting > 250)
        configurable.disposeUIResources()
    }

    fun testApplyingAPresetThatCantBeSaved_selectsIt_andSaysWhy() {
        val configurable = AgentPresetsConfigurable()
        val page = configurable.createComponent()
        configurable.reset()
        val list = UIUtil.findComponentsOfType(page, JBList::class.java).first { it.model.size == 3 }
        val name = UIUtil.findComponentsOfType(page, JBTextField::class.java).first()
        list.selectedIndex = 1
        name.text = "  "
        list.selectedIndex = 0

        val error = try {
            configurable.apply()
            null
        } catch (e: ConfigurationException) {
            e
        }

        assertEquals("Enter a preset name.", error?.messageHtml?.toString())
        assertEquals(1, list.selectedIndex)
        configurable.disposeUIResources()
    }
}
