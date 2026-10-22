package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class CatalogParserTest {
    private fun parse(bytes: ByteArray, sig: ByteArray = TestKeys.sign(bytes), key: ByteArray = TestKeys.PUBLIC) =
        CatalogParser.parse(bytes, sig, key)

    private fun ok(result: ParseResult) = (result as ParseResult.Ok).catalog

    @Test fun rfc8032VectorVerifies() {
        val sig = TestKeys.hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val sigFile = Base64.getEncoder().encodeToString(sig).toByteArray()
        assertThat(CatalogSignature.verify(ByteArray(0), sigFile, TestKeys.PUBLIC)).isTrue()
    }

    @Test fun verifiesTheCatalogSignedByCatalogGen() {
        val bytes = testdata("catalog/test-catalog.json").readBytes()
        val sig = testdata("catalog/test-catalog.json.sig").readBytes()
        val catalog = ok(CatalogParser.parse(bytes, sig, TestKeys.PUBLIC))
        // "bad-sha" and "../escape" are dropped; so is the dangling preset item.
        assertThat(catalog.packs.map { it.id }).containsExactly("wikipedia_en_medicine_nopic")
        assertThat(catalog.models.map { it.id }).containsExactly("gemma-4-e2b-gpu", "gemma-4-e4b-gpu").inOrder()
        assertThat(catalog.presets.single().items).containsExactly("gemma-4-e2b-gpu", "wikipedia_en_medicine_nopic")

        val med = catalog.packs.single()
        assertThat(med.sizeBytes).isEqualTo(1047708672L)
        assertThat(med.zimUuid).isEqualTo("1f8cf2fe-e076-e018-2e09-004387350cba")
        assertThat(med.zimName).isEqualTo("wikipedia_en_medicine")
        assertThat(med.snapshotDate).isEqualTo("2026-10-10")
        assertThat(med.hasFulltextIndex).isTrue()
        assertThat(med.topics).isEmpty()
        assertThat(catalog.models.first().role).isEqualTo(ModelRole.SMALL)
        assertThat(catalog.models.first().urls).containsExactly("https://huggingface.co/x/resolve/abc/gemma-4-E2B-it-gpu.litertlm")
        assertThat(catalog.item("wikipedia_en_medicine_nopic")).isSameInstanceAs(med)
    }

    @Test fun voicesParseAndOldCatalogsWithoutThemStillDo() {
        assertThat(ok(parse(catalogJson())).voices).isEmpty()
        val withVoices = catalogJson().decodeToString().trimEnd().removeSuffix("}") + """,
            "voices": [
              {"id": "vits-piper-en_US-ljspeech-medium", "title": "Granger voice", "engine": "piper", "language": "en-US",
               "sizeBytes": 67456670, "unpackedBytes": 81529587, "sha256": "$SHA_A",
               "url": "https://github.com/nealt9000/Granger/releases/download/voices-v1/v.zip", "license": "Public domain",
               "attribution": "LJ Speech"},
              {"id": "bad url", "title": "x", "engine": "piper", "language": "en-US", "sizeBytes": 1, "sha256": "$SHA_A",
               "url": "http://insecure/v.zip", "license": "x"},
              {"id": "noengine", "title": "x", "engine": "", "language": "en-US", "sizeBytes": 1, "sha256": "$SHA_A",
               "url": "https://ok/v.zip", "license": "x"}
            ]}"""
        val catalog = ok(parse(withVoices.toByteArray()))
        assertThat(catalog.voices.map { it.id }).containsExactly("vits-piper-en_US-ljspeech-medium")
        val voice = catalog.voices.single()
        assertThat(voice.engine).isEqualTo("piper")
        assertThat(voice.unpackedBytes).isEqualTo(81529587L)
        assertThat(voice.urls).containsExactly("https://github.com/nealt9000/Granger/releases/download/voices-v1/v.zip")
        assertThat(catalog.item("vits-piper-en_US-ljspeech-medium")).isEqualTo(voice)
        assertThat(catalog.items).contains(voice)
    }

    @Test fun nonCommercialDefaultsToFalse() {
        assertThat(ok(parse(catalogJson())).packs.single().nonCommercial).isFalse()
    }

    @Test fun nonCommercialIsRead() {
        val catalog = ok(parse(catalogJson(packExtra = ""","nonCommercial": true""")))
        assertThat(catalog.packs.single().nonCommercial).isTrue()
    }

    @Test fun featuredAndFamilyDefaultWhenAbsent() {
        val pack = ok(parse(catalogJson())).packs.single()
        assertThat(pack.featured).isFalse()
        assertThat(pack.family).isEmpty()
    }

    @Test fun featuredAndFamilyAreRead() {
        val pack = ok(parse(catalogJson(packExtra = ""","featured": true, "family": "wikipedia""""))).packs.single()
        assertThat(pack.featured).isTrue()
        assertThat(pack.family).isEqualTo("wikipedia")
    }

    @Test fun aThousandPacksParse() {
        val packs = (1..1000).joinToString(",") { i ->
            """{"id": "p$i", "title": "Pack $i", "category": "Q&A", "language": "eng", "sizeBytes": 1000,
               "sha256": "$SHA_A", "urls": ["https://download.kiwix.org/zim/p$i.zim"], "snapshotDate": "2026-10-10",
               "license": "CC BY-SA 4.0", "hasFulltextIndex": true, "zimUuid": "u$i", "zimName": "p$i"}"""
        }
        val bytes = """{"formatVersion": 1, "generatedAt": "2026-10-20T00:00:00Z", "packs": [$packs]}""".toByteArray()
        assertThat(ok(parse(bytes)).packs).hasSize(1000)
    }

    @Test fun tamperedBytesFailTheSignature() {
        val bytes = catalogJson()
        val sig = TestKeys.sign(bytes)
        val tampered = catalogJson(packTitle = "WikiMeD")
        assertThat(parse(tampered, sig)).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
    }

    @Test fun otherKeysAndGarbageSignaturesFail() {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val otherPublic = (gen.generateKeyPair().public as Ed25519PublicKeyParameters).encoded
        val bytes = catalogJson()
        assertThat(parse(bytes, key = otherPublic)).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, sig = "not base64!!".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, sig = ByteArray(0))).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, key = ByteArray(5))).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
    }

    @Test fun futureFormatIsUnsupported() {
        assertThat(parse(catalogJson(formatVersion = 2))).isEqualTo(ParseResult.Invalid(CatalogError.UNSUPPORTED_FORMAT))
    }

    @Test fun signedGarbageIsMalformed() {
        assertThat(parse("{not json".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
        assertThat(parse("[]".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
        assertThat(parse(catalogJson(generatedAt = "yesterday"))).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
    }

    @Test fun unsafeFileNamesAndPlainHttpAreDropped() {
        val catalog = ok(parse(catalogJson(modelFileName = "../../databases/library.db", packUrl = "http://example.com/med.zim")))
        assertThat(catalog.models.map { it.id }).containsExactly("e4b")
        assertThat(catalog.packs).isEmpty()
        assertThat(catalog.presets.single().items).isEmpty()

        val traversal = ok(parse(catalogJson(snapshotDate = "../../../x")))
        assertThat(traversal.packs).isEmpty()
    }

    @Test fun aModelReusingAnEarlierFileNameIsDropped() {
        // Two models must never download to the same file; SD cards ignore case, so neither may differ only by case.
        assertThat(ok(parse(catalogJson(modelFileName = "e4b.litertlm"))).models.map { it.id }).containsExactly("e2b")
        assertThat(ok(parse(catalogJson(modelFileName = "E4B.litertlm"))).models.map { it.id }).containsExactly("e2b")
    }

    @Test fun releaseBuildsListOnlyEvaluatedModels() {
        val catalog = ok(parse(catalogJson()))
        assertThat(catalog.forBuild(includeUnevaluatedModels = true).models.map { it.id }).containsExactly("e2b", "e4b")
        assertThat(catalog.forBuild(includeUnevaluatedModels = false).models.map { it.id }).containsExactly("e2b")
        assertThat(catalog.items.map { it.id }).containsExactly("e2b", "e4b", "med").inOrder()
    }

    @Test fun booksParseAndAppearInItems() {
        val catalog = ok(parse(catalogWithBooksJson()))
        assertThat(catalog.books.map { it.id }).containsExactly("gc-dracula", "gc-emma").inOrder()
        val dracula = catalog.books.first()
        assertThat(dracula.author).isEqualTo("Bram Stoker")
        assertThat(dracula.published).isEqualTo(1897)
        assertThat(dracula.collection).isEqualTo("granger-classics")
        assertThat(catalog.books.last().author).isEmpty()
        assertThat(catalog.books.last().published).isNull()
        assertThat(catalog.items.map { it.id }).containsExactly("med", "gc-dracula", "gc-emma")
        assertThat(catalog.item("gc-emma")).isSameInstanceAs(catalog.books.last())
    }

    @Test fun bookWithBadDateIsDropped() {
        val catalog = ok(parse(catalogWithBooksJson(secondBookDate = "../../x")))
        assertThat(catalog.books.map { it.id }).containsExactly("gc-dracula")
    }

    @Test fun presetKeepsBookIds() {
        val catalog = ok(parse(catalogWithBooksJson()))
        assertThat(catalog.presets.single().items).containsExactly("gc-dracula", "gc-emma", "med").inOrder()
    }

    @Test fun catalogWithoutBooksStillParses() {
        val catalog = ok(parse(catalogJson()))
        assertThat(catalog.books).isEmpty()
        assertThat(catalog.items).hasSize(3)
    }
}
