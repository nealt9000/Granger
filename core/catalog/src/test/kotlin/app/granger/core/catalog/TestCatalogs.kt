package app.granger.core.catalog

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.util.Base64

/** The RFC 8032 §7.1 TEST 1 key pair. Public knowledge; for tests only. */
object TestKeys {
    val SEED: ByteArray = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val PUBLIC: ByteArray = Base64.getDecoder().decode("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=")

    /** The content of a `.sig` file for [data]. */
    fun sign(data: ByteArray, seed: ByteArray = SEED): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(data, 0, data.size)
        return (Base64.getEncoder().encodeToString(signer.generateSignature()) + "\n").toByteArray()
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

const val SHA_A = "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976"
const val SHA_B = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"

/** A small valid catalog: one pack, one evaluated and one unevaluated model, one preset. */
fun catalogJson(
    generatedAt: String = "2026-10-16T00:00:00Z",
    formatVersion: Int = 1,
    packTitle: String = "WikiMed",
    modelFileName: String = "e2b.litertlm",
    packUrl: String = "https://download.kiwix.org/zim/wikipedia/med.zim",
    snapshotDate: String = "2026-10-10",
): ByteArray = """
    {"formatVersion": $formatVersion, "generatedAt": "$generatedAt",
     "packs": [{"id": "med", "title": "$packTitle", "category": "Medical", "language": "eng", "sizeBytes": 1000,
                "sha256": "$SHA_A", "urls": ["$packUrl"], "snapshotDate": "$snapshotDate", "license": "CC BY-SA 4.0",
                "hasFulltextIndex": true, "zimUuid": "u-med", "zimName": "wikipedia_en_medicine"}],
     "models": [{"id": "e2b", "title": "E2B", "role": "small", "fileName": "$modelFileName", "sizeBytes": 2000,
                 "minRamGb": 6, "sha256": "$SHA_B", "url": "https://huggingface.co/e2b", "license": "Apache-2.0",
                 "evalPassed": true},
                {"id": "e4b", "title": "E4B", "role": "large", "fileName": "e4b.litertlm", "sizeBytes": 3000,
                 "minRamGb": 8, "sha256": "$SHA_B", "url": "https://huggingface.co/e4b", "license": "Apache-2.0",
                 "evalPassed": false}],
     "presets": [{"id": "starter", "title": "Starter", "items": ["e2b", "med"]}]}
""".trimIndent().toByteArray()

/** A file under the repo's `testdata/` folder, found by walking up from the module directory. */
fun testdata(name: String): File {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "testdata").isDirectory) dir = dir.parentFile
    return File(dir ?: error("testdata/ not found"), "testdata/$name")
}

/** A catalog with one pack, two books (the second optionally dated [secondBookDate]) and a preset naming a book. */
fun catalogWithBooksJson(secondBookDate: String = "2026-10-17"): ByteArray = """
    {"formatVersion": 1, "generatedAt": "2026-10-17T00:00:00Z",
     "packs": [{"id": "med", "title": "WikiMed", "category": "Medical", "language": "eng", "sizeBytes": 1000,
                "sha256": "$SHA_A", "urls": ["https://download.kiwix.org/zim/med.zim"], "snapshotDate": "2026-10-10",
                "license": "CC BY-SA 4.0", "hasFulltextIndex": true, "zimUuid": "u-med", "zimName": "n"}],
     "books": [{"id": "gc-dracula", "title": "Dracula", "author": "Bram Stoker", "category": "Fiction", "language": "en",
                "sizeBytes": 500, "sha256": "$SHA_A", "urls": ["https://example.com/gc-dracula.epub"],
                "releaseDate": "2026-10-17", "license": "Public domain (US)", "collection": "granger-classics", "published": 1897},
               {"id": "gc-emma", "title": "Emma", "category": "Fiction", "language": "en",
                "sizeBytes": 600, "sha256": "$SHA_B", "urls": ["https://example.com/gc-emma.epub"],
                "releaseDate": "$secondBookDate", "license": "Public domain (US)", "collection": "granger-classics"}],
     "presets": [{"id": "classics", "title": "Classics", "items": ["gc-dracula", "gc-emma", "med", "missing"]}]}
""".trimIndent().toByteArray()
