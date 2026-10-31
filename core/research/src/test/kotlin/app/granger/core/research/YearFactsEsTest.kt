package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Spanish dates in [YearFacts]: months, eras, ranges and event words, as Spanish Wikipedia writes its leads. The block
 * itself stays English (prompt text) until the Spanish eval has a baseline.
 */
class YearFactsEsTest {
    private val es = listOf(ResearchLanguage.ES)

    private fun p(title: String, text: String, heading: String = title) =
        Passage(SourceKind.ARTICLE, "wikipedia_es", title, location = title.replace(' ', '_'), heading = heading, text = text)

    private val cervantes = p(
        "Miguel de Cervantes",
        "Miguel de Cervantes Saavedra (Alcalá de Henares, 29 de septiembre de 1547-Madrid, 22 de abril de 1616) fue un " +
            "novelista, poeta, dramaturgo y soldado español.",
    )
    private val quijote = p(
        "Don Quijote de la Mancha",
        "Don Quijote de la Mancha es una novela escrita por el español Miguel de Cervantes Saavedra. Publicada su primera " +
            "parte con el título de El ingenioso hidalgo don Quijote de la Mancha a comienzos de 1605, es la obra más " +
            "destacada de la literatura española.",
    )

    @Test fun anAgeAtAPublication() {
        val q = "¿cuántos años tenía Cervantes cuando salió la primera parte del Quijote?"
        assertThat(YearFacts.block(q, emptyList(), listOf(cervantes, quijote)))
            .contains("In 1605 (Quijote), Miguel de Cervantes was alive, aged 57 or 58, and died about 11 years later (22 April 1616) [1][2].")
    }

    @Test fun twoPublications() {
        val rayuela = p("Rayuela", "Rayuela es una novela del escritor argentino Julio Cortázar. Fue publicada por primera vez el 28 de junio de 1963 en Buenos Aires.")
        val paramo = p("Pedro Páramo", "Pedro Páramo es la primera novela del escritor mexicano Juan Rulfo, publicada en 1955 por el Fondo de Cultura Económica.")
        assertThat(YearFacts.block("¿cuántos años antes de Rayuela se publicó Pedro Páramo?", emptyList(), listOf(rayuela, paramo)))
            .contains("Rayuela (28 June 1963) came about 8 years after Pedro Páramo (1955) [1][2].")
    }

    @Test fun twoBattlesWithFullDates() {
        val boyaca = p("Batalla de Boyacá", "La batalla de Boyacá fue la confrontación más importante de la guerra de independencia de Colombia, librada el 7 de agosto de 1819 en el puente de Boyacá.")
        val ayacucho = p("Batalla de Ayacucho", "La batalla de Ayacucho fue el último gran enfrentamiento de las guerras de independencia hispanoamericanas, librado el 9 de diciembre de 1824 en la pampa de Ayacucho.")
        assertThat(YearFacts.block("¿cuántos años pasaron entre la batalla de Boyacá y la de Ayacucho?", emptyList(), listOf(boyaca, ayacucho)))
            .contains("Batalla de Ayacucho (9 December 1824) came 5 years after Batalla de Boyacá (7 August 1819) [1][2].")
    }

    @Test fun anEndIsASpanishRangesLastYear() {
        val muro = p("Muro de Berlín", "El Muro de Berlín fue un muro de hormigón que dividió la ciudad de Berlín de 1961 a 1989. Hoy quedan algunos tramos.")
        val wiles = p("Andrew Wiles", "Sir Andrew John Wiles es un matemático británico. La demostración del último teorema de Fermat, presentada por Wiles en 1994, cerró un problema abierto desde 1637.")
        val q = "¿Cuántos años pasaron entre la caída del Muro de Berlín y la demostración del último teorema de Fermat?"
        assertThat(YearFacts.block(q, emptyList(), listOf(muro, wiles))).contains("Fermat (1994) came about 5 years after Muro de Berlín (1989) [1][2].")
    }

