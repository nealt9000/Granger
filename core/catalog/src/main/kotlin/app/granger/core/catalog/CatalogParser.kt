package app.granger.core.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.time.Instant

enum class CatalogError { NETWORK, BAD_SIGNATURE, UNSUPPORTED_FORMAT, MALFORMED, OLDER_THAN_CACHED }

sealed interface ParseResult {
    data class Ok(val catalog: Catalog) : ParseResult
    data class Invalid(val error: CatalogError) : ParseResult
}

object CatalogParser {
    const val FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    /** Ids and model file names become file names on the phone. */
    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    /** Pack snapshot and book release dates are part of the file name too. */
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Checks the signature first, then parses. Entries that don't validate are dropped, not fatal. */
    fun parse(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): ParseResult {
        if (!CatalogSignature.verify(bytes, signature, publicKey)) return ParseResult.Invalid(CatalogError.BAD_SIGNATURE)
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ParseResult.Invalid(CatalogError.MALFORMED)
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: return ParseResult.Invalid(CatalogError.MALFORMED)
        if (version != FORMAT_VERSION) return ParseResult.Invalid(CatalogError.UNSUPPORTED_FORMAT)
        val generatedAt = (root["generatedAt"] as? JsonPrimitive)?.contentOrNull
        if (generatedAt == null || runCatching { Instant.parse(generatedAt) }.isFailure) {
            return ParseResult.Invalid(CatalogError.MALFORMED)
        }
        val packs = entries<PackEntry>(root, "packs", retryWithout = listOf(VECTORS, TIME_INDEX))
            .filter { valid(it) && DATE.matches(it.snapshotDate) }
            .distinctBy { it.id }
            .map { if (it.vectors == null || valid(it.vectors)) it else it.copy(vectors = null) }
            .map { if (it.timeIndex == null || valid(it.timeIndex)) it else it.copy(timeIndex = null) }
            .map(::family)
            .map { it.copy(language = Languages.normalise(it.language), i18n = i18n(it.i18n)) }
        val books = entries<BookEntry>(root, "books", retryWithout = listOf(VECTORS))
            .filter { valid(it) && DATE.matches(it.releaseDate) }
            .distinctBy { it.id }
            .map { if (it.vectors == null || valid(it.vectors)) it else it.copy(vectors = null) }
            .map { it.copy(language = Languages.normalise(it.language)) }
        val models = entries<ModelEntry>(root, "models")
            .filter { valid(it) && SAFE_NAME.matches(it.fileName) }
            .distinctBy { it.id }
            // Two models must never share a target file (compared ignoring case, as SD cards do).
            .distinctBy { it.fileName.lowercase() }
        val voices = entries<VoiceEntry>(root, "voices")
            .filter { valid(it) && it.engine.isNotBlank() }
            .distinctBy { it.id }
        val ids = (packs.map { it.id } + models.map { it.id } + books.map { it.id } + voices.map { it.id }).toSet()
        val presets = entries<Preset>(root, "presets").map { preset ->
            preset.copy(items = preset.items.filter { it in ids }, language = Languages.normalise(preset.language).ifBlank { ENGLISH },
                i18n = i18n(preset.i18n))
        }
        val readiness = (root["readiness"] as? JsonObject)?.let { readiness(it, packs, books) }
        val onboarding = (root["onboarding"] as? JsonObject)?.let { onboarding(it, packs, books) }
        return ParseResult.Ok(Catalog(version, generatedAt, packs, models, books, presets, voices, readiness, onboarding))
    }

