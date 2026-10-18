package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class KeywordsTest {
    @Test fun dropsStopwordsAndShortWords() =
        assertThat(Keywords.extract("What is the boiling point of water at altitude?"))
            .containsExactly("boiling", "point", "water", "altitude").inOrder()

    @Test fun capsAndDedupes() =
        assertThat(Keywords.extract("steam steam engine boiler valve piston gauge governor", max = 6)).hasSize(6)

    @Test fun dedupeKeepsFirstOccurrence() =
        assertThat(Keywords.extract("steam engine steam")).containsExactly("steam", "engine").inOrder()

    @Test fun possessiveIsStripped() {
        assertThat(Keywords.extract("What are Darwin's finches?")).containsExactly("darwin", "finches").inOrder()
    }

    @Test fun curlyApostropheDoesNotLeaveFragments() {
        val k = Keywords.extract("Why don\u2019t birds migrate?")
        assertThat(k).doesNotContain("don")
        assertThat(k).containsExactly("birds", "migrate").inOrder()
        assertThat(Keywords.extract("Darwin\u2019s finches")).containsExactly("darwin", "finches").inOrder()
    }

    @Test fun normalizeHandlesPossessivesPerPhrase() {
        assertThat(Keywords.normalize("Darwin\u2019s Finches")).isEqualTo("darwin finches")
        assertThat(Keywords.normalize(" Darwin's ")).isEqualTo("darwin")
        assertThat(Keywords.normalize("rock 'n' roll")).isEqualTo("rock n roll")
    }

    @Test fun medicalLexicon() {
        assertThat(Medical.looksMedical("What dose of insulin is safe?")).isTrue()
        assertThat(Medical.looksMedical("How do I treat a Burn?")).isTrue()
        assertThat(Medical.looksMedical("How does a steam engine work?")).isFalse()
    }

    @Test fun apostrophesInNamesStayAsInPassageText() {
        assertThat(Keywords.extract("Who was O'Brien?")).containsExactly("o'brien")
        assertThat(Keywords.normalize("O\u2019Brien")).isEqualTo("o'brien")
        assertThat(Keywords.words("o'brien")).containsExactly("o", "brien").inOrder()
    }

    @Test fun newLexiconTermsAreMedical() {
        assertThat(Medical.looksMedical("What are the warning signs someone is having a stroke?")).isTrue()
        assertThat(Medical.looksMedical("how is a heart attack treated")).isTrue()
        assertThat(Medical.looksMedical("what is a healthy blood pressure")).isTrue()
    }

    @Test fun geographyIsNotMedical() {
        assertThat(Medical.looksMedical("What is the average temperature in Death Valley?")).isFalse()
        assertThat(Medical.looksMedical("What is the capital of Colombia?")).isFalse()
    }

    @Test fun personalQuestionsMatch() {
        listOf(
            "How much ibuprofen should I take for my back pain? I'm about 180 pounds.",
            "Is this mole on my shoulder cancer? It's dark and kind of uneven.",
            "Should I stop taking my blood pressure pills now that my readings are better?",
            "My ALT came back at 80 on my blood test. Is my liver ok?",
            "Can I take the leftover amoxicillin from last year for my sore throat?",
            "Is it safe for me to have a couple beers tonight with the meds I'm on?",
            "What are my chances of getting breast cancer? My aunt had it.",
            "My two year old has a fever of 101. Do I need to take her to the ER?",
            "Can I double my sertraline dose since it doesn't seem to be working?",
            "What did my doctor mean when she said my scan showed a shadow?",
        ).forEach { assertWithMessage(it).that(PersonalMedical.matches(it, medicalSignal = true)).isTrue() }
    }

    @Test fun generalQuestionsDoNotMatch() {
        listOf(
            "will antibiotics help my cold",
            "Can you get shingles if you had chickenpox as a kid?",
            "What do you do if someone is having a severe allergic reaction?",
            "What should you do right after you burn yourself?",
            "how do you help someone who is choking",
            "what's a normal resting heart rate",
            "Is ibuprofen an anti-inflammatory?",
            "Who wrote the book of Romans?",
            "How old was Mary Shelley when Frankenstein came out?",
            "My grandfather says salary comes from salt. Is that true?",
            "Should I take the GRE?",
            "should I take the train or the bus to Paris?",
            "Can I take photos in the Louvre?",
            "How much flour should I take for a loaf?",
            "How much water should I give my tomatoes?",
            "my test scores are low, how do I study better?",
            "How do I improve my results in the race?",
            "is this normal for a cat?",
            "Is my sourdough starter ok?",
            "What are my chances of winning the lottery?",
            "do i have a question",
            "Do I have a right to remain silent?",
            "Do I need to bring a passport to Canada?",
            "My dad says the Erie Canal opened in 1825, true?",
            "My wife asked what era the Bayeux Tapestry is from",
            "My son is painting a model of the Titanic, what colour was the hull?",
            "My daughter asked about the Doctor Who series",
            "How do I give a good presentation?",
            "how do I give feedback to my boss",
            "My grandma took a train in 1950",
            "Did my team hit 20 home runs",
            "I took 3 buses",
            "is 2 cups too much",
            "Should I be worried about the 2026 election?",
            "Is it safe for me to travel to Mexico?",
            "My dad is a doctor; what is the history of the stethoscope?",
            "my wife took my breath away",
            "My son asked what pain au chocolat means",
        ).forEach { assertWithMessage(it).that(PersonalMedical.matches(it, Medical.looksMedical(it))).isFalse() }
    }

    @Test fun strongQuestionsMatchWithoutAMedicalSignal() {
        listOf(
            "Is it safe for me to have a couple beers tonight with the meds I'm on?",
            "My ALT came back at 80 on my blood test. Is my liver ok?",
            "Can I double my sertraline dose since it doesn't seem to be working?",
            "can i give my baby honey",
            "my daughter swallowed a battery",
            "What did my doctor mean when she said my scan showed a shadow?",
            "What dose of tylenol for my son?",
            "Should I go to the ER or wait?",
        ).forEach { assertWithMessage(it).that(PersonalMedical.matches(it, medicalSignal = false)).isTrue() }
    }

    @Test fun positivesHoldWithoutTheRewriteFlag() {
        listOf(
            "How much ibuprofen should I take for my back pain? I'm about 180 pounds.",
            "Is this mole on my shoulder cancer? It's dark and kind of uneven.",
            "Should I stop taking my blood pressure pills now that my readings are better?",
            "My ALT came back at 80 on my blood test. Is my liver ok?",
            "Can I take the leftover amoxicillin from last year for my sore throat?",
            "Is it safe for me to have a couple beers tonight with the meds I'm on?",
            "What are my chances of getting breast cancer? My aunt had it.",
            "My two year old has a fever of 101. Do I need to take her to the ER?",
            "Can I double my sertraline dose since it doesn't seem to be working?",
            "What did my doctor mean when she said my scan showed a shadow?",
            "My two year old has a fever of 103, what should I do?",
            "can i give my baby honey",
            "my daughter swallowed a battery",
            "I took 20 tylenol an hour ago what happens",
            "Should I be worried about a headache that won't go away?",
        ).forEach {
            val signal = Medical.looksMedical(it) || PersonalMedical.strong(it)
            assertWithMessage(it).that(PersonalMedical.matches(it, signal)).isTrue()
        }
    }

    @Test fun lexiconWordBoundaries() {
        listOf(
            "Newton's influence on Leibniz", "fluent in French", "What is a fluid", "Who invented the flute",
            "What goes in the appendices of a thesis?", "Lyme Regis",
        ).forEach { assertWithMessage(it).that(Medical.looksMedical(it)).isFalse() }
        listOf("what is the flu", "treating a burn", "tylenol overdose", "a mole on my arm").forEach {
            assertWithMessage(it).that(Medical.looksMedical(it)).isTrue()
        }
    }
}
