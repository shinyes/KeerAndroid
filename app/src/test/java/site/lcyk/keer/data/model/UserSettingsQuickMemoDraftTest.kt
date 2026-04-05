package site.lcyk.keer.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsQuickMemoDraftTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun serialization_keepsFullEditorDraftAndQuickDraftSeparate() {
        val settings = UserSettings(
            draft = "full editor draft",
            quickMemoDraft = QuickMemoDraftState(
                text = "quick memo draft",
                selectedTags = listOf("alpha"),
                selectedCollaborators = listOf("user_a"),
                resourceIdentifiers = listOf("resource-1"),
            ),
        )

        val restored = json.decodeFromString<UserSettings>(json.encodeToString(settings))

        assertEquals("full editor draft", restored.draft)
        assertEquals("quick memo draft", restored.quickMemoDraft.text)
        assertEquals(listOf("alpha"), restored.quickMemoDraft.selectedTags)
        assertEquals(listOf("user_a"), restored.quickMemoDraft.selectedCollaborators)
        assertEquals(listOf("resource-1"), restored.quickMemoDraft.resourceIdentifiers)
    }

    @Test
    fun deserialization_ofLegacySettingsFallsBackToEmptyQuickDraft() {
        val legacyJson = """
            {
              "draft": "legacy draft"
            }
        """.trimIndent()

        val restored = json.decodeFromString<UserSettings>(legacyJson)

        assertEquals("legacy draft", restored.draft)
        assertTrue(restored.quickMemoDraft.isEmpty())
    }
}