    /**
     * The readiness model (Plan H1): a domain with a blank or repeated id is dropped, so is a tier token that names no
     * pack, book, collection or family in this catalog, and essential items are folded into comprehensive. A `contains`
     * entry whose key is no pack's ZIM name is dropped (Plan H4). Each other language's lists (Plan L3) are checked and
     * folded the same way, and a language whose lists are empty after checking is dropped.
     */
    private fun readiness(obj: JsonObject, packs: List<PackEntry>, books: List<BookEntry>): Readiness? {
        val parsed = runCatching { json.decodeFromJsonElement<Readiness>(obj) }.getOrNull() ?: return null
        val known = tokenChecker(packs, books)
        val seen = HashSet<String>()
        val domains = parsed.domains
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && seen.add(it.id) }
            .map { domain ->
                val essential = domain.essential.filter(known).distinct()
                val languages = languages(domain.languages) { tiers ->
                    val e = tiers.essential.filter(known).distinct()
                    Tiers(e, (e + tiers.comprehensive.filter(known)).distinct()).takeIf { it.comprehensive.isNotEmpty() }
                }
                domain.copy(essential = essential, comprehensive = (essential + domain.comprehensive.filter(known)).distinct(),
                    i18n = i18n(domain.i18n), languages = languages)
            }
        val zimNames = packs.mapNotNullTo(HashSet()) { it.zimName.ifBlank { null } }
        return if (domains.isEmpty()) null else Readiness(domains, parsed.contains.filterKeys { it in zimNames })
    }

    /** Whether a readiness-style token names something in this catalog. */
    private fun tokenChecker(packs: List<PackEntry>, books: List<BookEntry>): (String) -> Boolean {
        val ids = packs.mapTo(HashSet()) { it.id }.apply { books.mapTo(this) { it.id } }
        val collections = books.mapTo(HashSet()) { it.collection }
        val families = packs.mapNotNullTo(HashSet()) { it.family.ifBlank { null } }
        return { token ->
            when {
                token.startsWith("@") -> token.drop(1) in collections
                token.startsWith("family:") -> token.removePrefix("family:") in families
                else -> token in ids
            }
        }
    }

    /**
     * Onboarding's tables (Plan H3): a use case with a blank or repeated id, or none of whose items resolve, is dropped;
     * so is a larger area that isn't a maps pack. Null when nothing is left. A use case's items in another language
     * (Plan L3) are checked the same way; a language with none left is dropped, so the use case isn't offered in it.
     */
    private fun onboarding(obj: JsonObject, packs: List<PackEntry>, books: List<BookEntry>): Onboarding? {
        val parsed = runCatching { json.decodeFromJsonElement<Onboarding>(obj) }.getOrNull() ?: return null
        val known = tokenChecker(packs, books)
        val maps = packs.filter { it.family == "maps" }.mapTo(HashSet()) { it.id }
        val seen = HashSet<String>()
        val useCases = parsed.useCases
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && seen.add(it.id) }
            .map { useCase ->
                useCase.copy(items = useCase.items.filter(known).distinct(), i18n = i18n(useCase.i18n),
                    languages = languages(useCase.languages) { Items(it.items.filter(known).distinct()).takeIf { i -> i.items.isNotEmpty() } })
            }
            .filter { it.items.isNotEmpty() }
        val larger = parsed.larger.filter { it in maps }.distinct()
        val covered = parsed.covered.filterKeys { it in maps }.mapValues { (_, names) -> names.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
            .filterValues { it.isNotEmpty() }
        return if (useCases.isEmpty() && larger.isEmpty() && covered.isEmpty()) null else Onboarding(useCases, larger, covered)
    }

    /**
     * Another language's lists (Plan L3), keyed by normalised code: English (which lives in the plain fields) and blank
     * codes are dropped, and so is a language [check] returns null for.
     */
    private fun <T : Any> languages(byCode: Map<String, T>, check: (T) -> T?): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for ((code, lists) in byCode) {
            val key = Languages.normalise(code)
            if (key.isEmpty() || key == ENGLISH || ',' in key || key in out) continue
            check(lists)?.let { out[key] = it }
        }
        return out
    }

    /** Copy keyed by normalised language code (Plan L3); a blank code is dropped. */
    private fun i18n(byCode: Map<String, Copy>): Map<String, Copy> {
        if (byCode.isEmpty()) return byCode
        val out = LinkedHashMap<String, Copy>()
        for ((code, copy) in byCode) {
            val key = Languages.normalise(code)
            if (key.isNotEmpty() && ',' !in key && key !in out) out[key] = copy
        }
        return out
    }

    /**
     * An entry that doesn't decode is dropped; with [retryWithout], it is first tried again without each of those
     * optional fields in turn (so a bad time index doesn't cost a pack its vectors), then without all of them.
     */
    private inline fun <reified T> entries(root: JsonObject, key: String, retryWithout: List<String> = emptyList()): List<T> =
        (root[key] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull() ?: (element as? JsonObject)?.let { obj ->
                val present = retryWithout.filter { it in obj }
                (present.map { listOf(it) } + listOf(present)).filter { it.isNotEmpty() }.firstNotNullOfOrNull { drop ->
                    runCatching { json.decodeFromJsonElement<T>(JsonObject(obj - drop.toSet())) }.getOrNull()
                }
            }
        }

    private const val VECTORS = "vectors"
    private const val TIME_INDEX = "timeIndex"

    /** The `.gtime` layout this app reads (core/library `TimeIndex.FORMAT`); a newer one is left for a newer app. */
    const val TIME_INDEX_FORMAT = 1

    /**
     * [pack]'s Wikipedia-family fields made consistent (Plan WF): a set without a rank of 1 or more is no set, a
     * subject's shares outside 0..1 are dropped, and a pack is never both a slice and a subject.
     */
    private fun family(pack: PackEntry): PackEntry {
        var p = pack
        if (p.setId.isBlank() || p.setRank < 1) p = p.copy(setId = "", setRank = 0, setLabel = "")
        if (p.isSlice || p.subjectOf.isBlank()) p = p.copy(subjectOf = "", adds = emptyMap())
        val adds = p.adds.filterValues { it in 0.0..1.0 }
        if (adds.size != p.adds.size) p = p.copy(adds = adds)
        return if (p.articles < 0) p.copy(articles = 0) else p
    }

    /** A vectors object that names a file we can fetch and check, built by a chunker and at dims the phone can scan. */
    private fun valid(v: VectorsEntry): Boolean =
        v.url.startsWith("https://") &&
            SHA256.matches(v.sha256) &&
            SHA256.matches(v.embedderSha256) &&
            v.sizeBytes > 0 &&
            v.dims > 0 &&
            v.chunkerVersion > 0

    /** A time index the app can fetch, check and read: an https file with a checksum, in the format this app reads. */
    private fun valid(t: TimeIndexEntry): Boolean =
        t.url.startsWith("https://") && SHA256.matches(t.sha256) && t.sizeBytes > 0 && t.format == TIME_INDEX_FORMAT

    private fun valid(item: CatalogItem): Boolean =
        SAFE_NAME.matches(item.id) &&
            SHA256.matches(item.sha256) &&
            item.sizeBytes > 0 &&
            item.urls.isNotEmpty() &&
            item.urls.all { it.startsWith("https://") }
}
