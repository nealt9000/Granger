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

    /** Plan KB: which packs are books, and each pack's flavour. */
    @Test fun contentAndFlavourDefaultWhenAbsent() {
        val pack = ok(parse(catalogJson())).packs.single()
        assertThat(pack.content).isEmpty()
        assertThat(pack.flavour).isEmpty()
        assertThat(pack.isBookPack).isFalse()
    }

    @Test fun contentAndFlavourAreRead() {
        val books = ok(parse(catalogJson(packExtra = ""","content": "books", "flavour": """""))).packs.single()
        assertThat(books.isBookPack).isTrue()
        val maxi = ok(parse(catalogJson(packExtra = ""","content": "", "flavour": "maxi""""))).packs.single()
        assertThat(maxi.isBookPack).isFalse()
        assertThat(maxi.flavour).isEqualTo("maxi")
    }

    /** Plan WF: Wikipedia's slices and subjects. */
    @Test fun setFieldsDefaultWhenAbsent() {
        val pack = ok(parse(catalogJson())).packs.single()
        assertThat(pack.setId).isEmpty()
        assertThat(pack.setRank).isEqualTo(0)
        assertThat(pack.isSlice).isFalse()
        assertThat(pack.isSubject).isFalse()
        assertThat(pack.adds).isEmpty()
        assertThat(pack.articles).isEqualTo(0L)
        assertThat(pack.sample).isFalse()
    }

    @Test fun aSliceAndASubjectAreRead() {
        val slice = ok(parse(catalogJson(packExtra = ""","set": "wikipedia_en", "setRank": 2, "setLabel": "Best 50,000 articles", "articles": 50002"""))).packs.single()
        assertThat(slice.isSlice).isTrue()
        assertThat(slice.setId).isEqualTo("wikipedia_en")
        assertThat(slice.setRank).isEqualTo(2)
        assertThat(slice.setLabel).isEqualTo("Best 50,000 articles")
        assertThat(slice.articles).isEqualTo(50002L)
        val subject = ok(parse(catalogJson(packExtra = ""","subjectOf": "wikipedia_en", "articles": 72901, "adds": {"wikipedia_en_top": 0.955, "wikipedia_en_top1m": 0.656}, "sample": true"""))).packs.single()
        assertThat(subject.isSubject).isTrue()
        assertThat(subject.adds).containsExactly("wikipedia_en_top", 0.955, "wikipedia_en_top1m", 0.656)
        assertThat(subject.sample).isTrue()
    }

    @Test fun inconsistentSetFieldsAreCleared() {
        val noRank = ok(parse(catalogJson(packExtra = ""","set": "wikipedia_en", "setLabel": "Everything""""))).packs.single()
        assertThat(noRank.isSlice).isFalse()
        assertThat(noRank.setLabel).isEmpty()
        val both = ok(parse(catalogJson(packExtra = ""","set": "wikipedia_en", "setRank": 1, "subjectOf": "wikipedia_en", "adds": {"x": 0.5}"""))).packs.single()
        assertThat(both.isSlice).isTrue()
        assertThat(both.isSubject).isFalse()
        assertThat(both.adds).isEmpty()
        val share = ok(parse(catalogJson(packExtra = ""","subjectOf": "wikipedia_en", "adds": {"a": 1.5, "b": -0.1, "c": 0.25}"""))).packs.single()
        assertThat(share.adds).containsExactly("c", 0.25)
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
        {"id": "medicine", "title": "Medicine & first aid", "essential": ["med"], "comprehensive": ["med", "medline", "@lost"]},
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

    // --- Plan R1: search vectors ---

    private fun vectors(url: String = "https://github.com/x/releases/download/vectors-1/med.gvec", sha: String = SHA_B, size: String = "4096") =
        """{"url": "$url", "sha256": "$sha", "sizeBytes": $size, "embedderSha256": "$SHA_A", "dims": 256, "dtype": "int8", "chunkerVersion": 1}"""

    private fun vectorsCatalog(packVectors: String?, bookVectors: String?) = """
        {"formatVersion": 1, "generatedAt": "2026-10-23T00:00:00Z",
         "packs": [${pack("med", "wikipedia", "medicine").withVectors(packVectors)}],
         "books": [${book("gc-hamlet", "literature").withVectors(bookVectors)}]}
    """.trimIndent().toByteArray()

    private fun String.withVectors(v: String?) = if (v == null) this else dropLast(1) + ", \"vectors\": $v}"

    @Test fun packsAndBooksCarryTheirVectorsAndTheirSizeCountsThem() {
        val catalog = ok(parse(vectorsCatalog(vectors(), vectors(sha = SHA_A, size = "100"))))
        val med = catalog.packs.single()
        assertThat(med.vectors!!.sha256).isEqualTo(SHA_B)
        assertThat(med.vectors!!.dims).isEqualTo(256)
        assertThat(med.downloadBytes).isEqualTo(1000 + 4096)
        assertThat((catalog.books.single() as CatalogItem).vectors!!.sizeBytes).isEqualTo(100)
    }

    @Test fun entriesWithoutVectorsAndOlderCatalogsHaveNone() {
        val catalog = ok(parse(vectorsCatalog(null, null)))
        assertThat(catalog.packs.single().vectors).isNull()
        assertThat(catalog.books.single().downloadBytes).isEqualTo(500)
        assertThat(ok(parse(catalogWithBooksJson())).items.mapNotNull { it.vectors }).isEmpty()
    }

    @Test fun badVectorsAreDroppedButTheirEntryIsKept() {
        val plainHttp = ok(parse(vectorsCatalog(vectors(url = "http://example.com/med.gvec"), vectors(sha = "nope"))))
        assertThat(plainHttp.packs.single().vectors).isNull()
        assertThat(plainHttp.books.single().vectors).isNull()
        // A field of the wrong type fails the whole object's decoding; the entry is read again without it.
        val wrongType = ok(parse(vectorsCatalog(vectors(size = "\"big\""), "[]")))
        assertThat(wrongType.packs.map { it.id }).containsExactly("med")
        assertThat(wrongType.books.map { it.id }).containsExactly("gc-hamlet")
        assertThat(wrongType.packs.single().vectors).isNull()
    }

    // --- Plan L3: languages ---

    private fun esPack(id: String, domain: String) = pack(id, "wikipedia", domain).replace("\"language\": \"eng\"", "\"language\": \"spa\"")

    private fun spanishCatalog(domains: String, useCases: String = "", presets: String = "") = """
        {"formatVersion": 1, "generatedAt": "2026-10-30T00:00:00Z",
         "packs": [${pack("med", "wikipedia", "medicine")}, ${esPack("wikipedia_es_medicine_maxi", "medicine")},
                   ${pack("so", "stack_exchange", "technology").replace("\"language\": \"eng\"", "\"language\": \"eng,spa\"")}],
         "books": [${book("gc-bible", "thought")}],
         "presets": [$presets],
         "readiness": {"domains": [$domains]}${if (useCases.isEmpty()) "" else ", \"onboarding\": {\"useCases\": [$useCases]}"}}
    """.trimIndent().toByteArray()

    private val spanishDomains = """
        {"id": "medicine", "title": "Medicine & first aid", "essential": ["med"], "comprehensive": [],
         "i18n": {"spa": {"title": "Medicina y primeros auxilios"}},
         "languages": {"es": {"essential": ["wikipedia_es_medicine_maxi", "wikipedia_es_atlantis"], "comprehensive": ["so"]}}},
        {"id": "thought", "title": "Thought", "essential": ["@granger-classics"], "comprehensive": [],
         "languages": {"es": {"essential": ["nope"], "comprehensive": []}, "en": {"essential": ["med"]}}}
    """

    @Test fun anOldCatalogParsesToTheSameEnglishModel() {
        val old = ok(parse(readinessCatalog(domains)))
        assertThat(old.packs.map { it.language }).containsExactly("en", "en").inOrder()
        assertThat(old.books.map { it.language }).containsExactly("en", "en")
        val medicine = old.readiness!!.domains.first()
        assertThat(medicine.i18n).isEmpty()
        assertThat(medicine.languages).isEmpty()
        assertThat(medicine.essential("en")).isEqualTo(medicine.essential)
        assertThat(medicine.comprehensive("eng")).isEqualTo(medicine.comprehensive)
        assertThat(medicine.essential("es")).isEmpty()
        assertThat(old.languages).containsExactly("en")
        val preset = ok(parse(catalogJson())).presets.single()
        assertThat(preset.language).isEqualTo("en")
        assertThat(preset.i18n).isEmpty()
        assertThat(preset.title("es")).isEqualTo("Starter")
        val pack = ok(parse(catalogJson())).packs.single()
        assertThat(pack.language).isEqualTo("en")
        assertThat(pack.i18n).isEmpty()
        assertThat(pack.setTitle).isEmpty()
    }

    @Test fun aSpanishDomainDropsOnlyItsUnknownTokens() {
        val catalog = ok(parse(spanishCatalog(spanishDomains)))
        val medicine = catalog.readiness!!.domains.single { it.id == "medicine" }
        assertThat(medicine.essential("es")).containsExactly("wikipedia_es_medicine_maxi")
        assertThat(medicine.comprehensive("es")).containsExactly("wikipedia_es_medicine_maxi", "so").inOrder()
        assertThat(medicine.essential).containsExactly("med")
        // A language whose lists are empty after checking is dropped, and English never comes from `languages`.
        assertThat(catalog.readiness!!.domains.single { it.id == "thought" }.languages).isEmpty()
        assertThat(catalog.languages).containsExactly("en", "es").inOrder()
    }

    @Test fun packAndBookLanguagesAreNormalised() {
        val catalog = ok(parse(spanishCatalog(spanishDomains)))
        assertThat(catalog.packs.map { it.language }).containsExactly("en", "es", "en,es").inOrder()
        assertThat(Languages.has(catalog.packs.last().language, "es")).isTrue()
    }

    @Test fun i18nFallsBackToEnglish() {
        val catalog = ok(parse(spanishCatalog(spanishDomains)))
        val medicine = catalog.readiness!!.domains.single { it.id == "medicine" }
        // The code is normalised ("spa" → "es").
        assertThat(medicine.title("es")).isEqualTo("Medicina y primeros auxilios")
        assertThat(medicine.title("es-MX")).isEqualTo("Medicina y primeros auxilios")
        assertThat(medicine.title("en")).isEqualTo("Medicine & first aid")
        assertThat(medicine.title("fr")).isEqualTo("Medicine & first aid")
        assertThat(catalog.readiness!!.domains.single { it.id == "thought" }.title("es")).isEqualTo("Thought")
    }

    @Test fun tierPresetsArePickedByLanguage() {
        val presets = """
            {"id": "efficient", "title": "Efficient start", "items": ["med"], "i18n": {"es": {"title": "Comienzo eficiente"}}},
            {"id": "comprehensive", "title": "Comprehensive library", "items": ["med", "so"]},
            {"id": "efficient-es", "title": "Efficient start", "items": ["wikipedia_es_medicine_maxi"], "language": "es"},
            {"id": "comprehensive-es", "title": "Comprehensive library", "items": ["wikipedia_es_medicine_maxi", "so"], "language": "spa"},
            {"id": "efficient-fr", "title": "Efficient start", "items": ["med"]}
        """
        val catalog = ok(parse(spanishCatalog(spanishDomains, presets = presets)))
        assertThat(catalog.tierPresets("en").map { it.id }).containsExactly("efficient", "comprehensive").inOrder()
        assertThat(catalog.tierPresets("es").map { it.id }).containsExactly("efficient-es", "comprehensive-es").inOrder()
        assertThat(catalog.tierPresets("es")[1].language).isEqualTo("es")
        // A preset whose language doesn't match its id isn't a tier preset.
        assertThat(catalog.tierPresets("fr")).isEmpty()
        assertThat(catalog.tierPresets("en").first().title("es")).isEqualTo("Comienzo eficiente")
        assertThat(catalog.tierPresets("en")[1].title("es")).isEqualTo("Comprehensive library")
    }

    @Test fun useCasesCarryTheirItemsAndCopyPerLanguage() {
        val useCases = """
            {"id": "medical", "title": "Medical work", "blurb": "Clinical.", "items": ["med"],
             "i18n": {"es": {"title": "Trabajo sanitario"}},
             "languages": {"es": {"items": ["wikipedia_es_medicine_maxi", "nope", "wikipedia_es_medicine_maxi"]}}},
            {"id": "faith", "title": "Faith", "items": ["@granger-classics"], "languages": {"es": {"items": ["nope"]}}}
        """
        val onboarding = ok(parse(spanishCatalog(spanishDomains, useCases))).onboarding!!
        val (medical, faith) = onboarding.useCases
        assertThat(medical.items("es")).containsExactly("wikipedia_es_medicine_maxi")
        assertThat(medical.items("en")).containsExactly("med")
        assertThat(medical.title("es")).isEqualTo("Trabajo sanitario")
        assertThat(medical.blurb("es")).isEqualTo("Clinical.")
        // Nothing Spanish resolves: not offered in Spanish.
        assertThat(faith.languages).isEmpty()
        assertThat(faith.items("es")).isEmpty()
    }

    @Test fun aSliceCarriesItsSetCopy() {
        val extra = ""","set": "wikipedia_es", "setRank": 1, "setLabel": "Todo", "setTitle": "Wikipedia en español",
            "setDescription": "La enciclopedia libre.", "i18n": {"en": {"title": "Wikipedia in Spanish: Everything",
            "setLabel": "Everything", "setTitle": "Wikipedia in Spanish"}}"""
        val pack = ok(parse(catalogJson(packTitle = "Wikipedia en español: Todo", packExtra = extra))).packs.single()
        assertThat(pack.setTitle).isEqualTo("Wikipedia en español")
        assertThat(pack.setDescription).isEqualTo("La enciclopedia libre.")
        // A Spanish pack's own title is Spanish; its English is in i18n.en.
        assertThat(pack.title("en")).isEqualTo("Wikipedia in Spanish: Everything")
        assertThat(pack.title("es")).isEqualTo("Wikipedia en español: Todo")
        assertThat(localised(pack.i18n, "en", pack.setLabel, Copy::setLabel)).isEqualTo("Everything")
        assertThat(localised(pack.i18n, "es", pack.setLabel, Copy::setLabel)).isEqualTo("Todo")
        assertThat(localised(pack.i18n, "en", pack.setDescription, Copy::setDescription)).isEqualTo("La enciclopedia libre.")
    }
}
