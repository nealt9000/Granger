package app.granger.core.research

/**
 * The Spanish medical lexicon (languages spec §6.2), the counterpart of [Medical]. It is safety work: it must catch
 * every medical question of `tools/eval/sets/medical-labels-es.jsonl`, and is scored on the held-out
 * `medical-heldout-es.jsonl`, written without seeing it. [Medical.looksMedical] runs it on every question that
 * isn't clearly English.
 *
 * Matching is on the folded question ([ResearchLanguage.fold]: lower case, no accents), so terms are written without
 * accents and "fiebre en bebes" matches as "fiebre en bebés" does. Terms match at the start of a word; short or
 * ambiguous ones are whole words. [NOT_MEDICAL] phrases are taken out first, so "pastillas de freno" isn't a pill.
 */
object MedicalEs {
    private val TERMS = listOf(
        // Symptoms and signs.
        "sintoma", "dolor de", "fiebre", "calentura", "sangr(?!ia)", "hemorragi", "vomit", "diarrea", "estrenimiento",
        "nausea", "mareo", "desmay", "convuls", "hinch", "inflamaci", "bulto", "flema", "sarpullido", "roncha",
        "comezon", "picazon", "ardor al", "mancha en la piel", "secuela", "palpitaci", "taquicardia", "hormigueo",
        "entumec", "falta de aire", "dificultad para respirar", "no puede respirar", "no puedo respirar",
        // Conditions.
        "enferm", "infecci", "infectad", "embaraz", "amamant", "lactancia", "alergi", "diabet", "insulina",
        "presion alta", "presion baja", "presion arterial", "hipertens", "hipotens", "infarto", "ataque al corazon",
        "ataque cardiaco", "derrame cerebral", "embolia", "gripe", "gripa", "influenza", "resfri", "catarro",
        "calculo renal", "rinon", "higado", "cancer", "tumor", "leucemia", "vacun", "virus", "bacteria", "hepatitis",
        "dengue", "zika", "chikungunya", "chagas", "malaria", "paludismo", "sarampion", "varicela", "paperas",
        "rubeola", "herpes", "culebrilla", "escorbuto", "tuberculosis", "anemi", "migrana", "jaqueca",
        "asma", "tiroides", "acido urico", "preeclampsia", "conjuntivitis", "depresi", "deprimid", "ansiedad",
        "colesterol", "triglicerido", "glucosa", "azucar en la sangre", "apendicitis", "conmoci", "epilep",
        "menstrua", "esguince", "torcedura", "neumonia", "bronquitis", "covid", "coronavirus", "otitis", "amigdal",
        "colico", "empacho", "hongo en", "hongos en", "osteopor", "artritis", "artrosis", "alzheimer", "parkinson",
        "demencia", "autismo", "obesidad", "desnutrici", "deshidrata", "intoxicaci", "envenen", "veneno",
        "sobredosis", "fractura", "quemadura", "picadura", "mordedura", "atragant", "asfixi", "golpe de calor",
        "insolaci", "monoxido de carbono", "pila de boton", "herida", "cortada", "cortadura", "sutura",
        "electrocut", "descarga electrica", "heimlich", "moreton", "hematoma", "chichon", "raspon", "resaca",
        "dando pecho", "dar pecho", "dar de mamar", "leche materna", "pancrea", "pulmon", "estomago", "intestin",
        "vesicula", "prostata", "utero", "ovario", "fibromialgia", "neuralgia", "ulcera", "reflujo", "sida",
        "dentista", "odontolog", "muela", "caries", "hemoglobina", "creatinina", "plaqueta", "leucocito",
        "ferritina", "desparasit", "parasito", "lombrices", "esteriliz", "castrar", "castraci", "seguro medico",
        // Old disease names (historical, still medical).
        "tisis", "tisico", "garrotillo", "hidropesia", "lepra", "leproso", "mal de san lazaro", "peste", "viruela",
        "vomito negro", "fiebre amarilla", "tifus", "tifoidea", "apoplejia", "sanguijuela", "difteria", "tosferina",
        // Animals.
        "moquillo", "parvovirus", "veterinari",
        // Drugs and care.
        "dosis", "medicament", "medicina", "antibiotic", "antiinflamatori", "analgesic", "antidepresiv",
        "ansiolitic", "anticonceptiv", "pildora", "pastilla", "jarabe", "inyecci", "miligramo", "pomada",
        "ibuprofen", "aspirina", "paracetamol", "acetaminofen", "tempra", "tylenol", "advil", "motrin", "amoxicilina",
        "penicilina", "omeprazol", "metformina", "warfarina", "metamizol", "dipirona", "sertralina", "fluoxetina",
        "loratadina", "naproxeno", "diclofenaco", "clonazepam", "alprazolam", "diazepam", "rivotril", "xanax",
        "naloxona", "losartan", "enalapril", "benadryl", "antihistamin", "del dia siguiente", "receta medica",
        "rcp", "reanimaci", "primeros auxilios", "urgencias", "ambulancia", "biopsia", "analisis de sangre",
        "examen de sangre", "pediatra", "ginecolog", "cirugia", "quimioterapia", "dolores de",
    ).map { "\\b" + Regex.escape(it) }
    private val WHOLE_WORDS = listOf(
        "dolor", "duel(e|en)", "doli(o|eron)", "tos(e|es|en|iendo)?", "medic[oa]s?", "doctor(a|es)?",
        "rabia", "colera", "orina(r)?", "encias", "mg", "vih", "ets", "tsh", "\\w+itis",
    ).map { "\\b$it\\b" }
    /** Phrases a question's words make together. */
    private val PHRASES = listOf(
        "\\b(azucar|glucosa) (en|de|a) \\d",
        "\\bpresion (en|de) \\d+( ?/ ?\\d+)?\\b(?! ?(atm|bar|pa|kpa|psi))",
        "\\b(la|de|tengo|ataque de) gota\\b",
        "\\bazucar (esta |muy )?(alta|baja)\\b",
        "\\b(hace|haga|hizo|hecho|hacer) (\\w+ )?(popo|caca|pipi|del bano)\\b",
        "\\b(me |le )?(bajo|baja|vino|llego) (dos veces )?la regla\\b",
        "\\btengo (una )?cruda\\b|\\bcruda (horrible|terrible|fuerte|tremenda)\\b|\\b(quitar|para) la cruda\\b",
        "\\b(trago|tomo|bebio|comio) (\\w+ )?(cloro|lejia|blanqueador|raticida|insecticida|detergente|gasolina|thinner|soda caustica)\\b",
        "\\bhuesos? (esta |estan )?rot[oa]s?\\b",
        "\\blate (muy )?(rapido|fuerte|lento|raro)\\b",
        "\\bse me (duerme|duermen|hincha|hinchan|entume|entumen|cae el pelo)\\b",
        "\\b(me|se|le) torc(i|io)\\b",
        "\\b(me|le|lo|la|te) pic(o|aron) (un|una)\\b",
        "\\b(me|le|lo|la|te) mordi(o|eron)\\b|\\bmuerde (un|una)\\b",
        "\\bse (trago|tomo|bebio) (un|una|el|la|todo|toda|\\d)",
        "\\b(baje|bajo|perdi|perdio) \\d+ (kilos|kg|libras)\\b",
        "\\bregla (muy )?(irregular|atrasad|abundante|doloros)",
        "\\bse me atraso la regla\\b",
        "\\b(llevo|estoy|me siento|ando) (\\w+ ){0,2}(triste|deprimid|ansios|angustiad)",
        // An animal's health: "mi perro se comió…", "mi gato toma mucha agua".
        "\\bmi (perr|gat|caball|conej|loro|perico|hamster|vaca|gallin)\\w* (se comio|comio|se trago|tiene|toma|vomit|no come|no coma|no quiere|cojea|esta)",
    )
    /** Medical-looking words in another sense, taken out before matching. */
    private val NOT_MEDICAL = Regex(
        listOf(
            "pastillas? de freno", "jarabe (tapatio|de maple|de arce|de maiz|de agave)", "virus informatico",
            "fiebre de(l)? (oro|caucho|plata|sabado)", "tropico de cancer", "signo (de )?cancer", "doctor honoris causa", "gran depresion",
            "depresion tropical", "golpe de estado", "gota fria", "pastillas? de jabon", "jarabe de goma",
            "inyeccion de (liquidez|capital|dinero|recursos)", "pulmon del planeta",
        ).joinToString("|") { "\\b$it" },
    )
    private val PATTERN = Regex((TERMS + WHOLE_WORDS + PHRASES).joinToString("|"))

