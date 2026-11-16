package app.granger.topic

import android.database.sqlite.SQLiteDatabase
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import app.granger.core.library.Related
import app.granger.core.library.RelatedHit
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexes
import app.granger.core.research.SourceKind
import app.granger.ebook.ArticleFooter
import app.granger.ebook.ArticleLead
import app.granger.ebook.LibraryBook
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopicSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var made = 0

    private fun gqid(zim: String, vararg articles: Pair<String, Long>): File {
        val f = File(tmp.root, "q${made++}.gqid")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('kind', 'gqid'), ('schemaVersion', '1'), ('sourceSha256', 'x'), ('sourceZim', ?)", arrayOf(zim))
            db.execSQL("CREATE TABLE articles(path TEXT PRIMARY KEY, qid INTEGER NOT NULL, rank INTEGER NOT NULL, other TEXT)")
            db.execSQL("CREATE TABLE links(qid INTEGER, prop TEXT, value TEXT)")
            articles.forEach { (path, qid) -> db.execSQL("INSERT INTO articles VALUES (?, ?, 10, NULL)", arrayOf<Any>(path, qid)) }
        }
        return f
    }

    private val laneIds = "philosophy_religion,civilisations,languages,film_music,art_literature,science,wars,events_politics,people"

    /** (qid, path, kind, y0, y1, lanes bits, rank). */
    private fun gtime(rows: List<List<Any?>>): File {
        val f = File(tmp.root, "t${made++}.gtime")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE marks(qid INTEGER NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, kind INTEGER NOT NULL, y0 INTEGER NOT NULL, " +
                    "md0 INTEGER NOT NULL, y1 INTEGER, md1 INTEGER, prec INTEGER NOT NULL, lanes INTEGER NOT NULL, rank INTEGER NOT NULL, lat REAL, lon REAL)",
            )
            db.execSQL("INSERT INTO meta VALUES ('format', '1'), ('lanes', '$laneIds')")
            for (r in rows) {
                db.execSQL(
                    "INSERT INTO marks VALUES (?, ?, ?, ?, ?, 0, ?, NULL, 9, ?, ?, NULL, NULL)",
                    arrayOf(r[0], r[1], (r[1] as String).replace('_', ' '), r[2], r[3], r[4], r[5], r[6]),
                )
            }
        }
        return f
    }

    private val people = 1L shl 8

    private val languages = mapOf("en-top" to "en", "en-history" to "en", "es-top" to "es")
    private val names = mapOf("wikipedia_en_top_maxi" to "en-top", "wikipedia_es_top_maxi" to "es-top")

    private fun source(
        qids: QidIndexes = QidIndexes(emptyList()),
        times: TimeIndexes = TimeIndexes(emptyList()),
        related: (suspend (String, String, (String, String) -> Boolean) -> Related)? = null,
        headers: MutableList<String> = mutableListOf(),
        footer: ArticleFooter = ArticleFooter.NONE,
    ) = TopicSource(
        header = { _, path -> headers += path; TopicHeader(path.replace('_', ' '), "Lead of $path.", "/pic/$path.jpg") },
        lead = { _, path -> ArticleLead(path.replace('_', ' '), "About $path.", null) },
        times = { times },
        qids = { qids },
        languageOf = { languages[it] },
        packIds = { names },
        footer = { _, _ -> footer },
        related = related,
        bookOf = { id -> LibraryBook(id, "Title of $id", null) },
        now = { 2026 },
    )

    @Test fun theHeaderArrivesFirstAndTheTopicFinishesWithEveryPart() = runBlocking<Unit> {
        val all = source().topic("en-top", "Napoleon").toList()
        assertThat(all.first { it.header != null }.header).isEqualTo(TopicHeader("Napoleon", "Lead of Napoleon.", "/pic/Napoleon.jpg"))
        val last = all.last()
        assertThat(last.done).isTrue()
        // No time index, no QID index, no vectors: those sections are empty, not waiting.
        assertThat(last.time).isNull()
        assertThat(last.other).isNull()
        assertThat(last.books).isEqualTo(ArticleFooter.NONE)
        assertThat(last.related).isEmpty()
        assertThat(last.relatedBooks).isEmpty()
        assertThat(all.dropLast(1).none { it.done }).isTrue()
    }

    @Test fun aFinishedTopicIsCachedForBack() = runBlocking<Unit> {
        val headers = mutableListOf<String>()
        val s = source(headers = headers)
        s.topic("en-top", "Napoleon").toList()
        val again = s.topic("en-top", "Napoleon").toList()
        assertThat(again).hasSize(1)
        assertThat(again.single().done).isTrue()
        assertThat(headers).containsExactly("Napoleon")
    }

    @Test fun theOtherLanguageShowsOnlyWhenAPackInThatLanguageHasIt() = runBlocking<Unit> {
        val en = QidIndex.open(gqid("wikipedia_en_top_maxi_2026-09.zim", "Napoleon" to 517L), "en-top")!!
        val history = QidIndex.open(gqid("wikipedia_en_history_maxi_2026-10.zim", "Napoleon" to 517L), "en-history")!!
        val es = QidIndex.open(gqid("wikipedia_es_top_maxi_2026-10.zim", "Napoleón_Bonaparte" to 517L), "es-top")!!
        val with = source(qids = QidIndexes(listOf(en, history, es))).topic("en-top", "Napoleon").toList().last()
        assertThat(with.other).isEqualTo(TopicOther("es", "es-top", "Napoleón_Bonaparte", "Napoleón Bonaparte"))
        // Another English pack with the article is not "the other language".
        val without = source(qids = QidIndexes(listOf(en, history))).topic("en-top", "Napoleon").toList().last()
        assertThat(without.other).isNull()
    }

    @Test fun timeComesFromTheIndexWithContemporariesAndEvents() = runBlocking<Unit> {
        val file = gtime(
            listOf(
                listOf(517L, "Napoleon", 2, 1769, 1821, people, 288),
                listOf(5879L, "Goethe", 2, 1749, 1832, people, 280),
                listOf(9L, "Abraham_Lincoln", 2, 1809, 1865, people, 400),
                listOf(6534L, "French_Revolution", 1, 1789, 1799, 1L shl 7, 198),
            ),
        )
        val times = TimeIndexes(listOf(TimeIndex.open(file, "en-top")!!))
        val t = source(times = times).topic("en-top", "Napoleon").toList().last().time!!
        assertThat(t.mark.y0).isEqualTo(1769)
        assertThat(t.people.map { it.path }).containsExactly("Goethe")
        assertThat(t.events.map { it.path }).containsExactly("French_Revolution")
        assertThat(source(times = times).topic("en-top", "Photosynthesis").toList().last().time).isNull()
    }

    @Test fun relatedNamesItsPacksDropsTheSameSubjectAndOpensBooks() = runBlocking<Unit> {
        val en = QidIndex.open(gqid("wikipedia_en_top_maxi_2026-09.zim", "Napoleon" to 517L, "Napoleon_Bonaparte" to 517L, "Napoleonic_Wars" to 78994L), "en-top")!!
        var asked: Pair<String, String>? = null
        val found: suspend (String, String, (String, String) -> Boolean) -> Related = { name, path, same ->
            asked = name to path
            val hits = listOf(
                RelatedHit(SourceKind.ARTICLE, "wikipedia_en_top_maxi", "Napoleon_Bonaparte", 0.95f),
                RelatedHit(SourceKind.ARTICLE, "wikipedia_en_top_maxi", "Napoleonic_Wars", 0.9f),
                RelatedHit(SourceKind.ARTICLE, "wikipedia_en_gone_maxi", "Elba", 0.85f),
            ).filterNot { same(it.target, it.key) }
            Related(hits, listOf(RelatedHit(SourceKind.BOOK, "book:granger:gc-war-and-peace", "OEBPS/c170.xhtml", 0.83f)))
        }
        val last = source(qids = QidIndexes(listOf(en)), related = found).topic("en-top", "Napoleon").toList().last()
        assertThat(asked).isEqualTo("wikipedia_en_top_maxi" to "Napoleon")
        // The redirect title is the page itself; a hit in a pack no longer installed is left out.
        assertThat(last.related).containsExactly(TopicArticle("en-top", "Napoleonic_Wars", "Napoleonic Wars", "About Napoleonic_Wars.", null))
        assertThat(last.relatedBooks).containsExactly(
            TopicBook(LibraryBook("book:granger:gc-war-and-peace", "Title of book:granger:gc-war-and-peace", null), "OEBPS/c170.xhtml"),
        )
    }

    @Test fun aPartThatFailsIsLeftOutNotThePage() = runBlocking<Unit> {
        val broken: suspend (String, String, (String, String) -> Boolean) -> Related = { _, _, _ -> error("vectors unreadable") }
        val last = source(related = broken).topic("en-top", "Napoleon").toList().last()
        assertThat(last.done).isTrue()
        assertThat(last.header).isNotNull()
        assertThat(last.related).isEmpty()
    }
}

