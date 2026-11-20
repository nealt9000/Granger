package app.granger.core.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Topic records (Plan O6; tools/topicrecords/build.py): each article's topic-page data pre-joined into one compressed
 * record, read online with one range read a page. This is the format only, on byte arrays: no I/O, so it's pure for tests.
 *
 * The directory (`.grdir`, v2) lists each block's first QID, offset, length and CRC32; a block (`.grec`) is
 * n u16, n x (qid u32, len u32), then n zlib(JSON) records.
 */
class RecordsDirectory private constructor(
    val records: Int,
    /** The sha256 of the `.gqid` the records were built from. */
    val sourceSha256: String,
    private val firsts: LongArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val crcs: IntArray,
) {
    val blocks: Int get() = firsts.size

    /** Where [qid]'s block is, or null when it falls before the first block. */
    fun blockFor(qid: Long): Block? {
        var lo = 0; var hi = firsts.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (firsts[mid] <= qid) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (found < 0) null else Block(found, offsets[found], lengths[found], crcs[found])
    }

    /** One block: its index, where it starts in the `.grec`, how long it is, and its CRC32. */
    data class Block(val index: Int, val offset: Long, val length: Int, val crc: Int)

    companion object {
        private const val VERSION = 2
        private const val HEADER = 48
        private const val ENTRY = 20

        /** The directory in [bytes], or null when they aren't a v2 directory. */
        fun parse(bytes: ByteArray): RecordsDirectory? {
            if (bytes.size < HEADER || bytes.copyOfRange(0, 4).decodeToString() != "GRD2") return null
            val b = LittleEndian(bytes)
            if (b.short(4) != VERSION) return null
            val records = b.int(8)
            val n = b.int(12)
            if (n < 0 || bytes.size < HEADER + n.toLong() * ENTRY) return null
            val sha = bytes.copyOfRange(16, 48).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            val firsts = LongArray(n); val offsets = LongArray(n); val lengths = IntArray(n); val crcs = IntArray(n)
            for (i in 0 until n) {
                val at = HEADER + i * ENTRY
                firsts[i] = b.int(at).toLong() and 0xFFFFFFFFL
                offsets[i] = b.long(at + 4)
                lengths[i] = b.int(at + 12)
                crcs[i] = b.int(at + 16)
            }
            return RecordsDirectory(records, sha, firsts, offsets, lengths, crcs)
        }
    }
}

/** A block whose bytes don't match the directory's CRC32: the read was bad, or the file changed under the directory. */
class CorruptBlock(message: String) : Exception(message)

/** An article another record points to: its QID, its path (null when this index has none), rank and adult flags. */
data class RecordRef(val qid: Long, val path: String?, val rank: Int, val flags: Int)

/** Everything a topic page takes from one article's record (spec §6 Phase 2; plan O6 builder's call 7). */
data class TopicRecord(
    val qid: Long,
    val path: String,
    val rank: Int,
    /** The other language's title (the topic page's "Read in …"), when there is one. */
    val other: String?,
    val subjects: List<String>,
    /** Rooms, as facet keys "subject.facet". */
    val rooms: List<String>,
    val coordinates: Pair<Double, Double>?,
    /** Wikidata links: (property, value). */
    val links: List<Pair<String, String>>,
    val flags: Int,
    /** For a broad topic: its parts, as the local index reads them (items as QIDs), and every part item's reference. */
    val parts: TopicParts?,
    val items: Map<Long, RecordRef>,
    /** The broad topics that lead here, best known first (at most 8), and how many there are in all. */
    val holders: List<RecordRef>,
    val holderCount: Int,
)

/**
 * The record as volunteered screens show it (the adult filter, Plan AF): items flagged [ContentFlags.TOPIC] left out of
 * its parts and its "leads here", and its figures gone when it's flagged itself. The flags come in the record, so this
 * works for articles no local index holds.
 */
fun TopicRecord.withoutHidden(filterOn: Boolean): TopicRecord {
    if (!filterOn) return this
    fun hidden(q: Long) = (items[q]?.flags ?: 0) and ContentFlags.TOPIC != 0
    val p = parts?.let { p ->
        p.copy(
            kinds = p.kinds.filterNot { hidden(it.qid) }, links = p.links.filterNot { hidden(it.qid) }, people = p.people.filterNot(::hidden),
            figures = if (flags != 0) emptyList() else p.figures,
        )
    }
    return copy(parts = p, holders = holders.filterNot { it.flags and ContentFlags.TOPIC != 0 })
}

