package app.granger.readiness

import app.granger.core.catalog.downloadBytes
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.Domain
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.formatBytes
import app.granger.sources.CatalogScope
import app.granger.sources.scopeOf
import java.net.URI
import java.time.LocalDate

/** How much of a knowledge domain the phone holds (spec §3.4). */
enum class Coverage { NONE, BASIC, COMPREHENSIVE }

data class DomainCoverage(
    val id: String,
    val title: String,
    val coverage: Coverage,
    /** Every installed, whole item with this domain, tier or not. */
    val installedBytes: Long,
    /** The catalog size of the essential tier (a `family:` item counts its smallest pack). */
    val essentialBytes: Long,
    /** The catalog size of the comprehensive tier (which includes the essential one). */
    val comprehensiveBytes: Long,
    /** Essential items not installed, smallest first; `family:` items are left out (onboarding picks the region). */
    val missingEssential: List<CatalogItem>,
)

/** One sentence about a gap, with what the screen can do about it (spec §4). */
sealed interface Signal {
    val text: String

    /** Nothing of the domain is installed; [item] is the smallest missing essential. */
    data class EmptyDomain(val domain: String, val item: CatalogItem, override val text: String) : Signal

    /** An installed pack over a year old whose catalog entry has a newer snapshot. */
    data class Stale(val domain: String?, val packId: String, override val text: String) : Signal

    /** An installed pack without a full-text index: Search and Research can't look inside it. */
    data class Unsearchable(val packId: String, override val text: String) : Signal
}

enum class Source(val title: String) {
    KIWIX("Kiwix"),
    GRANGER("Granger"),
    ARCHIVE_ORG("Archive.org"),
    LIBRIVOX("LibriVox"),
    MY_BOOKS("My books"),
    MODELS("AI models"),
    VOICES("Voices"),
    PHONE_MEDIA("Music & audiobooks on the phone"),
}

data class StorageRow(
    val source: Source,
    val bytes: Long,
    val items: Int,
    /** The phone's own media: referenced by Granger, not stored by it. */
    val referencedOnly: Boolean = false,
)

data class ReadinessReport(
    val domains: List<DomainCoverage>,
    val signals: List<Signal>,
    /** Every [Source], in order; the screen hides the empty ones. */
    val storage: List<StorageRow>,
    /** The cached catalog predates the readiness model (spec §8). */
    val needsCatalogRefresh: Boolean,
) {
    companion object {
        val EMPTY = ReadinessReport(emptyList(), emptyList(), emptyList(), needsCatalogRefresh = true)
    }
}

/**
 * The readiness model (spec §3): what the phone holds per domain, computed from the cached catalog, the library's packs and
 * books, and the download ledger. Pure; no I/O. Only whole, installed files count (Decision 5).
 */
object ReadinessModel {
    private const val STALE_AFTER_DAYS = 365L

    fun report(catalog: Catalog?, packs: List<Pack>, books: List<Book>, records: List<DownloadRecord>, today: LocalDate): ReadinessReport {
        val installed = records.filter { it.state == DownloadState.INSTALLED }
        val wholePacks = packs.filter { it.status == PackStatus.OK }
        val wholeBooks = books.filter { it.status == PackStatus.OK }
        val resolved = Resolved(catalog, wholePacks, wholeBooks)
        val domains = catalog?.readiness?.domains.orEmpty().map { resolved.coverage(it) }
        return ReadinessReport(
            domains = domains,
            signals = signals(domains, resolved, wholePacks, installed, today),
            storage = storage(resolved, packs, books, installed),
            needsCatalogRefresh = catalog?.readiness == null,
        )
    }

    /** Installed packs and Granger books matched to their catalog entries (Decision 4). */
    private class Resolved(val catalog: Catalog?, packs: List<Pack>, books: List<Book>) {
        private val byUuid = catalog?.packs.orEmpty().associateBy { it.zimUuid }
        private val byName = catalog?.packs.orEmpty().groupBy { it.zimName }
        private val bookEntries = catalog?.books.orEmpty().associateBy { it.id }

        /** Each whole pack with its entry (null when the catalog doesn't know it). */
        val packEntries: Map<Pack, PackEntry?> = packs.associateWith { byUuid[it.id] ?: byName[it.name]?.firstOrNull() }
        private val installedNames = packEntries.values.filterNotNull().mapTo(HashSet()) { it.zimName }
        private val installedFamilies = packEntries.values.filterNotNull().mapNotNullTo(HashSet()) { it.family.ifBlank { null } }