    fun looksMedical(text: String): Boolean = PATTERN.containsMatchIn(NOT_MEDICAL.replace(ResearchLanguage.fold(text), " "))
}

/**
 * The Spanish counterpart of [PersonalMedical]: a question asking for advice about the asker's, or one person's, own
 * health, medicines, test results or care. [strong] phrasings decide alone; the generic ones count only with a medical
 * signal for the question. Matched on the folded question, like [MedicalEs].
 */
object PersonalMedicalEs {
    private const val RELATIVE = "bebe|nene|nena|hijo|hija|hijito|hijita|nino|nina|ninos|guagua|esposo|esposa|marido|" +
        "pareja|novio|novia|mama|papa|madre|padre|abuelo|abuela|abuelito|abuelita|hermano|hermana|suegro|suegra|" +
        "tio|tia|viejo|vieja|chamaco|chamaca|amigo|amiga"
    private val STRONG = listOf(
        "\\bmis? (\\w+ )?(medicamentos?|medicinas?|pastillas?|dosis|receta|inhalador|insulina|tratamiento)\\b",
        "\\b(salio|salieron|dio|dieron) (positiv|negativ)",
        "\\b(debo|tengo que|deberia|hay que) (ir|llevarlo|llevarla|llamar|ver)( a| al)? (la |el |un |una |mi )?" +
            "(urgencias|emergencias|guardia|hospital|medico|doctor|doctora|pediatra|dentista|911|clinica|posta|centro de salud|consultorio)\\b",
        "\\b(vuelvo|regreso|voy) (a|al) (la |el )?(urgencias|emergencias|guardia|hospital|medico|doctor|doctora|pediatra|posta|centro de salud|consultorio|clinica)\\b",
        "\\b(le )?(puedo|debo|podria) dar(le)?\\b[^.?!;]*\\ba mi ($RELATIVE)\\b",
        "\\ble doy\\b[^.?!;]*\\b(a )?mi ($RELATIVE)\\b",
        "\\bcuant[oa]s? (\\w+ ){1,2}le (doy|puedo dar|debo dar)\\b",
        "\\bque (dosis|cantidad) de \\w+ (le doy |para |a )(a )?mi\\b",
        "\\bmi ($RELATIVE) se (trago|tomo|bebio|comio)\\b",
        "\\bmi (medico|doctor|doctora|pediatra|ginecologo|ginecologa) (me )?(dijo|dice|receto)\\b",
    ).map(::Regex)
    private val GENERIC = listOf(
        "\\bsigo con\\b|\\bme dieron de alta\\b",
        "\\b(puedo|puede|podria|debo|debe|deberia|se puede|es malo|es bueno|esta bien) (tomar|tomarme|dejar|duplicar|mezclar|combinar|dar|darle|beber)\\b",
        "\\bcuant[oa]s? (\\w+ ){0,2}(puedo|debo|le|me) (tomar|dar|doy|tomo)\\b",
        "\\bmis? (\\w+ ){0,2}(analisis|examen|examenes|resultados?|estudios?|niveles?|valores|radiografia|tomografia|resonancia|biopsia|laboratorios?|ecografia|ultrasonido)\\b",
        "\\b(sera|es) (\\w+ ){0,4}(cancer|grave|normal|peligroso|serio|infectad[oa]|rot[oa]|maligno)\\b",
        "\\bque (probabilidades|posibilidades|riesgo) tengo\\b",
        "\\b(salio|salieron|dio|dieron) (muy )?(alt|baj)",
        "\\btengo (?!que\\b|una pregunta|curiosidad|una duda)\\w+",
        "\\bme (sale|salio|salen|salieron|arde|arden|pica|pican|late|duele|duelen|dio|da|dan|siento|tome|trague|cai|corte|queme|torci|hice|quede)\\b",
        "\\b(estoy|estaba) (embarazada|amamantando|lactando|tomando|enferm|resfriad|maread|deprimid)",
        "\\bmi ($RELATIVE)( de \\d+ \\w+)? (tiene|esta|se|le|no|toma)\\b",
        "\\bmi ($RELATIVE|\\d+ (anos|meses|semanas))\\b[^.?!;]*\\b(fiebre|calentura|temperatura|sarpullido|ronchas|dolor|duele|vomit|respira|tos|tose|sangr|diarrea|urgencias|hospital|cayo|golpe|azucar|presion|hincha)",
        "\\b(me tome|me trague|tome|trague|me bebi) \\d+",
        "\\b(es|son) \\d+ \\w+ (demasiado|mucho)\\b",
        "\\b(debo|deberia|tengo que) preocuparme\\b",
        "\\bes (seguro|peligroso) para mi\\b",
        "\\bdesde hace (\\w+ )?(horas|dias|semanas|meses)\\b",
        "\\bque (hago|le doy|me tomo|tomo)\\b",
        "\\bse me \\w+",
        "\\b(llevo|ando) (\\w+ ){0,2}(dias|semanas|meses|triste|con)\\b",
    ).map(::Regex)

    /** Phrasings that are personal medical questions on their own. */
    fun strong(question: String): Boolean = ResearchLanguage.fold(question).let { q -> STRONG.any { it.containsMatchIn(q) } }

    /** [medicalSignal] is the pipeline's combined medical flag for the question; generic phrasings need it. */
    fun matches(question: String, medicalSignal: Boolean): Boolean {
        val q = ResearchLanguage.fold(question)
        return STRONG.any { it.containsMatchIn(q) } || (medicalSignal && GENERIC.any { it.containsMatchIn(q) })
    }
}