object TopicRecords {
    /** [qid]'s record in [block] (the bytes the directory's [entry] names), or null when the block doesn't hold it. */
    fun find(block: ByteArray, entry: RecordsDirectory.Block, qid: Long, packId: String): TopicRecord? {
        if (block.size != entry.length) throw CorruptBlock("block ${entry.index}: ${block.size} bytes, not ${entry.length}")
        val crc = crc32(block)
        if (crc != entry.crc) throw CorruptBlock("block ${entry.index}: crc mismatch")
        val b = LittleEndian(block)
        val n = b.short(0)
        var pos = 2 + 8 * n
        for (j in 0 until n) {
            val q = b.int(2 + 8 * j).toLong() and 0xFFFFFFFFL
            val len = b.int(2 + 8 * j + 4)
            if (q == qid) return parse(qid, inflate(block, pos, len), packId)
            pos += len
        }
        return null
    }

    private fun inflate(bytes: ByteArray, at: Int, len: Int): String = zlibInflate(bytes, at, len).decodeToString()

    private fun ref(a: JsonArray?): RecordRef? = a?.let {
        RecordRef(it.long(0), if (it.isNull(1)) null else it.str(1), it.int(2), it.int(3))
    }

    private fun strings(a: JsonArray?): List<String> = a?.map { (it as JsonPrimitive).content }.orEmpty()

    /** One record's JSON (see build.py's docstring for the keys). */
    internal fun parse(qid: Long, json: String, packId: String): TopicRecord {
        val o = Json.parseToJsonElement(json) as JsonObject
        val items = HashMap<Long, RecordRef>()
        val parts = o.arr("t")?.let { t ->
            val groups = ArrayList<TopicParts.Group>(); val kinds = ArrayList<TopicParts.Kind>()
            val sections = ArrayList<TopicParts.Section>(); val links = ArrayList<TopicParts.Link>()
            val figures = ArrayList<TopicParts.Figure>(); val people = ArrayList<Long>()
            val list = o.arr("parts") ?: JsonArray(emptyList())
            for (element in list) {
                val p = element as JsonArray
                val item = ref(p.getOrNull(1) as? JsonArray)
                item?.let { items[it.qid] = it }
                val itemQid = item?.qid ?: 0L
                val grp = if (p.isNull(2)) -1 else p.int(2)
                val text = if (p.isNull(3)) "" else p.str(3)
                val note = if (p.isNull(4)) "" else p.str(4)
                when (p.int(0)) {
                    TopicParts.GROUP -> groups += TopicParts.Group(itemQid, text, grp)
                    TopicParts.KIND -> kinds += TopicParts.Kind(itemQid, grp)
                    TopicParts.SECTION -> sections += TopicParts.Section(note, text)
                    TopicParts.LINK -> links += TopicParts.Link(itemQid, grp)
                    TopicParts.FIGURE -> figures += TopicParts.Figure(note, text, grp)
                    TopicParts.PERSON -> people += itemQid
                }
            }
            if (groups.isEmpty() && kinds.isEmpty() && sections.isEmpty() && people.isEmpty()) null
            else TopicParts(qid, packId, t.int(0), groups, kinds, sections, links, figures, people, t.int(1))
        }
        val holders = o.arr("h")?.map { ref(it as JsonArray)!! }.orEmpty()
        return TopicRecord(
            qid = qid,
            path = (o.getValue("p") as JsonPrimitive).content,
            rank = o.int("r"),
            other = o.str("o").takeIf { it.isNotEmpty() },
            subjects = strings(o.arr("s")),
            rooms = strings(o.arr("f")),
            coordinates = o.arr("c")?.let { it.double(0) to it.double(1) },
            links = o.arr("l")?.map { (it as JsonArray).let { p -> p.str(0) to p.str(1) } }.orEmpty(),
            flags = o.int("x"),
            parts = parts,
            items = items,
            holders = holders,
            holderCount = o.int("hn", holders.size),
        )
    }
}

// org.json's forgiving reads, over kotlinx.serialization (Plan I3).
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
private fun JsonObject.int(key: String, default: Int = 0): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default
private fun JsonArray.isNull(i: Int): Boolean = getOrNull(i) == null || getOrNull(i) is JsonNull
private fun JsonArray.str(i: Int): String = (this[i] as JsonPrimitive).content
private fun JsonArray.int(i: Int): Int = (getOrNull(i) as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonArray.long(i: Int): Long = (this[i] as JsonPrimitive).longOrNull ?: 0L
private fun JsonArray.double(i: Int): Double = (this[i] as JsonPrimitive).doubleOrNull ?: 0.0

/** Little-endian reads over a byte array (`ByteBuffer.order(LITTLE_ENDIAN)`'s, for common code). */
internal class LittleEndian(private val b: ByteArray) {
    fun short(at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun int(at: Int): Int = short(at) or (short(at + 2) shl 16)
    fun long(at: Int): Long = (int(at).toLong() and 0xFFFFFFFFL) or (int(at + 4).toLong() shl 32)
}

/** zlib's CRC32 of [bytes], as a signed Int (`CRC32().value.toInt()`). */
internal expect fun crc32(bytes: ByteArray): Int

/** [len] bytes of [bytes] from [at], zlib-inflated. */
internal expect fun zlibInflate(bytes: ByteArray, at: Int, len: Int): ByteArray
