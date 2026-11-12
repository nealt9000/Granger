package app.granger.core.text

/**
 * British and American spellings of one word ("grey" and "gray", "colour" and "color", "anaemia" and "anemia"), so a
 * search in one finds text in the other. The Classics mix British and American editions ("the dapple-gray horse" in The
 * Clouds missed "dapple-grey"), and WikiMed writes American spellings a British reader won't type. A curated list, not
 * suffix rules: "-ise" alone would turn "promise" and "otherwise" into words that don't exist.
 */
object Spellings {
    /**
     * [word] (as given) first, then its other spelling, if it has one; case aside. A compound or a token with
     * punctuation has each of its words swapped ("dapple-grey" → "dapple-gray").
     */
    fun variants(word: String): List<String> {
        PAIRS[word.lowercase()]?.let { return listOf(word, it) }
        val other = LETTERS.replace(word) { m -> PAIRS[m.value.lowercase()] ?: m.value }
        return if (other == word) listOf(word) else listOf(word, other)
    }

    /** Whether any of [words] (or a word inside one) has another spelling. */
    fun any(words: List<String>): Boolean = words.any { variants(it).size > 1 }

    private val LETTERS = Regex("\\p{L}+")

    /**
     * [words] in each of their spellings, the words as given first: every combination, at most [max]. A query with no
     * word that has another spelling gives just itself.
     */
    fun forms(words: List<String>, max: Int = 4): List<List<String>> {
        var out = listOf(emptyList<String>())
        for (word in words) {
            val vs = variants(word)
            out = out.flatMap { prefix -> vs.map { prefix + it } }.take(max)
        }
        return out
    }

    /** [text] in each of its spellings ([forms] over its words, spaces between), as given first. */
    fun forms(text: String, max: Int = 4): List<String> {
        val words = text.trim().split(SPACES).filter { it.isNotEmpty() }
        if (!any(words)) return listOf(text)
        return forms(words, max).map { it.joinToString(" ") }
    }

    private val SPACES = Regex("\\s+")

    private val PAIRS: Map<String, String> = buildMap {
        fun pair(uk: String, us: String) {
            check(get(uk).let { it == null || it == us } && get(us).let { it == null || it == uk }) { "spelling pair clash: $uk $us" }
            put(uk, us)
            put(us, uk)
        }
        // -our / -or, with the endings these words take ("favourite", "honourable", "labourer", "armoury").
        val our = "arbour ardour armour behaviour candour clamour colour demeanour endeavour favour fervour flavour " +
            "harbour honour humour labour neighbour odour parlour rancour rigour rumour saviour savour splendour succour " +
            "tumour valour vapour vigour"
        for (uk in our.split(' ')) {
            val us = uk.removeSuffix("our") + "or"
            for (end in listOf("", "s", "ed", "ing", "ful", "fully", "less", "able", "ably", "ite", "ites", "er", "ers", "y", "hood", "ly", "ous")) {
                pair(uk + end, us + end)
            }
        }
        // -ise / -ize, for the verbs that take either.
        val ize = "real recogn organ apolog civil critic sympath author bapt character colon emphas memor patron scrutin " +
            "symbol tyrann agon idol moral harmon special util ideal natural familiar fertil local minim modern normal " +
            "neutral optim popular rational subsid summar standard visual legal equal central categor jeopard mesmer " +
            "monopol ostrac polar pulver revolution satir stabil steril terror theor trivial capital immortal human " +
            "material spiritual fantas"
        for (stem in ize.split(' ')) {
            for ((uk, us) in listOf("ise" to "ize", "ised" to "ized", "ises" to "izes", "ising" to "izing",
                "isation" to "ization", "isations" to "izations", "iser" to "izer", "isers" to "izers")) {
                pair(stem + uk, stem + us)
            }
        }
        for (stem in listOf("analy", "paraly", "cataly")) {
            for ((uk, us) in listOf("se" to "ze", "sed" to "zed", "ses" to "zes", "sing" to "zing")) pair(stem + uk, stem + us)
        }
        // A doubled l before an ending ("travelled", "marvellous").
        val ll = "travel cancel label level model quarrel marvel counsel jewel fuel duel signal channel funnel tunnel " +
            "shovel revel rival total pedal libel grovel snivel swivel unravel dishevel bevel enamel gravel"
        for (stem in ll.split(' ')) {
            for (end in listOf("ed", "ing", "er", "ers", "ous")) pair(stem + "l" + end, stem + end)
        }
        // Each pair as written.
        val words = """
            grey gray|greys grays|greyish grayish|greyness grayness|greyed grayed|greying graying
            centre center|centres centers|centred centered|theatre theater|theatres theaters|metre meter|metres meters
            litre liter|litres liters|fibre fiber|fibres fibers|sombre somber|spectre specter|spectres specters
            sabre saber|sabres sabers|meagre meager|lustre luster|calibre caliber|sepulchre sepulcher|sepulchres sepulchers
            mitre miter|nitre niter|ochre ocher|manoeuvre maneuver|manoeuvres maneuvers|manoeuvred maneuvered
            anaemia anemia|anaemic anemic|anaesthesia anesthesia|anaesthetic anesthetic|anaesthetics anesthetics
            haemorrhage hemorrhage|haemorrhages hemorrhages|haemoglobin hemoglobin|haematoma hematoma
            haemophilia hemophilia|haemorrhoids hemorrhoids|leukaemia leukemia|oedema edema|oesophagus esophagus
            oestrogen estrogen|diarrhoea diarrhea|foetus fetus|foetal fetal|paediatric pediatric|paediatrics pediatrics
            paediatrician pediatrician|gynaecology gynecology|orthopaedic orthopedic|encyclopaedia encyclopedia
            mediaeval medieval|caesarean cesarean|septicaemia septicemia|ischaemia ischemia|coeliac celiac
            dyspnoea dyspnea|apnoea apnea
            defence defense|defences defenses|offence offense|offences offenses|pretence pretense|pretences pretenses
            licence license|plough plow|ploughs plows|ploughed plowed|ploughing plowing|ploughman plowman
            ploughshare plowshare|mould mold|moulds molds|mouldy moldy|moulded molded|smoulder smolder
            smouldering smoldering|smouldered smoldered|moult molt|moustache mustache|moustaches mustaches
            sceptic skeptic|sceptics skeptics|sceptical skeptical|scepticism skepticism|gaol jail|gaols jails
            gaoler jailer|pyjamas pajamas|aluminium aluminum|sulphur sulfur|sulphuric sulfuric|sulphurous sulfurous
            ageing aging|judgement judgment|judgements judgments|acknowledgement acknowledgment|connexion connection
            waggon wagon|waggons wagons|catalogue catalog|catalogues catalogs|programme program|programmes programs
            jewellery jewelry|woollen woolen|counsellor counselor|counsellors counselors|councillor councilor
            councillors councilors|enrolment enrollment|fulfil fulfill|fulfils fulfills|fulfilment fulfillment
            skilful skillful|skilfully skillfully|wilful willful|wilfully willfully|instalment installment
            instalments installments|whisky whiskey|practise practice|practised practiced|practising practicing
            gipsy gypsy|gipsies gypsies
        """.trimIndent()
        for (entry in words.split('|', '\n')) {
            val (uk, us) = entry.trim().split(' ').also { require(it.size == 2) { "bad spelling pair: $entry" } }
            pair(uk, us)
        }
    }
}
