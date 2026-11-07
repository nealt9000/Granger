package app.granger.home

import app.granger.FakeZimArchive
import app.granger.core.library.SearchHit
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WanderModelTest {
    private fun page(title: String, vararg links: String) =
        "<html><head><title>$title - Wikipedia</title></head><body><p>$title is a subject long enough to be a lead paragraph, linking " +
            links.joinToString(", ") { "<a href=\"./$it\">${it.replace('_', ' ')}</a>" } + ". It has a second sentence.</p><h2>More</h2></body></html>"

    private val top = FakeZimArchive(
        uuid = "top",
        contents = mapOf(
            "Hagia_Sophia" to page("Hagia Sophia", "Istanbul", "Justinian_I", "Byzantine_Empire", "Nowhere_At_All"),
            "Istanbul" to page("Istanbul", "Hagia_Sophia", "Bosphorus", "Turkey"),
            "Justinian_I" to page("Justinian I", "Byzantine_Empire", "Hagia_Sophia", "Code_of_Justinian"),
            "Bosphorus" to page("Bosphorus", "Istanbul", "Black_Sea", "Turkey"),
            "Turkey" to page("Turkey", "Istanbul", "Ankara", "Bosphorus", "Hagia_Sophia"),
            "Lonely" to page("Lonely", "Nowhere_1", "Nowhere_2"),
        ),
    )
    private val history = FakeZimArchive(
        uuid = "history",
        contents = mapOf("Byzantine_Empire" to page("Byzantine Empire", "Justinian_I", "Constantinople", "Roman_Empire")),
    )
    private val packs = listOf(pack("top", "Wikipedia"), pack("history", "Wikipedia History"))

    private fun TestScope.model(searchHits: List<SearchHit> = emptyList(), packList: List<app.granger.core.library.Pack> = packs): WanderModel {
        val io = StandardTestDispatcher(testScheduler)
        return WanderModel(
            scope = this,    // not backgroundScope: advanceUntilIdle doesn't run background work
            encyclopedias = { packList },
            archiveOf = { mapOf("top" to top, "history" to history)[it.id] },
            thumbnail = { _, path, _ -> if (path == "Istanbul") "/thumbs/istanbul.jpg" else null },
            titles = { searchHits },
            io = io,
            random = Random(1),
            debounceMillis = 10,
        )
    }

    @Test fun aStartShowsTheLeadsLinksThatAreOnThePhoneAcrossPacks() = runTest {
        val m = model()
        m.startFrom("top", "Hagia_Sophia"); advanceUntilIdle()
        val ui = m.ui.value
        assertThat(ui.centre?.title).isEqualTo("Hagia Sophia")
        assertThat(ui.summary).startsWith("Hagia Sophia is a subject")
        // Byzantine Empire is only in History; the missing link is dropped because three resolve.
        assertThat(ui.orbit.map { it.packId to it.path }).containsExactly("top" to "Istanbul", "top" to "Justinian_I", "history" to "Byzantine_Empire").inOrder()
        assertThat(ui.orbit.all { it.onPhone }).isTrue()
        // Titles and pictures fill in from each article.
        assertThat(ui.orbit.first().title).isEqualTo("Istanbul")
        assertThat(ui.orbit.first().imagePath).isEqualTo("/thumbs/istanbul.jpg")
        assertThat(ui.orbit[2].title).isEqualTo("Byzantine Empire")
    }

    @Test fun aPictureComesFromAnotherPackWhenTheArticlesOwnHasNone() = runTest {
        val bare = FakeZimArchive(uuid = "med", contents = mapOf("Psychosis" to page("Psychosis", "Delusion", "Paranoia", "Hallucination")))
        val pictured = FakeZimArchive(uuid = "top", contents = mapOf("Psychosis" to page("Psychosis")))
        val m = WanderModel(
            scope = this, encyclopedias = { listOf(pack("med"), pack("top")) },
            archiveOf = { mapOf("med" to bare, "top" to pictured)[it.id] },
            thumbnail = { a, path, _ -> if (a.uuid == "top" && path == "Psychosis") "/thumbs/psychosis.jpg" else null },
            titles = { emptyList() }, io = StandardTestDispatcher(testScheduler),
        )
        m.startFrom("med", "Psychosis"); advanceUntilIdle()
        assertThat(m.ui.value.centre?.packId).isEqualTo("med")
        assertThat(m.ui.value.centre?.imagePath).isEqualTo("/thumbs/psychosis.jpg")
    }

    @Test fun goingPushesTheTrailAndBackTrimsIt() = runTest {
        val m = model()
        m.startFrom("top", "Hagia_Sophia"); advanceUntilIdle()
        m.go(m.ui.value.orbit.first { it.path == "Istanbul" }); advanceUntilIdle()
        m.go(m.ui.value.orbit.first { it.path == "Bosphorus" }); advanceUntilIdle()
        assertThat(m.ui.value.centre?.path).isEqualTo("Bosphorus")
        assertThat(m.ui.value.trail.map { it.path }).containsExactly("Hagia_Sophia", "Istanbul").inOrder()
        m.back(0); advanceUntilIdle()
        assertThat(m.ui.value.centre?.path).isEqualTo("Hagia_Sophia")
        assertThat(m.ui.value.trail).isEmpty()
    }

    @Test fun aFreshStartClearsTheTrail() = runTest {
        val m = model()
        m.startFrom("top", "Hagia_Sophia"); advanceUntilIdle()
        m.go(m.ui.value.orbit.first()); advanceUntilIdle()
        assertThat(m.ui.value.trail).hasSize(1)
        m.startFrom("top", "Turkey"); advanceUntilIdle()
        assertThat(m.ui.value.trail).isEmpty()
    }

    @Test fun withFewerThanThreeOnThePhoneTheMissingShowDashedAndDoNothing() = runTest {
        val m = model()
        m.startFrom("top", "Lonely"); advanceUntilIdle()
        val orbit = m.ui.value.orbit
        assertThat(orbit.map { it.title }).containsExactly("Nowhere 1", "Nowhere 2").inOrder()
        assertThat(orbit.none { it.onPhone }).isTrue()
        m.go(orbit.first()); advanceUntilIdle()
        assertThat(m.ui.value.centre?.path).isEqualTo("Lonely")
    }

    @Test fun theTrailKeepsTheLastTwelve() = runTest {
        val m = model()
        m.startFrom("top", "Istanbul"); advanceUntilIdle()
        repeat(15) { i ->
            val next = m.ui.value.orbit.first { it.onPhone && it.path != m.ui.value.centre?.path }
            m.go(next); advanceUntilIdle()
        }
        assertThat(m.ui.value.trail).hasSize(WanderModel.TRAIL)
    }

    @Test fun searchKeepsArticleHitsInTheEncyclopediasOnly() = runTest {
        val hits = listOf(
            SearchHit("top", "Wikipedia", "Hagia_Sophia", "Hagia Sophia"),
            SearchHit("book-1", "Classics", "", "Hagia Sophia, a poem"),
            SearchHit("so", "Stack Overflow", "q/1", "Hagia question"),
            SearchHit("history", "Wikipedia History", "Hagia_Sophia", "Hagia Sophia"),
        )
        val m = model(hits)
        m.search("hagia"); advanceUntilIdle()
        assertThat(m.ui.value.hits.map { it.packId }).containsExactly("top")
        m.pick(m.ui.value.hits.first()); advanceUntilIdle()
        assertThat(m.ui.value.centre?.title).isEqualTo("Hagia Sophia")
        assertThat(m.ui.value.query).isEmpty()
        assertThat(m.ui.value.hits).isEmpty()
    }

    @Test fun aRandomStartPrefersAWalkableArticle() = runTest {
        top.random = "Lonely"
        val m = model(packList = listOf(pack("top", "Wikipedia")))
        m.start(); advanceUntilIdle()
        // Every try lands on Lonely, which has no links on the phone: the best found is shown anyway.
        assertThat(m.ui.value.centre?.path).isEqualTo("Lonely")
        top.random = "Turkey"
        m.start(); advanceUntilIdle()
        assertThat(m.ui.value.centre?.path).isEqualTo("Turkey")
        assertThat(m.ui.value.orbit.count { it.onPhone }).isAtLeast(WanderModel.MIN_REACHABLE)
    }

    @Test fun aRandomStartComesFromAWholeEncyclopediaNotASubject() = runTest {
        top.random = "Turkey"
        history.random = "Byzantine_Empire"
        val m = model(packList = listOf(pack("history").copy(name = "wikipedia_en_history"), pack("top").copy(name = "wikipedia_en_top")))
        repeat(3) { m.start(); advanceUntilIdle(); assertThat(m.ui.value.centre?.packId).isEqualTo("top") }
    }

    @Test fun aRandomStartIsInTheLibrarysMainLanguage() = runTest {
        top.random = "Turkey"
        val es = FakeZimArchive(uuid = "es", random = "Baldosa", contents = mapOf("Baldosa" to page("Baldosa", "Piedra", "Vidrio", "Metal")))
        val m = WanderModel(
            scope = this,
            encyclopedias = { listOf(pack("top").copy(name = "wikipedia_en_top", language = "eng"), pack("es").copy(name = "wikipedia_es_top", language = "spa")) },
            archiveOf = { mapOf("top" to top, "es" to es)[it.id] }, thumbnail = { _, _, _ -> null }, titles = { emptyList() },
            io = StandardTestDispatcher(testScheduler), random = Random(3),
        )
        repeat(3) { m.start(); advanceUntilIdle(); assertThat(m.ui.value.centre?.packId).isEqualTo("top") }
    }

    @Test fun noEncyclopediaMeansWanderIsUnavailable() = runTest {
        val m = model(packList = emptyList())
        m.ensureStarted(); advanceUntilIdle()
        assertThat(m.ui.value.available).isFalse()
        assertThat(m.ui.value.loading).isFalse()
    }
}

class WanderEncyclopediasTest {
    private fun wiki(id: String, name: String, lang: String) = pack(id).copy(name = name, language = lang)

    @Test fun okWikipediasInTheLibrarysLanguagesWholeOnesFirst() {
        val packs = listOf(
            wiki("hist", "wikipedia_en_history", "eng"),
            wiki("es", "wikipedia_es_top", "spa"),
            wiki("top", "wikipedia_en_top", "eng"),
            wiki("so", "stackoverflow.com_en_all", "eng"),
            wiki("broken", "wikipedia_en_all", "eng").copy(status = app.granger.core.library.PackStatus.MISSING),
        )
        assertThat(WanderModel.encyclopediasOf(packs, listOf("es", "en")).map { it.id }).containsExactly("es", "top", "hist").inOrder()
        assertThat(WanderModel.encyclopediasOf(packs, listOf("en")).map { it.id }).containsExactly("top", "hist", "es").inOrder()
    }
}
