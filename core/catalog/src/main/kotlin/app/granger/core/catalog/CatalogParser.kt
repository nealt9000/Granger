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
        val packs = entries<PackEntry>(root, "packs")
            .filter { valid(it) && DATE.matches(it.snapshotDate) }
            .distinctBy { it.id }
        val books = entries<BookEntry>(root, "books")
            .filter { valid(it) && DATE.matches(it.releaseDate) }
            .distinctBy { it.id }
        val models = entries<ModelEntry>(root, "models")
            .filter { valid(it) && SAFE_NAME.matches(it.fileName) }
            .distinctBy { it.id }
            // Two models must never share a target file (compared ignoring case, as SD cards do).
            .distinctBy { it.fileName.lowercase() }
        val voices = entries<VoiceEntry>(root, "voices")
            .filter { valid(it) && it.engine.isNotBlank() }
            .distinctBy { it.id }
        val ids = (packs.map { it.id } + models.map { it.id } + books.map { it.id } + voices.map { it.id }).toSet()
        val presets = entries<Preset>(root, "presets").map { preset -> preset.copy(items = preset.items.filter { it in ids }) }
        val readiness = (root["readiness"] as? JsonObject)?.let { readiness(it, packs, books) }
        return ParseResult.Ok(Catalog(version, generatedAt, packs, models, books, presets, voices, readiness))
    }

    /**
     * The readiness model (Plan H1): a domain with a blank or repeated id is dropped, so is a tier token that names no
     * pack, book, collection or family in this catalog, and essential items are folded into comprehensive.
     */
    private fun readiness(obj: JsonObject, packs: List<PackEntry>, books: List<BookEntry>): Readiness? {
        val parsed = runCatching { json.decodeFromJsonElement<Readiness>(obj) }.getOrNull() ?: return null
        val ids = packs.mapTo(HashSet()) { it.id }.apply { books.mapTo(this) { it.id } }
        val collections = books.mapTo(HashSet()) { it.collection }
        val families = packs.mapNotNullTo(HashSet()) { it.family.ifBlank { null } }
        fun known(token: String) = when {
            token.startsWith("@") -> token.drop(1) in collections
            token.startsWith("family:") -> token.removePrefix("family:") in families
            else -> token in ids
        }
        val seen = HashSet<String>()
        val domains = parsed.domains
            .filter { it.id.isNotBlank() && it.title.isNotBlank() && seen.add(it.id) }
            .map { domain ->
                val essential = domain.essential.filter(::known).distinct()
                domain.copy(essential = essential, comprehensive = (essential + domain.comprehensive.filter(::known)).distinct())
            }
        return if (domains.isEmpty()) null else Readiness(domains)
    }

    private inline fun <reified T> entries(root: JsonObject, key: String): List<T> =
        (root[key] as? JsonArray).orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

    private fun valid(item: CatalogItem): Boolean =
        SAFE_NAME.matches(item.id) &&
            SHA256.matches(item.sha256) &&
            item.sizeBytes > 0 &&
            item.urls.isNotEmpty() &&
            item.urls.all { it.startsWith("https://") }
}
