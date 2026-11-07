package app.granger.home

import app.granger.core.library.IndexPages
import app.granger.core.library.Pack
import app.granger.core.library.PageTitles
import app.granger.core.library.SearchHit
import app.granger.core.zim.ZimArchive
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser

/** One article in Wander. [onPhone] is false for a link no pack here holds (shown dashed; Plan H5a Decision 3). */
data class WanderNode(val packId: String, val path: String, val title: String, val imagePath: String? = null, val onPhone: Boolean = true)

/** Wander's state: the [centre] and its [summary], the [orbit] around it, the [trail] behind, and the start search. */
data class WanderUi(
    val centre: WanderNode? = null,
    val summary: String = "",
    val orbit: List<WanderNode> = emptyList(),
    val trail: List<WanderNode> = emptyList(),
    val loading: Boolean = false,
    /** False when the library has no encyclopedia: Home hides Wander. */
    val available: Boolean = true,
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
)

/**
 * Wander (Plan H5a): walk an encyclopedia by the links in each article's lead ([LeadLinks]). Owned by
 * [HomeViewModel], so it lives as long as Home's view model; nothing is persisted. Reads archives on [io].
 */
class WanderModel(
    private val scope: CoroutineScope,
    /** The library's OK Wikipedia packs, general ones and the library's languages first (Decision 3). */
    private val encyclopedias: suspend () -> List<Pack>,
    private val archiveOf: (Pack) -> ZimArchive?,
    /** A cached thumbnail for an article, or null; see [ArticleThumbnails]. */
    private val thumbnail: (archive: ZimArchive, path: String, html: String) -> String?,
    /** Title search across the library ([app.granger.core.library.LibrarySearch.titles]). */
    private val titles: suspend (String) -> List<SearchHit>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    private val debounceMillis: Long = 250L,
) {
    private val state = MutableStateFlow(WanderUi())
    val ui: StateFlow<WanderUi> = state.asStateFlow()

    private var moving: Job? = null
    private var searching: Job? = null

    /** Starts somewhere random the first time Wander is shown; later calls keep where you are. */
    fun ensureStarted() {
        if (state.value.centre == null && moving?.isActive != true) start()
    }

    /** Somewhere else: a random article with at least [MIN_REACHABLE] links on the phone (Decision 4); clears the trail. */
    fun start() = move(clearTrail = true) {
        val packs = encyclopedias()
        if (packs.isEmpty()) return@move null
        var best: Loaded? = null
        repeat(START_TRIES) {
            val pack = packs[random.nextInt(packs.size)]
            val archive = archiveOf(pack) ?: return@repeat
            val path = archive.randomPath()?.takeUnless { IndexPages.isIndex(it) } ?: return@repeat
            val loaded = load(pack, archive, path, packs) ?: return@repeat
            if (best == null || loaded.reachable > best!!.reachable) best = loaded
            if (loaded.reachable >= MIN_REACHABLE) return@move loaded
        }
        best
    }

    /** Steps to [node] from the centre, which joins the trail. */
    fun go(node: WanderNode) {
        if (!node.onPhone) return
        val from = state.value.centre
        move(push = from) { at(node.packId, node.path) }
    }

    /** Back to the trail's [index]th crumb; the crumbs after it are dropped (Decision 6). */
    fun back(index: Int) {
        val trail = state.value.trail
        val node = trail.getOrNull(index) ?: return
        move(trailTo = trail.take(index)) { at(node.packId, node.path) }
    }

    /** Starts at an article chosen elsewhere (a search hit, the timeline's lens); clears the trail. */
    fun startFrom(packId: String, path: String) = move(clearTrail = true) { at(packId, path) }

    /** The start search's text; titles in the encyclopedias, at most [MAX_HITS], after a short pause. */
    fun search(query: String) {
        state.update { it.copy(query = query, hits = if (query.isBlank()) emptyList() else it.hits) }
        searching?.cancel()
        if (query.isBlank()) return
        searching = scope.launch {
            delay(debounceMillis)
            val ids = withContext(io) { encyclopedias().mapTo(HashSet()) { it.id } }
            val hits = runCatching { titles(query.trim()) }.getOrDefault(emptyList())
                .filter { it.packId in ids && it.path.isNotEmpty() }
                .distinctBy { it.title }
                .take(MAX_HITS)
            state.update { if (it.query == query) it.copy(hits = hits) else it }
        }
    }

    fun pick(hit: SearchHit) {
        searching?.cancel()
        state.update { it.copy(query = "", hits = emptyList()) }
        startFrom(hit.packId, hit.path)
    }

    private suspend fun at(packId: String, path: String): Loaded? {
        val packs = encyclopedias()
        val pack = packs.firstOrNull { it.id == packId } ?: return null
        val archive = archiveOf(pack) ?: return null
        return load(pack, archive, path, packs)
    }

    /**
     * Runs [find] on [io] and shows what it found, replacing any move in flight. The trail is cleared, set to
     * [trailTo], or gains [push] (capped at [TRAIL]). Orbit pictures and titles then fill in one by one.
     */
    private fun move(clearTrail: Boolean = false, push: WanderNode? = null, trailTo: List<WanderNode>? = null, find: suspend () -> Loaded?) {
        moving?.cancel()
        state.update { it.copy(loading = true) }
        moving = scope.launch {
            val loaded = withContext(io) { runCatching { find() }.getOrNull() }
            if (loaded == null) {
                val none = withContext(io) { encyclopedias().isEmpty() }
                state.update { it.copy(loading = false, available = !none) }
                return@launch
            }
            state.update { s ->
                val trail = when {
                    clearTrail -> emptyList()
                    trailTo != null -> trailTo
                    push != null -> (s.trail + push.copy()).takeLast(TRAIL)
                    else -> s.trail
                }
                s.copy(centre = loaded.centre, summary = loaded.summary, orbit = loaded.orbit, trail = trail, loading = false, available = true)
            }
            // Each orbit article's own title and picture, read after the constellation shows.
            val packs = withContext(io) { encyclopedias() }.associateBy { it.id }
            for (node in loaded.orbit) {
                if (!node.onPhone) continue
                val filled = withContext(io) {
                    val pack = packs[node.packId] ?: return@withContext null
                    val archive = archiveOf(pack) ?: return@withContext null
                    val html = archive.read(node.path)?.let { String(it.bytes, Charsets.UTF_8) } ?: return@withContext null
                    node.copy(title = titleOf(html, pack, node.path), imagePath = runCatching { thumbnail(archive, node.path, html) }.getOrNull())
                } ?: continue
                state.update { s -> if (s.centre?.path != loaded.centre.path) s else s.copy(orbit = s.orbit.map { if (it.path == node.path && it.packId == node.packId) filled else it }) }
            }
        }
    }

    private class Loaded(val centre: WanderNode, val summary: String, val orbit: List<WanderNode>, val reachable: Int)

    /** [path] in [pack] as a centre: its lead, and its links resolved in its own pack first, then the others (Decision 3). */
    private fun load(pack: Pack, archive: ZimArchive, path: String, packs: List<Pack>): Loaded? {
        val resolved = archive.resolvePath(path) ?: return null
        val item = archive.read(resolved) ?: return null
        if (!item.mimeType.startsWith("text/html")) return null
        val html = String(item.bytes, Charsets.UTF_8)
        val lead = LeadLinks.parse(html, resolved)
        val centre = WanderNode(pack.id, resolved, cleanTitle(lead.title, pack, resolved), runCatching { thumbnail(archive, resolved, html) }.getOrNull())
        val others = listOf(pack) + packs.filter { it.id != pack.id }
        val reachable = ArrayList<WanderNode>()
        val missing = ArrayList<WanderNode>()
        val seen = HashSet<String>()
        for (link in lead.links) {
            if (reachable.size >= ORBIT) break
            val name = link.substringAfterLast('/')
            var found: WanderNode? = null
            for (p in others) {
                val a = (if (p.id == pack.id) archive else archiveOf(p)) ?: continue
                // The same article in another layout: "A/Cat" in one pack is "Cat" in another.
                val hit = a.resolvePath(link) ?: a.resolvePath(name).takeIf { link != name } ?: a.resolvePath("A/$name")
                if (hit != null) { found = WanderNode(p.id, hit, plainTitle(hit)); break }
            }
            val key = (found?.path ?: link).substringAfterLast('/')
            if (!seen.add(key) || key == resolved.substringAfterLast('/')) continue
            if (found != null) reachable += found else missing += WanderNode(pack.id, link, plainTitle(link), onPhone = false)
        }
        val orbit = if (reachable.size >= MIN_REACHABLE) reachable else reachable + missing.take(MIN_REACHABLE)
        return Loaded(centre, lead.summary, orbit, reachable.size)
    }

    /** The page's `<title>` without its site, read without parsing the page; else the path's name. */
    private fun titleOf(html: String, pack: Pack, path: String): String =
        cleanTitle(TITLE.find(html)?.groupValues?.get(1)?.let { Parser.unescapeEntities(it, false) }.orEmpty(), pack, path)

    private fun cleanTitle(title: String, pack: Pack, path: String): String =
        title.trim().ifBlank { null }?.let { PageTitles.clean(it, pack.title) } ?: plainTitle(path)

    private fun plainTitle(path: String) = path.substringAfterLast('/').replace('_', ' ')

    companion object {
        /**
         * The packs Wander walks (Decision 3): OK Wikipedia packs, those in the library's [languages] first in that
         * order, a whole encyclopedia (Everything, Top) before a subject within each language.
         */
        fun encyclopediasOf(packs: List<Pack>, languages: List<String>): List<Pack> {
            val wiki = packs.filter { it.status == app.granger.core.library.PackStatus.OK && it.name.startsWith("wikipedia_") }
                .sortedBy { !GENERAL.containsMatchIn(it.name) }
            val chosen = LinkedHashSet<Pack>()
            for (lang in languages) wiki.filterTo(chosen) { app.granger.core.catalog.Languages.has(it.language, lang) }
            chosen += wiki
            return chosen.toList()
        }

        private val GENERAL = Regex("^wikipedia_[a-z]{2,3}_(all|top|top1m|100)(_|$)")
        const val ORBIT = 8
        const val MIN_REACHABLE = 3
        const val TRAIL = 12
        const val MAX_HITS = 6
        private const val START_TRIES = 8
        private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
