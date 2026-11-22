package app.granger.settings

import app.granger.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The knowledge languages and the Language screen's section, shared (Plan I7 Task 1). */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageSettingsModelTest {
    private class MemoryPrefs(override var languages: List<String>? = null) : KnowledgePrefs

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun theDefaultIsTheAppLanguageWhenTheCatalogHasIt() = runTest {
        val prefs = MemoryPrefs()
        val supported = MutableStateFlow(listOf("en", "es"))
        val knowledge = KnowledgeLanguagesModel(prefs, MutableStateFlow("es-MX"), supported)
        assertEquals(listOf("es"), knowledge.languages.first())

        knowledge.set(listOf("EN", "es", "en"))
        assertEquals(listOf("en", "es"), prefs.languages)
        assertEquals(listOf("en", "es"), knowledge.languages.first())
        knowledge.set(emptyList())
        assertNull(prefs.languages)
        assertEquals(listOf("es"), KnowledgeLanguagesModel(prefs, MutableStateFlow("es"), supported).languages.first())
    }

    @Test fun theSectionShowsOnlyWithMoreThanOneLanguage() {
        assertFalse(LanguageSettingsUi(listOf("en"), listOf("en")).showLibrary)
        val ui = LanguageSettingsUi(listOf("es", "en"), listOf("en", "es"))
        assertTrue(ui.showLibrary)
        val (en, es) = ui.rows
        assertEquals(LibraryLanguageRow("en", on = true, locked = false, note = null, canMakeMain = true), en)
        assertEquals(LibraryLanguageRow("es", on = true, locked = false, note = UiText.Res("settings_language_main"), canMakeMain = false), es)
        // The last one ticked stays ticked, and with one on there's no "Main language" line.
        assertEquals(LibraryLanguageRow("es", on = true, locked = true, note = null, canMakeMain = false), LanguageSettingsUi(listOf("es"), listOf("en", "es")).rows[1])
    }

    @Test fun theModelTogglesAndMakesMain() = runTest {
        val knowledge = KnowledgeLanguagesModel(MemoryPrefs(), MutableStateFlow("en"), MutableStateFlow(listOf("en", "es")))
        val model = LanguageSettingsModel(knowledge)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.ui.collect {} }
        assertEquals(listOf("en"), model.ui.value.chosen)

        model.toggle("en") // the last one stays
        assertEquals(listOf("en"), model.ui.value.chosen)
        model.toggle("es")
        assertEquals(listOf("en", "es"), model.ui.value.chosen)
        model.makeMain("es")
        assertEquals(listOf("es", "en"), model.ui.value.chosen)
        model.toggle("es")
        assertEquals(listOf("en"), model.ui.value.chosen)
        job.cancel()
    }
}
