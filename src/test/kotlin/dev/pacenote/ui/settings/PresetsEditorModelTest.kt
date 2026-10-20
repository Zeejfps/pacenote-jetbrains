package dev.pacenote.ui.settings

import dev.pacenote.settings.AgentArgumentsProblem
import dev.pacenote.settings.AgentKind
import dev.pacenote.settings.AgentPermission
import dev.pacenote.settings.AgentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsEditorModelTest {
    private fun model() = PresetsEditorModel(AgentPreset.BuiltIn, AgentPreset.Codex.id)

    @Test
    fun isUnmodifiedUntilEdited() {
        assertFalse(model().isModified(AgentPreset.BuiltIn, AgentPreset.Codex.id))
    }

    @Test
    fun addingAPresetModifiesAndSavesWithTheDefaultsOfANewPreset() {
        val model = model()
        val created = model.add("Mine")

        assertTrue(model.isModified(AgentPreset.BuiltIn, AgentPreset.Codex.id))
        val saved = (model.check() as PresetsCheck.Valid).presets
        val mine = saved.last()
        assertEquals(created.id, mine.id)
        assertEquals("Mine", mine.name)
        assertEquals(AgentKind.ClaudeCode, mine.kind)
        assertEquals(AgentPermission.Ask, mine.permission)
        assertEquals(emptyList<String>(), mine.arguments)
    }

    @Test
    fun theLastPresetStays() {
        val model = PresetsEditorModel(listOf(AgentPreset.ClaudeCode), null)

        assertFalse(model.remove(AgentPreset.ClaudeCode.id))
        assertEquals(1, model.drafts.size)
    }

    @Test
    fun removingTheChatAgentClearsThePick() {
        val model = model()

        assertTrue(model.remove(AgentPreset.Codex.id))
        assertNull(model.chatAgent)
    }

    @Test
    fun chatAgentMustBeOneOfTheDrafts() {
        val model = model()
        model.chooseChatAgent(dev.pacenote.settings.AgentPresetId("nope"))

        assertNull(model.chatAgent)
    }

    @Test
    fun argumentsAreParsedOnSave() {
        val model = model()
        model.update(model.drafts.first().copy(arguments = "--model opus --effort \"very high\""))

        val saved = (model.check() as PresetsCheck.Valid).presets
        assertEquals(listOf("--model", "opus", "--effort", "very high"), saved.first().arguments)
    }

    @Test
    fun reportsWhyADraftCantBeSaved() {
        val model = model()
        val claude = model.drafts[0]
        val codex = model.drafts[1]
        model.update(claude.copy(arguments = "model opus"))
        model.update(codex.copy(name = "  "))

        val problems = (model.check() as PresetsCheck.Invalid).problems
        assertEquals(PresetProblem.Arguments(AgentArgumentsProblem.NotAFlag("model")), problems[claude.id])
        assertEquals(PresetProblem.NameRequired, problems[codex.id])
        assertTrue(model.isModified(AgentPreset.BuiltIn, null))
    }

    @Test
    fun resetGoesBackToTheBuiltInPresets() {
        val model = model()
        model.add("Mine")
        model.resetToDefaults()

        assertEquals(AgentPreset.BuiltIn, (model.check() as PresetsCheck.Valid).presets)
        assertNull(model.chatAgent)
    }
}
