package app.granger.core.library

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

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
            if (bytes.size < HEADER || String(bytes, 0, 4, Charsets.US_ASCII) != "GRD2") return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getShort(4).toInt() != VERSION) return null
            val records = b.getInt(8)
            val n = b.getInt(12)
            if (n < 0 || bytes.size < HEADER + n.toLong() * ENTRY) return null
            val sha = bytes.copyOfRange(16, 48).joinToString("") { "%02x".format(it) }
            val firsts = LongArray(n); val offsets = LongArray(n); val lengths = IntArray(n); val crcs = IntArray(n)
            for (i in 0 until n) {
                val at = HEADER + i * ENTRY
                firsts[i] = b.getInt(at).toLong() and 0xFFFFFFFFL
                offsets[i] = b.getLong(at + 4)
                lengths[i] = b.getInt(at + 12)
                crcs[i] = b.getInt(at + 16)
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

object TopicRecords {
    /** [qid]'s record in [block] (the bytes the directory's [entry] names), or null when the block doesn't hold it. */
    fun find(block: ByteArray, entry: RecordsDirectory.Block, qid: Long, packId: String): TopicRecord? {
        if (block.size != entry.length) throw CorruptBlock("block ${entry.index}: ${block.size} bytes, not ${entry.length}")
        val crc = CRC32().apply { update(block) }.value.toInt()
        if (crc != entry.crc) throw CorruptBlock("block ${entry.index}: crc mismatch")
        val b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(0).toInt() and 0xFFFF
        var pos = 2 + 8 * n
        for (j in 0 until n) {
            val q = b.getInt(2 + 8 * j).toLong() and 0xFFFFFFFFL
            val len = b.getInt(2 + 8 * j + 4)
            if (q == qid) return parse(qid, inflate(block, pos, len), packId)
            pos += len
        }
        return null
    }

    private fun inflate(bytes: ByteArray, at: Int, len: Int): String {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, at, len)
            val out = java.io.ByteArrayOutputStream(len * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val k = inflater.inflate(buf)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, k)
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    private fun ref(a: JSONArray?): RecordRef? = a?.let {
        RecordRef(it.getLong(0), if (it.isNull(1)) null else it.getString(1), it.optInt(2), it.optInt(3))
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    /** One record's JSON (see build.py's docstring for the keys). */
    internal fun parse(qid: Long, json: String, packId: String): TopicRecord {
        val o = JSONObject(json)
        val items = HashMap<Long, RecordRef>()
        val parts = o.optJSONArray("t")?.let { t ->
            val groups = ArrayList<TopicParts.Group>(); val kinds = ArrayList<TopicParts.Kind>()
            val sections = ArrayList<TopicParts.Section>(); val links = ArrayList<TopicParts.Link>()
            val figures = ArrayList<TopicParts.Figure>(); val people = ArrayList<Long>()
            val list = o.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until list.length()) {
                val p = list.getJSONArray(i)
                val item = ref(p.optJSONArray(1))
                item?.let { items[it.qid] = it }
                val itemQid = item?.qid ?: 0L
                val grp = if (p.isNull(2)) -1 else p.getInt(2)
                val text = if (p.isNull(3)) "" else p.getString(3)
                val note = if (p.isNull(4)) "" else p.getString(4)
                when (p.getInt(0)) {
                    TopicParts.GROUP -> groups += TopicParts.Group(itemQid, text, grp)
                    TopicParts.KIND -> kinds += TopicParts.Kind(itemQid, grp)
                    TopicParts.SECTION -> sections += TopicParts.Section(note, text)
                    TopicParts.LINK -> links += TopicParts.Link(itemQid, grp)
                    TopicParts.FIGURE -> figures += TopicParts.Figure(note, text, grp)
                    TopicParts.PERSON -> people += itemQid
                }
            }
            if (groups.isEmpty() && kinds.isEmpty() && sections.isEmpty() && people.isEmpty()) null
            else TopicParts(qid, packId, t.getInt(0), groups, kinds, sections, links, figures, people, t.optInt(1))
        }
        val holders = o.optJSONArray("h")?.let { h -> List(h.length()) { ref(h.getJSONArray(it))!! } }.orEmpty()
        return TopicRecord(
            qid = qid,
            path = o.getString("p"),
            rank = o.optInt("r"),
            other = o.optString("o").takeIf { it.isNotEmpty() },
            subjects = strings(o.optJSONArray("s")),
            rooms = strings(o.optJSONArray("f")),
            coordinates = o.optJSONArray("c")?.let { it.getDouble(0) to it.getDouble(1) },
            links = o.optJSONArray("l")?.let { l -> List(l.length()) { l.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }.orEmpty(),
            flags = o.optInt("x"),
            parts = parts,
            items = items,
            holders = holders,
            holderCount = o.optInt("hn", holders.size),
        )
    }
}
