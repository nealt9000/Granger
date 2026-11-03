package app.granger.core.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadwordTest {
    @Test fun askedWhatAWordMeans() {
        assertEquals("betimes", Headword.word("what does betimes mean"))
        assertEquals("wherefore", Headword.word("when juliet says wherefore art thou romeo, what does wherefore mean"))
        assertEquals("apoplexy", Headword.word("what did apoplexy mean in old books"))
        assertEquals("half life", Headword.word("what does half life mean"))
        assertEquals("gaslighting", Headword.word("What does the word \"gaslighting\" mean?"))
        assertEquals("serendipity", Headword.word("What's the meaning of serendipity?"))
        assertEquals("ubiquitous", Headword.word("define ubiquitous"))
    }

    @Test fun askedWhereAWordComesFrom() {
        assertEquals("quarantine", Headword.word("where does the word quarantine come from"))
        assertEquals("salary", Headword.word("What's the origin of the word salary?"))
        assertEquals("OK", Headword.word("etymology of OK"))
    }

    @Test fun notAboutAWord() {
        assertNull(Headword.word("What did my doctor mean when she said my scan showed a shadow?"))
        assertNull(Headword.word("What did my boss mean in the email he sent me this morning?"))
        assertNull(Headword.word("where does rain come from"))
        assertNull(Headword.word("is it true salary comes from roman soldiers getting paid in salt"))
        assertNull(Headword.word("what does the bible say about tattoos"))
        assertNull(Headword.word("What is the meaning of life in Buddhism?"))
        assertNull(Headword.word("what does it mean to be stoic"))
    }

    @Test fun spanish() {
        assertEquals("madrugué", Headword.word("que significa madrugué"))
        assertEquals("apapachó", Headword.word("qué significa que alguien te apapachó"))
        assertEquals("sobremesa", Headword.word("¿Cuál es el significado de sobremesa?"))
        assertEquals("guagua", Headword.word("¿de dónde viene la palabra guagua?"))
        assertNull(Headword.word("mi TSH salió en 6.2, ¿qué significa para mí?"))
        assertNull(Headword.word("¿qué es la fotosíntesis?"))
    }

    @Test fun pathsForAWord() {
        assertEquals(listOf("half life", "half-life", "half_life"), Headword.paths("half life"))
        assertEquals(listOf("OK", "ok"), Headword.paths("OK"))
    }
}
