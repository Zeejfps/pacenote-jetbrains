package dev.pacenote.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the settings keep: presets whole, command and environment included, and the approval rules,
 * defaults until the user saves their own. */
class AgentSettingsTest {
    @Test
    fun aPreset_comesBackAsItWasSaved() {
        val settings = AgentSettings()
        val preset = AgentPreset.Gemini.copy(
            command = listOf("gemini", "--experimental-acp"),
            environment = mapOf("GEMINI_API_KEY" to "k=v", "PATH" to "/opt/bin:\$PATH"),
        )

        settings.updatePresets(listOf(preset))

        assertEquals(listOf(preset), settings.presets)
    }

    @Test
    fun approvals_areTheDefaults_untilSaved() {
        val settings = AgentSettings()
        assertEquals(ApprovalRules.Default, settings.approvals)

        val mine = ApprovalRules(askEveryCommand = true, askBefore = listOf("terraform apply"), allowedServers = listOf("linear"))
        settings.updateApprovals(mine)

        assertEquals(mine, settings.approvals)
    }

    @Test
    fun aServerAllowedFromACard_isKeptOnce() {
        val settings = AgentSettings()

        settings.alwaysAllowServer("claude_ai_Claude_Docs")
        settings.alwaysAllowServer("CLAUDE_AI_Claude_Docs")

        assertEquals(listOf("claude_ai_Claude_Docs"), settings.approvals.allowedServers)
        assertTrue(settings.approvals.allowsServer("claude_ai_claude_docs"))
    }
}
