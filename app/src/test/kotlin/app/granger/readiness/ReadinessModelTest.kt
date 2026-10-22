package app.granger.readiness

import app.granger.SHA_A
import app.granger.book
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.Domain
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.library.BookOrigin
import app.granger.core.library.PackStatus
import app.granger.pack
import app.granger.record
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReadinessModelTest {
    private fun packEntry(id: String, zimName: String, domain: String, size: Long, family: String = "wikipedia", fts: Boolean = true, snapshot: String = "2026-10-10") =
        PackEntry(
            id = id, title = "Title $id", category = "Reference", language = "eng", sizeBytes = size, sha256 = SHA_A,
            urls = listOf("https://download.kiwix.org/zim/$id.zim"), snapshotDate = snapshot, license = "CC BY-SA 4.0",
            hasFulltextIndex = fts, zimUuid = "u-$id", zimName = zimName, family = family, domain = domain,
        )

    private fun bookEntry(id: String, domain: String, size: Long) = BookEntry(
        id = id, title = "Book $id", category = "Drama", language = "en", sizeBytes = size, sha256 = SHA_A,
        urls = listOf("https://github.com/nealt9000/Granger/releases/download/c/$id.epub"), releaseDate = "2026-10-17",
        license = "Public domain (US)", collection = "granger-classics", domain = domain,
    )

    private val medNopic = packEntry("wikipedia_en_medicine_nopic", "wikipedia_en_medicine", "medicine", 1_000)
    private val medMaxi = packEntry("wikipedia_en_medicine_maxi", "wikipedia_en_medicine", "medicine", 1_800)
    private val wikem = packEntry("wikem_en_all_maxi", "wikem_en_all", "medicine", 400, family = "wikem")
    private val top = packEntry("wikipedia_en_top_nopic", "wikipedia_en_top", "reference", 2_700)
    private val france = packEntry("maps_en_france", "maps_en_france", "maps", 300, family = "maps")
    private val spain = packEntry("maps_en_spain", "maps_en_spain", "maps", 200, family = "maps")
    private val ifixit = packEntry("ifixit_en_all", "ifixit_en_all", "making", 3_500, family = "ifixit", fts = false)
    private val bible = bookEntry("gc-bible", "thought", 150)
    private val republic = bookEntry("gc-republic", "thought", 50)
    private val hamlet = bookEntry("gc-hamlet", "literature", 30)

    private val readiness = Readiness(
        listOf(
            Domain("medicine", "Medicine & first aid", essential = listOf("wikipedia_en_medicine_nopic"),
                comprehensive = listOf("wikipedia_en_medicine_nopic", "wikem_en_all_maxi")),
            Domain("making", "Making, repair & engineering", essential = listOf("ifixit_en_all"), comprehensive = listOf("ifixit_en_all")),
            Domain("maps", "Maps, places & travel", essential = listOf("family:maps"), comprehensive = listOf("family:maps")),
            Domain("reference", "General reference", essential = listOf("wikipedia_en_top_nopic"), comprehensive = listOf("wikipedia_en_top_nopic")),
            Domain("thought", "Religion, philosophy & history", essential = listOf("@granger-classics"), comprehensive = listOf("@granger-classics")),
        ),
    )
    private val catalog = Catalog(
        1, "2026-10-22T00:00:00Z", packs = listOf(medNopic, medMaxi, wikem, top, france, spain, ifixit),
        books = listOf(bible, republic, hamlet), readiness = readiness,
    )
    private val today = LocalDate.of(2026, 10, 22)

    /** A library pack as the ZIM scanner would record it. */
    private fun installed(entry: PackEntry, size: Long = entry.sizeBytes, status: PackStatus = PackStatus.OK, date: String = entry.snapshotDate) =
        pack(entry.zimUuid, entry.title, status).copy(filePath = "/internal/packs/${entry.id}.zim", name = entry.zimName, sizeBytes = size, hasFulltextIndex = entry.hasFulltextIndex, date = date)

    private fun report(packs: List<app.granger.core.library.Pack> = emptyList(), books: List<app.granger.core.library.Book> = emptyList(), records: List<app.granger.core.downloads.DownloadRecord> = emptyList(), cat: Catalog? = catalog) =
        ReadinessModel.report(cat, packs, books, records, today)

    private fun domain(r: ReadinessReport, id: String) = r.domains.single { it.id == id }

    @Test fun nothingInstalledIsNoneEverywhereWithEmptyDomainSignals() {
        val r = report()
        assertThat(r.domains.map { it.id }).containsExactly("medicine", "making", "maps", "reference", "thought").inOrder()
        assertThat(r.domains.map { it.coverage }).doesNotContain(Coverage.BASIC)
        assertThat(r.domains.all { it.coverage == Coverage.NONE }).isTrue()
        assertThat(r.needsCatalogRefresh).isFalse()
        // Maps' only essential is a family token, so it has no signal (onboarding picks the region).
        val empty = r.signals.filterIsInstance<Signal.EmptyDomain>()
        assertThat(empty.map { it.domain }).containsExactly("medicine", "making", "reference", "thought").inOrder()
        assertThat(empty.first().text).isEqualTo("Nothing yet for Medicine & first aid. Get Title wikipedia_en_medicine_nopic, 1.0 KB.")
        // The smallest missing Classics book of the domain is the one named.
        assertThat(empty.last().item.id).isEqualTo("gc-republic")
    }

    @Test fun oneEssentialPackMakesBasic() {
        val r = report(packs = listOf(installed(medNopic)))
        val med = domain(r, "medicine")
        assertThat(med.coverage).isEqualTo(Coverage.BASIC)
        assertThat(med.installedBytes).isEqualTo(1_000)
        assertThat(med.essentialBytes).isEqualTo(1_000)
        assertThat(med.comprehensiveBytes).isEqualTo(1_400)
        assertThat(med.missingEssential).isEmpty()
        assertThat(r.signals.filterIsInstance<Signal.EmptyDomain>().map { it.domain }).doesNotContain("medicine")
    }

    @Test fun theOtherFlavourOfAnEssentialPackCounts() {
        val r = report(packs = listOf(installed(medMaxi)))
        val med = domain(r, "medicine")
        assertThat(med.coverage).isEqualTo(Coverage.BASIC)
        assertThat(med.installedBytes).isEqualTo(1_800)
    }

    @Test fun everyComprehensiveItemMakesComprehensive() {
        val r = report(packs = listOf(installed(medNopic), installed(wikem)))
        assertThat(domain(r, "medicine").coverage).isEqualTo(Coverage.COMPREHENSIVE)
        assertThat(domain(r, "medicine").installedBytes).isEqualTo(1_400)
    }

    @Test fun aMissingOrCorruptPackCountsForNothing() {
        val r = report(packs = listOf(installed(medNopic, status = PackStatus.MISSING), installed(wikem, status = PackStatus.CORRUPT)))
        val med = domain(r, "medicine")
        assertThat(med.coverage).isEqualTo(Coverage.NONE)
        assertThat(med.installedBytes).isEqualTo(0)
    }

    @Test fun aFamilyTokenIsSatisfiedByAnyPackOfTheFamily() {
        assertThat(domain(report(), "maps").coverage).isEqualTo(Coverage.NONE)
        val r = report(packs = listOf(installed(spain)))
        val maps = domain(r, "maps")
        assertThat(maps.coverage).isEqualTo(Coverage.COMPREHENSIVE)
        assertThat(maps.installedBytes).isEqualTo(200)
        // The tier's size is the family's smallest pack.
        assertThat(maps.essentialBytes).isEqualTo(200)
    }

    @Test fun oneClassicsBookOfTheDomainMakesBasicAllMakeComprehensive() {
        val one = report(books = listOf(book("gc-bible").copy(sizeBytes = 150)))
        assertThat(domain(one, "thought").coverage).isEqualTo(Coverage.BASIC)
        assertThat(domain(one, "thought").installedBytes).isEqualTo(150)
        assertThat(domain(one, "thought").essentialBytes).isEqualTo(200)
        val all = report(books = listOf(book("gc-bible"), book("gc-republic")))
        assertThat(domain(all, "thought").coverage).isEqualTo(Coverage.COMPREHENSIVE)
        // Hamlet is literature, which this catalog's model doesn't list: it moves nothing.
        val wrongDomain = report(books = listOf(book("gc-hamlet")))
        assertThat(domain(wrongDomain, "thought").coverage).isEqualTo(Coverage.NONE)
    }

    @Test fun importedAndArchiveBooksNeverCountTowardADomain() {
        val imported = book("my-notes").copy(origin = BookOrigin.IMPORT, sourceRef = "gc-bible")
        val archive = book("gc-republic").copy(origin = BookOrigin.ARCHIVE_ORG)
        val r = report(books = listOf(imported, archive))
        assertThat(domain(r, "thought").coverage).isEqualTo(Coverage.NONE)
        assertThat(domain(r, "thought").installedBytes).isEqualTo(0)
    }

    @Test fun bytesCountEveryInstalledItemOfTheDomain() {
        // Both flavours on the phone (odd, but possible): both count toward the domain's bytes.
        val r = report(packs = listOf(installed(medNopic), installed(medMaxi)))
        assertThat(domain(r, "medicine").installedBytes).isEqualTo(2_800)
    }

    @Test fun aPackUnknownToTheCatalogIsStorageOnly() {
        val stranger = pack("u-stranger", "Stranger").copy(name = "stranger_en_all", sizeBytes = 777, filePath = "/internal/packs/stranger.zim")
        val r = report(packs = listOf(stranger))
        assertThat(r.domains.sumOf { it.installedBytes }).isEqualTo(0)
        assertThat(r.storage.single { it.source == Source.KIWIX }.bytes).isEqualTo(777)
    }

    @Test fun staleAndUnsearchableSignals() {
        val oldMed = installed(medNopic, date = "2025-01-10")
        val medRecord = record("wikipedia_en_medicine_nopic", DownloadState.INSTALLED, filePath = oldMed.filePath).copy(snapshotDate = "2025-01-10")
        val r = report(packs = listOf(oldMed, installed(ifixit)), records = listOf(medRecord))
        val stale = r.signals.filterIsInstance<Signal.Stale>().single()
        assertThat(stale.packId).isEqualTo("wikipedia_en_medicine_nopic")
        assertThat(stale.domain).isEqualTo("medicine")
        assertThat(stale.text).isEqualTo("Title wikipedia_en_medicine_nopic is from 2025-01-10; a newer one is in the catalog.")
        val unsearchable = r.signals.filterIsInstance<Signal.Unsearchable>().single()
        assertThat(unsearchable.packId).isEqualTo("u-ifixit_en_all")
        assertThat(unsearchable.text).isEqualTo("Title ifixit_en_all can't be searched: the pack has no index.")
    }

    @Test fun aRecentPackOrOneTheCatalogHasNoNewerSnapshotForIsNotStale() {
        val recent = installed(medNopic, date = "2026-06-01")
        val oldButNewest = installed(top, date = "2025-01-10")
        val catalogWithOldTop = catalog.copy(packs = catalog.packs.map { if (it.id == top.id) it.copy(snapshotDate = "2025-01-10") else it })
        val r = report(packs = listOf(recent, oldButNewest), cat = catalogWithOldTop)
        assertThat(r.signals.filterIsInstance<Signal.Stale>()).isEmpty()
    }

    @Test fun signalsAreOrderedEmptyThenStaleThenUnsearchable() {
        val oldMed = installed(medNopic, date = "2025-01-10")
        // Medicine and making are Basic; maps has only a family token; reference and thought are empty.
        val r = report(packs = listOf(installed(ifixit), oldMed))
        val kinds = r.signals.map { it::class.simpleName }
        assertThat(kinds).containsExactly("EmptyDomain", "EmptyDomain", "Stale", "Unsearchable").inOrder()
    }

    @Test fun storageBySourceSplitsByKindAndHost() {
        val kiwix = record("wikipedia_en_medicine_nopic", DownloadState.INSTALLED, sizeBytes = 1_000)
            .copy(urls = listOf("https://download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim"))
        val granger = record("gc-bible", DownloadState.INSTALLED, kind = ItemKind.BOOK, sizeBytes = 150, filePath = "/internal/books/granger/gc-bible.epub")
            .copy(urls = listOf("https://github.com/nealt9000/Granger/releases/download/classics/gc-bible.epub"))
        val archiveBook = record("frick", DownloadState.INSTALLED, kind = ItemKind.BOOK, sizeBytes = 80, filePath = "/internal/books/archive-org/frick.epub")
            .copy(urls = listOf("https://archive.org/download/frick/frick.epub"))
        val chapters = (1..3).map { n ->
            record("librivox-817-$n", DownloadState.INSTALLED, kind = ItemKind.AUDIO, sizeBytes = 100, filePath = "/internal/audiobooks/817/$n.mp3")
                .copy(urls = listOf("https://archive.org/download/book817/$n.mp3"), groupId = "media:librivox:817")
        }
        val album = (1..2).map { n ->
            record("holst-$n", DownloadState.INSTALLED, kind = ItemKind.AUDIO, sizeBytes = 50, filePath = "/internal/music/holst/$n.mp3")
                .copy(urls = listOf("https://ia800.us.archive.org/holst/$n.mp3"), groupId = "media:archive:holst")
        }
        val model = record("e2b", DownloadState.INSTALLED, kind = ItemKind.MODEL, sizeBytes = 2_000, filePath = "/internal/models/e2b.litertlm")
        val voice = record("ljspeech", DownloadState.INSTALLED, kind = ItemKind.VOICE, sizeBytes = 67, filePath = "/internal/voices/ljspeech.zip")
        val queued = record("wikem_en_all_maxi", DownloadState.QUEUED, sizeBytes = 400)
        val imported = book("my-notes").copy(origin = BookOrigin.IMPORT, sizeBytes = 9)
        val r = report(records = listOf(kiwix, granger, archiveBook) + chapters + album + listOf(model, voice, queued), books = listOf(imported))
        val rows = r.storage.associateBy { it.source }
        assertThat(rows.getValue(Source.KIWIX).bytes).isEqualTo(1_000)
        assertThat(rows.getValue(Source.GRANGER).bytes).isEqualTo(150)
        assertThat(rows.getValue(Source.ARCHIVE_ORG).bytes).isEqualTo(80 + 100)
        assertThat(rows.getValue(Source.ARCHIVE_ORG).items).isEqualTo(2)
        assertThat(rows.getValue(Source.LIBRIVOX).bytes).isEqualTo(300)
        assertThat(rows.getValue(Source.LIBRIVOX).items).isEqualTo(1)
        assertThat(rows.getValue(Source.MY_BOOKS).bytes).isEqualTo(9)
        assertThat(rows.getValue(Source.MODELS).bytes).isEqualTo(2_000)
        assertThat(rows.getValue(Source.VOICES).bytes).isEqualTo(67)
        assertThat(rows.getValue(Source.PHONE_MEDIA).bytes).isEqualTo(0)
        assertThat(rows.getValue(Source.PHONE_MEDIA).referencedOnly).isTrue()
        assertThat(r.storage.map { it.source }).isEqualTo(Source.entries)
    }

    @Test fun anOlderCatalogAsksForARefresh() {
        val old = catalog.copy(readiness = null)
        val r = report(packs = listOf(installed(ifixit)), cat = old)
        assertThat(r.needsCatalogRefresh).isTrue()
        assertThat(r.domains).isEmpty()
        // What doesn't need the model still shows.
        assertThat(r.signals.filterIsInstance<Signal.Unsearchable>()).hasSize(1)
        assertThat(r.storage.single { it.source == Source.KIWIX }.bytes).isEqualTo(3_500)
        assertThat(report(cat = null).needsCatalogRefresh).isTrue()
    }

    // --- Plan H4: one entry per ZIM, containment ---

    private val top1m = packEntry("wikipedia_en_top1m_nopic", "wikipedia_en_top1m", "reference", 17_000)
    private val calibrated = Catalog(
        1, "2026-10-22T00:00:00Z", packs = listOf(medNopic, medMaxi, wikem, top, top1m),
        readiness = Readiness(
            listOf(
                Domain("medicine", "Medicine & first aid", essential = listOf("wikipedia_en_medicine_nopic"),
                    comprehensive = listOf("wikipedia_en_medicine_nopic", "wikipedia_en_medicine_maxi", "wikem_en_all_maxi")),
                Domain("reference", "General reference", essential = listOf("wikipedia_en_top_nopic"),
                    comprehensive = listOf("wikipedia_en_top_nopic", "wikipedia_en_top1m_nopic")),
            ),
            contains = mapOf("wikipedia_en_top1m" to listOf("wikipedia_en_top"), "wikipedia_en_all" to listOf("wikipedia_en_*")),
        ),
    )

    @Test fun tierBytesCountEachZimOnceAtItsLargestListedFlavour() {
        val med = domain(report(cat = calibrated), "medicine")
        assertThat(med.essentialBytes).isEqualTo(1_000)
        // WikiMed with pictures (1,800) once, not beside the one without (1,000): 1,800 + 400.
        assertThat(med.comprehensiveBytes).isEqualTo(2_200)
    }

    @Test fun aContainedZimCountsNothingBesideItsContainer() {
        val ref = domain(report(cat = calibrated), "reference")
        assertThat(ref.essentialBytes).isEqualTo(2_700)
        assertThat(ref.comprehensiveBytes).isEqualTo(17_000)
    }

    @Test fun aContainerSatisfiesTheItemsItHolds() {
        val r = report(packs = listOf(installed(top1m)), cat = calibrated)
        val ref = domain(r, "reference")
        assertThat(ref.coverage).isEqualTo(Coverage.COMPREHENSIVE)
        assertThat(ref.missingEssential).isEmpty()
        assertThat(r.signals.filterIsInstance<Signal.EmptyDomain>().map { it.domain }).doesNotContain("reference")
    }

    @Test fun installedBytesStayWhatIsOnThePhone() {
        val r = report(packs = listOf(installed(top), installed(top1m)), cat = calibrated)
        assertThat(domain(r, "reference").installedBytes).isEqualTo(19_700)
    }
}