        /** A ZIM is held when it, or a ZIM that holds its articles, is installed (Plan H4, Decision 7). */
        private fun held(zimName: String): Boolean =
            zimName in installedNames || catalog?.readiness?.containers(zimName).orEmpty().any { it in installedNames }

        /** Granger books that are whole, with their entries. */
        val granger: Map<Book, BookEntry> = books.filter { it.origin == BookOrigin.GRANGER }
            .mapNotNull { book -> bookEntries[book.sourceRef]?.let { book to it } }.toMap()
        private val installedBookIds = granger.values.mapTo(HashSet()) { it.id }

        fun entryOf(pack: Pack): PackEntry? = packEntries[pack]

        fun coverage(domain: Domain): DomainCoverage {
            val installedBytes = packEntries.entries.sumOf { (pack, entry) -> if (entry?.domain == domain.id) pack.sizeBytes else 0L } +
                granger.entries.sumOf { (book, entry) -> if (entry.domain == domain.id) book.sizeBytes else 0L }
            val essential = domain.essential.map { token(it, domain.id) }
            val comprehensive = domain.comprehensive.map { token(it, domain.id) }
            val coverage = when {
                comprehensive.isNotEmpty() && comprehensive.all { it.complete } -> Coverage.COMPREHENSIVE
                essential.any { it.started } -> Coverage.BASIC
                else -> Coverage.NONE
            }
            return DomainCoverage(
                id = domain.id,
                title = domain.title,
                coverage = coverage,
                installedBytes = installedBytes,
                essentialBytes = tierBytes(essential),
                comprehensiveBytes = tierBytes(comprehensive),
                missingEssential = essential.filterNot { it.started }.mapNotNull { it.missing }.sortedBy { it.sizeBytes },
            )
        }

        /** What a tier token stands for and whether it's installed; [zimName] for a pack token. */
        private class Token(val started: Boolean, val complete: Boolean, val bytes: Long, val missing: CatalogItem?, val zimName: String? = null)

        /** A tier's catalog size: one entry per ZIM (its largest listed flavour), nothing for a ZIM another in the tier holds. */
        private fun tierBytes(tokens: List<Token>): Long {
            val byZim = tokens.filter { !it.zimName.isNullOrBlank() }.groupBy { it.zimName!! }.mapValues { (_, t) -> t.maxOf { it.bytes } }
            val containers = catalog?.readiness
            val counted = byZim.filterKeys { zim -> containers?.containers(zim).orEmpty().none { it in byZim } }
            return counted.values.sum() + tokens.filter { it.zimName.isNullOrBlank() }.sumOf { it.bytes }
        }

        private fun token(token: String, domainId: String): Token = when {
            token.startsWith("@") -> {
                val collection = token.drop(1)
                val entries = catalog?.books.orEmpty().filter { it.collection == collection && it.domain == domainId }
                val have = entries.filter { it.id in installedBookIds }
                Token(
                    started = have.isNotEmpty(),
                    complete = entries.isNotEmpty() && have.size == entries.size,
                    bytes = entries.sumOf { it.sizeBytes },
                    missing = entries.filter { it.id !in installedBookIds }.minByOrNull { it.sizeBytes },
                )
            }
            token.startsWith("family:") -> {
                val family = token.removePrefix("family:")
                val entries = catalog?.packs.orEmpty().filter { it.family == family }
                val have = family in installedFamilies
                Token(started = have, complete = have, bytes = entries.minOfOrNull { it.sizeBytes } ?: 0L, missing = null)
            }
            else -> {
                val pack = catalog?.packs.orEmpty().firstOrNull { it.id == token }
                val book = if (pack == null) bookEntries[token] else null
                val have = when {
                    pack != null -> held(pack.zimName)
                    book != null -> book.id in installedBookIds
                    else -> false
                }
                val item: CatalogItem? = pack ?: book
                Token(started = have, complete = have, bytes = item?.sizeBytes ?: 0L, missing = if (have) null else item, zimName = pack?.zimName)
            }
        }
    }