    @Test fun aYearBcIsntCounted() {
        val tenochtitlan = p("Tenochtitlan", "Tenochtitlan fue la capital de los mexicas, fundada en 1325 d. C. en un islote del lago de Texcoco.")
        val caida = p("Caída de Constantinopla", "La caída de Constantinopla fue la conquista de la ciudad por el Imperio otomano el 29 de mayo de 1453.")
        val babilonia = p("Babilonia", "Babilonia fue una ciudad de Mesopotamia. Hammurabi la hizo capital de su imperio en 1792 a. C. Su código de leyes es famoso.")
        assertThat(YearFacts.block("¿Cuántos años pasaron entre la fundación de Tenochtitlan y la caída de Constantinopla?", emptyList(), listOf(tenochtitlan, caida)))
            .contains("Caída de Constantinopla (29 May 1453) came about 128 years after Tenochtitlan (1325) [1][2].")
        assertThat(YearFacts.block("¿Cuántos años pasaron entre Babilonia y la caída de Constantinopla?", emptyList(), listOf(babilonia, caida))).isNull()
    }

    @Test fun spanishLifespans() {
        assertThat(YearFacts.lifespan(cervantes.text, es)).isEqualTo(YearFacts.Date(1547, 9, 29) to YearFacts.Date(1616, 4, 22))
        assertThat(YearFacts.lifespan("Simón Bolívar (Caracas, 24 de julio de 1783-Santa Marta, 17 de diciembre de 1830) fue un militar", es))
            .isEqualTo(YearFacts.Date(1783, 7, 24) to YearFacts.Date(1830, 12, 17))
        assertThat(YearFacts.lifespan("Gabriel García Márquez (Aracataca, 6 de marzo de 1927-Ciudad de México, 17 de abril de 2014) fue", es))
            .isEqualTo(YearFacts.Date(1927, 3, 6) to YearFacts.Date(2014, 4, 17))
        assertThat(YearFacts.lifespan("Frida Kahlo (Coyoacán, 6 de julio de 1907 – Coyoacán, 13 de julio de 1954) fue", es))
            .isEqualTo(YearFacts.Date(1907, 7, 6) to YearFacts.Date(1954, 7, 13))
        assertThat(YearFacts.lifespan("Juan Rulfo (1917-1986) fue un escritor", es)).isEqualTo(YearFacts.Date(1917) to YearFacts.Date(1986))
        assertThat(YearFacts.lifespan("Sor Juana (Nepantla, Noviembre de 1651-México, Abril de 1695) fue", es))
            .isEqualTo(YearFacts.Date(1651, 11) to YearFacts.Date(1695, 4))
    }

    @Test fun spanishDatesNeedASpanishQuestion() {
        // English alone has no Spanish months, and "1547-Madrid" is no dash between dates.
        assertThat(YearFacts.lifespan(cervantes.text)).isNull()
    }

    @Test fun spanishVerbsComeBeforeTheirName() {
        assertThat(YearFacts.eventWords("¿cuántos años tenía Cervantes cuando salió la primera parte del Quijote?", listOf("cervantes", "quijote")))
            .containsExactly("quijote", setOf("salio", "parte"))
        assertThat(YearFacts.eventWords("¿cuántos años antes de Rayuela se publicó Pedro Páramo?", listOf("rayuela", "pedro paramo")))
            .containsExactly("pedro paramo", setOf("publico"))
        assertThat(YearFacts.eventWords("¿Cuántos años pasaron desde que Colón llegó a América hasta que fundaron Tenochtitlan?", listOf("colon", "america", "tenochtitlan")))
            .containsExactly("colon", setOf("llego"), "tenochtitlan", setOf("fundaron"))
        assertThat(YearFacts.eventWords("¿Llegó Darwin a ver la tabla periódica de Mendeléyev?", listOf("darwin", "mendeleyev")))
            .containsExactly("mendeleyev", setOf("tabla", "periodica"))
    }

    @Test fun theBlockStaysEnglish() {
        val block = YearFacts.block("¿cuántos años tenía Cervantes cuando salió la primera parte del Quijote?", emptyList(), listOf(cervantes, quijote))!!
        assertThat(block).startsWith("Dates in the passages:")
        assertThat(block).contains("born 29 September 1547, died 22 April 1616 [1]")
        assertThat(block).contains("Worked out from those dates:")
    }
}
