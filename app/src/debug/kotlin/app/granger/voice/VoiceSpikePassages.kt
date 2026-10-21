package app.granger.voice

/** One of the spike's fixed passages. [id] names it in log lines and file names. */
data class Passage(val id: String, val title: String, val text: String)

/**
 * The three passages every engine reads (Plan V0, Task 3): an encyclopedia paragraph with a date, a place and a
 * parenthesis; verse with archaic words; and a Research-style answer with numbers, a name and a unit.
 */
object VoiceSpikePassages {
    /** The lead of Wikipedia's "Rosetta Stone" article (CC BY-SA 4.0, https://en.wikipedia.org/wiki/Rosetta_Stone). */
    val ARTICLE = Passage(
        "article", "Rosetta Stone (article)",
        """
        The Rosetta Stone is a stele of granodiorite inscribed with three versions of a decree issued in Memphis, Egypt,
        in 196 BC during the Ptolemaic dynasty on behalf of King Ptolemy V Epiphanes. The top and middle texts are in
        Ancient Egyptian using hieroglyphic and Demotic scripts respectively, while the bottom is in Ancient Greek. The
        decree has only minor differences between the three versions, making the Rosetta Stone key to deciphering the
        Egyptian scripts. The stone was carved during the Hellenistic period and is believed to have originally been
        displayed within a temple, possibly at Sais. It was probably moved in late antiquity or during the Mameluk
        period, and was eventually used as building material in the construction of Fort Julien near the town of
        Rashid (Rosetta) in the Nile Delta. It was found there in July 1799 by French officer Pierre-François Bouchard
        during the Napoleonic campaign in Egypt.
        """.trimIndent(),
    )

    /** Portia, The Merchant of Venice, Act IV, Scene 1 (public domain). */
    val BOOK = Passage(
        "book", "The quality of mercy (verse)",
        """
        The quality of mercy is not strained. It droppeth as the gentle rain from heaven upon the place beneath. It is
        twice blest: it blesseth him that gives and him that takes. 'Tis mightiest in the mightiest; it becomes the
        throned monarch better than his crown. His sceptre shows the force of temporal power, the attribute to awe and
        majesty, wherein doth sit the dread and fear of kings; but mercy is above this sceptred sway. It is enthroned in
        the hearts of kings; it is an attribute to God himself, and earthly power doth then show likest God's when mercy
        seasons justice. Therefore, Jew, though justice be thy plea, consider this: that in the course of justice none
        of us should see salvation. We do pray for mercy, and that same prayer doth teach us all to render the deeds of
        mercy. I have spoke thus much to mitigate the justice of thy plea, which if thou follow, this strict court of
        Venice must needs give sentence 'gainst the merchant there.
        """.trimIndent(),
    )

    /** Written for the spike in the shape of a Research answer. */
    val ANSWER = Passage(
        "answer", "The Planets (answer)",
        """
        Holst began The Planets in 1914 and finished the seven movements in 1917, with the first complete public
        performance in 1920. Mars, the Bringer of War, is written in 5/4 time and runs about 7 minutes in most
        recordings. Your library holds the 1926 recording conducted by Holst himself with the London Symphony
        Orchestra, about 42 minutes long. The score calls for a large orchestra, including an organ and a wordless
        women's chorus of about 30 singers in Neptune.
        """.trimIndent(),
    )

    val ALL = listOf(ARTICLE, BOOK, ANSWER)
}
