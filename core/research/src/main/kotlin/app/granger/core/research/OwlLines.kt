package app.granger.core.research

import kotlin.random.Random

/**
 * What the owl says the moment a question is sent, by topic, and when the answer isn't in the library: Neal's lines
 * from docs/ask/owl-lines-draft.md (sections A to D), generated from it. MEDICAL lines are plain, never a joke. A topic guess can be wrong, so every line hints rather than
 * announces, and none claims to know the answer.
 */
object OwlLines {
    val LINES: Map<ReactionTopic, List<String>> = mapOf(
        ReactionTopic.DEFAULT to listOf(
            "Hm. A fair question. Let me look.",
            "Right. Off to the shelves.",
            "Give me a moment. The good books are never on the low shelf.",
            "Let me see what your library has to say.",
            "One moment. Where did I put my spectacles…",
            "Ah. Now that wants looking up.",
            "Let me ask the books. They know more than I do.",
            "A moment, please. I'm climbing the ladder.",
            "Very well. Let's see what's been written.",
            "Hm. I have a hunch, but hunches aren't sources.",
            "Let me dust off the right volume.",
            "Questions are free. Answers take a moment.",
            "Patience. The index is longer than it looks.",
            "Now, which shelf was that on…",
            "Let me check before I say anything foolish.",
            "Splendid. Something to look up.",
            "Hold on to your hat. I'm off to the stacks.",
            "Let me have a rummage.",
        ),
        ReactionTopic.HISTORY to listOf(
            "History, is it? Mind the dust.",
            "The past keeps poor records. Let me check them anyway.",
            "Ah, people long gone. They do leave notes.",
            "Let me consult the chronicles.",
            "Let me find who wrote it down at the time.",
            "Dates, names, battles. Let me sort them out.",
            "Ah, the old days. Not all of them were good.",
            "Let me find a witness, or at least a historian.",
            "History repeats itself. Let me find the first time.",
            "Kings and calamities. Let me check the record.",
            "One moment. I'm winding back the centuries.",
            "Let me find out who was there.",
        ),
        ReactionTopic.SCIENCE to listOf(
            "Science! Let me find the proper workings.",
            "Nature has her reasons. Let's find them.",
            "Hm. That wants evidence, not opinion. Looking.",
            "Let me find someone who measured it.",
            "Let me find the experiment, not the rumour.",
            "Ah, the workings of things. Lovely.",
            "Let me find the natural philosophers on this.",
            "Theories come and go. Let me find the current one.",
            "Hm. Let me find what's been observed.",
            "Atoms, stars or beetles? Let me look.",
            "Let me find the careful people who checked.",
            "Curious. Let me find the mechanism.",
        ),
        ReactionTopic.LITERATURE to listOf(
            "Ah, the old books. My favourite shelf.",
            "Let me find the very page.",
            "The authors are long dead, but they're still talking.",
            "Better from the source than from me. Fetching it.",
            "Ink and paper! Let me find the passage.",
            "Let me pull the volume and find the line.",
            "A good story deserves a proper look.",
            "Let me see what the author actually wrote.",
            "Ah. Let me find the chapter.",
            "Let me ask the old masters. They're patient.",
            "One moment. These books are heavy.",
            "Words worth rereading. Let me find them.",
        ),
        ReactionTopic.SCRIPTURE to listOf(
            "Let me find the passage itself.",
            "Chapter and verse, then. One moment.",
            "Let me find what the text actually says.",
            "Let me open the old texts.",
            "Let me find it in the words themselves.",
            "Let me find the verse and its neighbours.",
            "Sacred texts deserve a careful look.",
            "Let me consult the scriptures in your library.",
            "Let me find the passage, not someone's opinion of it.",
        ),
        ReactionTopic.WORDS to listOf(
            "Words! My favourite small things.",
            "Let me find where that word came from.",
            "Every word has a history. Let me look it up.",
            "Let me find its roots. Words have them.",
            "Let me open the dictionary. Gently.",
            "Spelling, meaning or origin? Let me look at all three.",
            "Ah, a word. Let me find what it's been up to.",
            "Let me consult the lexicographers.",
            "Words wander. Let me find where this one's been.",
        ),
        ReactionTopic.PLACES to listOf(
            "Let me find a map, or at least a good description.",
            "A place! Let me see who's been.",
            "Let me look up where in the world that is.",
            "Let me find it on the old maps.",
            "Ah, somewhere else. Let me look.",
            "Let me find who lives there and why.",
            "Let me find the lie of the land.",
            "Let me find it among the atlases.",
            "Far or near, let me find it.",
        ),
        ReactionTopic.MATHS to listOf(
            "Numbers. They never lie, but people misread them.",
            "Let me find the figures and check them.",
            "Sums! Let me find the parts first.",
            "Let me find the numbers before I do any sums.",
            "Arithmetic! Let me get the facts straight first.",
            "Let me find the figures. I'll check my working.",
            "Hm. Let me count carefully.",
            "Let me find the quantities, then the reckoning.",
            "Mathematics is honest. Let me find the inputs.",
        ),
        ReactionTopic.HOW_TO to listOf(
            "A practical matter. Let me find the method.",
            "Let me find how it's properly done.",
            "Instructions! Let me find a reliable set.",
            "Let me find instructions from someone who's done it.",
            "Doing beats wondering. Let me find the steps.",
            "Let me find the proper way, not the quick way.",
            "Let me find a sound method.",
            "A job to do! Let me find the steps.",
            "Let me find advice I'd trust.",
        ),
        ReactionTopic.MEDICAL to listOf(
            "Let me look through your medical references.",
            "Searching your library's medical sources.",
            "Checking your library's health references.",
            "Looking through your medical books.",
            "Let me find what your references say.",
            "Searching your library's health sources.",
        ),
        ReactionTopic.FOLLOW_UP to listOf(
            "Carrying on, then.",
            "Ah, there's more. Good.",
            "Picking up where we left off.",
            "And on we go.",
            "Another one! Good.",
            "Following the thread.",
            "Same shelf, or a new one? Let me see.",
            "Ah, the plot thickens.",
            "Right. Further in.",
        ),
    )