    private fun signals(
        domains: List<DomainCoverage>,
        resolved: Resolved,
        packs: List<Pack>,
        installed: List<DownloadRecord>,
        today: LocalDate,
    ): List<Signal> {
        val empty = domains.filter { it.coverage == Coverage.NONE && it.missingEssential.isNotEmpty() }.map { d ->
            val item = d.missingEssential.first()
            // A domain can hold packs outside its tiers (Stack Overflow in Technology): its bar isn't empty, only its essentials.
            val lead = if (d.installedBytes > 0L) "No essentials yet for" else "Nothing yet for"
            Signal.EmptyDomain(d.id, item, "$lead ${d.title}. Get ${item.title}, ${formatBytes(item.downloadBytes)}.")
        }
        val recordByPath = installed.associateBy { it.filePath }
        val stale = packs.sortedBy { it.title.lowercase() }.mapNotNull { pack ->
            val entry = resolved.entryOf(pack) ?: return@mapNotNull null
            val installedDate = parseDate(recordByPath[pack.filePath]?.snapshotDate ?: pack.date) ?: return@mapNotNull null
            val newest = parseDate(entry.snapshotDate) ?: return@mapNotNull null
            if (installedDate.plusDays(STALE_AFTER_DAYS) < today && newest > installedDate) {
                Signal.Stale(entry.domain.ifBlank { null }, entry.id, "${pack.title} is from $installedDate; a newer one is in the catalog.")
            } else {
                null
            }
        }
        // A map has nothing to search by text: its missing index isn't a gap (Neal, U3a walkthrough).
        val unsearchable = packs.filterNot { it.hasFulltextIndex || resolved.entryOf(it)?.family == "maps" }.sortedBy { it.title.lowercase() }.map { pack ->
            Signal.Unsearchable(pack.id, "${pack.title} can't be searched: the pack has no index.")
        }
        return empty + stale + unsearchable
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

    private fun storage(resolved: Resolved, packs: List<Pack>, books: List<Book>, installed: List<DownloadRecord>): List<StorageRow> {
        val bytes = LongArray(Source.entries.size)
        val items = IntArray(Source.entries.size)
        fun add(source: Source, size: Long, count: Int = 1) {
            bytes[source.ordinal] += size
            items[source.ordinal] += count
        }
        val groups = HashSet<String>()
        val byItem = installed.associateBy { it.itemId }
        for (record in installed) {
            if (record.kind == ItemKind.VECTORS) {
                // Search vectors (Plan R1) are part of their item: its source, its count.
                val parent = VectorDownloads.parentOf(record.itemId)?.let(byItem::get)
                add(parent?.let(::sourceOf) ?: Source.GRANGER, record.sizeBytes, 0)
                continue
            }
            val source = sourceOf(record)
            val group = record.groupId
            val counts = group == null || groups.add(group)
            add(source, record.sizeBytes, if (counts) 1 else 0)
        }
        // Packs the ledger doesn't know (pushed by hand) count where their catalog scope says, else as Kiwix's.
        val recordedPaths = installed.mapTo(HashSet()) { it.filePath }
        for (pack in packs) {
            if (pack.filePath in recordedPaths || pack.status == PackStatus.MISSING) continue
            val entry = resolved.entryOf(pack)
            add(if (entry != null && scopeOf(entry) == CatalogScope.GRANGER) Source.GRANGER else Source.KIWIX, pack.sizeBytes)
        }
        for (book in books) {
            if (book.origin == BookOrigin.IMPORT && book.status != PackStatus.MISSING) add(Source.MY_BOOKS, book.sizeBytes)
        }
        return Source.entries.map { source ->
            StorageRow(source, bytes[source.ordinal], items[source.ordinal], referencedOnly = source == Source.PHONE_MEDIA)
        }
    }

    /** Where a pack the ledger doesn't know (pushed by hand) counts: its catalog scope, else Kiwix. */
    fun sourceOf(pack: Pack, catalog: Catalog?): Source {
        val entry = catalog?.packs?.let { all -> all.firstOrNull { it.zimUuid == pack.id } ?: all.firstOrNull { it.zimName == pack.name } }
        return if (entry != null && scopeOf(entry) == CatalogScope.GRANGER) Source.GRANGER else Source.KIWIX
    }

    fun sourceOf(record: DownloadRecord): Source {
        val host = record.urls.firstOrNull()?.let { runCatching { URI(it).host }.getOrNull() }?.lowercase().orEmpty()
        return when {
            record.kind == ItemKind.MODEL -> Source.MODELS
            record.kind == ItemKind.VOICE -> Source.VOICES
            record.groupId?.startsWith("media:librivox:") == true -> Source.LIBRIVOX
            host == "archive.org" || host.endsWith(".archive.org") -> Source.ARCHIVE_ORG
            host == "kiwix.org" || host.endsWith(".kiwix.org") -> Source.KIWIX
            else -> Source.GRANGER
        }
    }
}
