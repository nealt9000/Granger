package app.granger.core.library

/**
 * One reference link in a Classic: [quote] in the chapter [href], after the words [before] (both as tools/links/build.py
 * cut them from the chapter's text), to the Wikidata item [qid]. [tier] 1 shows at Light, 2 only at More.
 */
data class BookMention(val href: String, val start: Int, val quote: String, val before: String, val qid: Long, val tier: Int)