    /** A line for [topic] not in [used] (the chat's lines so far); once every line is used, any line. */
    fun pick(topic: ReactionTopic, used: Set<String>, random: Random = Random.Default): String {
        val lines = LINES.getValue(topic)
        return lines.filter { it !in used }.ifEmpty { lines }.random(random)
    }

    /**
     * The owl's clean declines (the doc's section D), for a non-medical turn whose answer isn't in the library. Each
     * says the answer isn't there, not that the topic isn't: the library may hold nearby books.
     */
    val DECLINES: List<String> = listOf(
        "I've turned out every shelf, and it isn't here. Most vexing.",
        "Your library is admirably stocked, but not with that.",
        "Not a word of it in your books, and I looked properly.",
        "The shelves are silent on this one, and they're rarely silent.",
        "Nothing. I checked twice, which is once more than I usually allow.",
        "Your books talk around it, but never quite about it.",
        "A gap in the collection. Libraries have them; so do owls.",
        "I found the neighbourhood, but not the house.",
    )

    /** The label over a best guess (section D): the guess is from memory, unchecked by any book. */
    val GUESS_LABELS: List<String> = listOf(
        "Off the record, then: my best guess, unchecked by any book.",
        "From memory, which is old and not to be trusted: my best guess.",
        "No book backs me up here, so weigh this lightly.",
    )

    /** [turnId]'s decline line: the same one every time the turn is shown. */
    fun decline(turnId: Long): String = DECLINES[Math.floorMod(turnId, DECLINES.size)]

    /** [turnId]'s best-guess label, steady as [decline]. */
    fun guessLabel(turnId: Long): String = GUESS_LABELS[Math.floorMod(turnId, GUESS_LABELS.size)]

    /** Where the owl is looking (section B), once the search by meaning is back; `{titles}` is the titles joined. */
    val LOOKING_IN: List<String> = listOf(
        "Looking in {titles}…",
        "I've pulled down {titles}…",
        "Leafing through {titles}…",
        "Now then: {titles}…",
    )

    /** Section B's plain form, for a health question. */
    const val LOOKING_IN_MEDICAL = "Checking {titles}…"

    /** A section B line naming [titles], plain when [medical]. */
    fun lookingIn(titles: List<String>, medical: Boolean, random: Random = Random.Default): String =
        (if (medical) LOOKING_IN_MEDICAL else LOOKING_IN.random(random)).replace("{titles}", titles.joinToString(" · "))

    /** Section C, just before the answer streams: the passages being read, as `(plural, singular)` forms of each line. */
    val READING: List<Pair<String, String>> = listOf(
        "Reading {n} passages…" to "Reading 1 passage…",
        "Reading the likeliest {n}…" to "Reading the likeliest one…",
        "{n} passages look promising. Reading…" to "1 passage looks promising. Reading…",
    )

    /** A section C line for [passages] passages. */
    fun reading(passages: Int, random: Random = Random.Default): String {
        val (plural, singular) = READING.random(random)
        return if (passages == 1) singular else plural.replace("{n}", passages.toString())
    }

    /**
     * While the model loads, in turn every few seconds (Neal, 2026-11-02): the owl getting ready, never the question, so
     * they suit a health question too.
     */
    val WAKING: List<String> = listOf(
        "Waking the librarian…",
        "Fetching my spectacles…",
        "Lighting the reading lamp…",
        "Shaking the dust off my feathers…",
        "Climbing down from the high shelf…",
        "Finding my place in the catalogue…",
        "Sharpening a quill…",
        "Stirring the old owl…",
        "Pulling on my reading gloves…",
        "Opening the library doors…",
        "Shuffling the index cards…",
        "Turning up the lamp…",
    )
}