class TwoLetterTest {
    @Test fun booksLanguagesBecomeTwoLetters() {
        assertThat(app.granger.twoLetter("en")).isEqualTo("en")
        assertThat(app.granger.twoLetter("eng")).isEqualTo("en")
        assertThat(app.granger.twoLetter("spa")).isEqualTo("es")
        assertThat(app.granger.twoLetter("en-GB")).isEqualTo("en")
        assertThat(app.granger.twoLetter("")).isNull()
    }
}

class AgeTest {
    @Test fun ageCountsBirthdays() {
        assertThat(ageOf(1769, 815, 1821, 505)).isEqualTo(51)   // Napoleon: died before his birthday
        assertThat(ageOf(1775, 1216, 1817, 718)).isEqualTo(41)  // Jane Austen
        assertThat(ageOf(1749, 828, 1832, 0)).isEqualTo(83)     // a death date known only to the year
        assertThat(ageOf(-383, 0, -321, 0)).isEqualTo(62)       // Aristotle, across BC years
    }
}

class QuoteSentenceTest {
    private val chapter = "Chapter One\nIn the midst of a conversation that was started about Napoleon’s Spanish affairs, which they all agreed in " +
        "approving, Prince Andrew began to express a contrary opinion. Nobody answered.\nLater, Napoleon was named again here."

    @Test fun theMentionsWholeSentence() {
        assertThat(QuoteSentence.around(chapter, "conversation that was started about ", "Napoleon"))
            .isEqualTo("In the midst of a conversation that was started about Napoleon’s Spanish affairs, which they all agreed in approving, Prince Andrew began to express a contrary opinion.")
    }

    @Test fun theWordsBeforeChooseTheOccurrence() {
        assertThat(QuoteSentence.around(chapter, "Later, ", "Napoleon")).isEqualTo("Later, Napoleon was named again here.")
    }

    @Test fun aRunOnIsCutToAWindowAtWords() {
        val long = "a ".repeat(300) + "Napoleon " + "b ".repeat(300)
        val s = QuoteSentence.around(long, "a a ", "Napoleon")!!
        assertThat(s).startsWith("…")
        assertThat(s).endsWith("…")
        assertThat(s).contains("Napoleon")
        assertThat(s.length).isAtMost(330)
    }

    @Test fun aQuoteNotInTheTextIsNull() {
        assertThat(QuoteSentence.around(chapter, "", "Wellington")).isNull()
    }
}
