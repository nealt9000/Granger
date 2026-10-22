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

    // --- Plan H1: domains and the readiness model ---

    private fun pack(id: String, family: String, domain: String) =
        """{"id": "$id", "title": "$id", "category": "Reference", "language": "eng", "sizeBytes": 1000, "sha256": "$SHA_A",
            "urls": ["https://download.kiwix.org/zim/$id.zim"], "snapshotDate": "2026-10-10", "license": "CC BY-SA 4.0",
            "hasFulltextIndex": true, "zimUuid": "u-$id", "zimName": "$id", "family": "$family", "domain": "$domain"}"""

    private fun book(id: String, domain: String) =
        """{"id": "$id", "title": "$id", "category": "Drama", "language": "en", "sizeBytes": 500, "sha256": "$SHA_A",
            "urls": ["https://example.com/$id.epub"], "releaseDate": "2026-10-17", "license": "Public domain (US)",
            "collection": "granger-classics", "domain": "$domain"}"""

    private fun readinessCatalog(domains: String, contains: String = "") = """
        {"formatVersion": 1, "generatedAt": "2026-10-22T00:00:00Z",
         "packs": [${pack("med", "wikipedia", "medicine")}, ${pack("maps_en_france", "maps", "maps")}${if (contains.isEmpty()) "" else wikipedias}],
         "books": [${book("gc-bible", "thought")}, ${book("gc-hamlet", "literature")}],
         "readiness": {"domains": [$domains]${if (contains.isEmpty()) "" else ", \"contains\": $contains"}}}
    """.trimIndent().toByteArray()

    private val domains = """
        {"id": "medicine", "title": "Medicine & first aid", "essential": ["med"], "comprehensive": ["med", "wikem", "@lost"]},
        {"id": "maps", "title": "Maps", "essential": ["family:maps", "family:atlas"], "comprehensive": []},
        {"id": "thought", "title": "Thought", "essential": ["@granger-classics"], "comprehensive": ["med"]},
        {"id": "", "title": "Blank", "essential": [], "comprehensive": []},
        {"id": "maps", "title": "Maps again", "essential": [], "comprehensive": []}
    """

    @Test fun packsAndBooksCarryTheirDomain() {
        val catalog = ok(parse(readinessCatalog(domains)))
        assertThat(catalog.packs.map { it.domain }).containsExactly("medicine", "maps").inOrder()
        assertThat(catalog.books.map { it.domain }).containsExactly("thought", "literature").inOrder()
    }

    @Test fun olderCatalogsHaveBlankDomainsAndNoReadiness() {
        val catalog = ok(parse(catalogWithBooksJson()))
        assertThat(catalog.packs.single().domain).isEmpty()
        assertThat(catalog.books.first().domain).isEmpty()
        assertThat(catalog.readiness).isNull()
    }

    private fun onboardingCatalog(block: String) = """
        {"formatVersion": 1, "generatedAt": "2026-10-22T00:00:00Z",
         "packs": [${pack("med", "wikipedia", "medicine")}, ${pack("maps_en_france", "maps", "maps")}],
         "books": [${book("gc-bible", "thought")}, ${book("gc-hamlet", "literature")}],
         "onboarding": $block}
    """.trimIndent().toByteArray()

    @Test fun onboardingKeepsResolvedUseCasesAndMapAreas() {
        val block = """{"useCases": [
            {"id": "medical", "title": "Medical work", "blurb": "Clinical.", "items": ["med", "nope", "family:maps", "med"]},
            {"id": "faith", "title": "Faith", "items": ["@granger-classics", "@lost"]},
            {"id": "empty", "title": "Nothing", "items": ["nope"]},
            {"id": "", "title": "Blank", "items": ["med"]},
            {"id": "medical", "title": "Again", "items": ["med"]}
        ], "larger": ["maps_en_france", "med", "maps_en_france"], "covered": {"maps_en_france": ["Corsica", " "], "med": ["Nowhere"]}}"""
        val onboarding = ok(parse(onboardingCatalog(block))).onboarding!!
        assertThat(onboarding.useCases.map { it.id }).containsExactly("medical", "faith").inOrder()
        assertThat(onboarding.useCases[0].items).containsExactly("med", "family:maps").inOrder()
        assertThat(onboarding.useCases[0].blurb).isEqualTo("Clinical.")
        assertThat(onboarding.useCases[1].items).containsExactly("@granger-classics")
        assertThat(onboarding.larger).containsExactly("maps_en_france")
        assertThat(onboarding.covered).containsExactly("maps_en_france", listOf("Corsica"))
    }

    @Test fun onboardingWithNothingLeftIsNullAndOlderCatalogsHaveNone() {
        assertThat(ok(parse(onboardingCatalog("""{"useCases": [{"id": "x", "title": "X", "items": ["nope"]}], "larger": ["med"]}"""))).onboarding).isNull()
        assertThat(ok(parse(readinessCatalog(domains))).onboarding).isNull()
    }

    @Test fun readinessKeepsOnlyTokensTheCatalogKnows() {
        val readiness = ok(parse(readinessCatalog(domains))).readiness!!
        val medicine = readiness.domains.single { it.id == "medicine" }
        assertThat(medicine.comprehensive).containsExactly("med")
        val maps = readiness.domains.single { it.id == "maps" }
        assertThat(maps.essential).containsExactly("family:maps")
    }

    @Test fun readinessFoldsEssentialIntoComprehensive() {
        val readiness = ok(parse(readinessCatalog(domains))).readiness!!
        assertThat(readiness.domains.single { it.id == "maps" }.comprehensive).containsExactly("family:maps")
        assertThat(readiness.domains.single { it.id == "thought" }.comprehensive).containsExactly("@granger-classics", "med").inOrder()
    }

    @Test fun readinessDropsDomainsWithBlankIdsOrDuplicates() {
        val readiness = ok(parse(readinessCatalog(domains))).readiness!!
        assertThat(readiness.domains.map { it.id }).containsExactly("medicine", "maps", "thought").inOrder()
        assertThat(readiness.domains.single { it.id == "maps" }.title).isEqualTo("Maps")
    }

    @Test fun readinessWithNothingLeftIsNull() {
        val onlyBlank = """{"id": "", "title": "Blank", "essential": [], "comprehensive": []}"""
        assertThat(ok(parse(readinessCatalog(onlyBlank))).readiness).isNull()
        assertThat(ok(parse(readinessCatalog(""))).readiness).isNull()
    }

    // --- Plan H4: containment ---

    private val wikipedias = listOf("wikipedia_en_top", "wikipedia_en_top1m", "wikipedia_en_all")
        .joinToString("") { ", " + pack(it, "wikipedia", "reference") }

    private val contains = """{"wikipedia_en_all": ["wikipedia_en_*"], "wikipedia_en_top1m": ["wikipedia_en_top", "wikipedia_en_100"],
        "wikipedia_en_atlantis": ["wikipedia_en_top"]}"""

    @Test fun readinessKeepsContainsEntriesWhoseKeyIsAZim() {
        val readiness = ok(parse(readinessCatalog(domains, contains))).readiness!!
        assertThat(readiness.contains.keys).containsExactly("wikipedia_en_all", "wikipedia_en_top1m")
    }

    @Test fun containersNameEveryZimThatHoldsOneAndNeverItself() {
        val readiness = ok(parse(readinessCatalog(domains, contains))).readiness!!
        assertThat(readiness.containers("wikipedia_en_top")).containsExactly("wikipedia_en_all", "wikipedia_en_top1m")
        assertThat(readiness.containers("wikipedia_en_top1m")).containsExactly("wikipedia_en_all")
        assertThat(readiness.containers("wikipedia_en_all")).isEmpty()
        assertThat(readiness.containers("maps_en_france")).isEmpty()
    }

    @Test fun aCatalogWithoutContainsHasNone() {
        val readiness = ok(parse(readinessCatalog(domains, "{}"))).readiness!!
        assertThat(readiness.contains).isEmpty()
        assertThat(readiness.containers("wikipedia_en_top")).isEmpty()
    }
}
