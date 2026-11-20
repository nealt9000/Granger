package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaCuesEsTest {
    @Test fun spanishMediaWordsAreCues() {
        assertThat(MediaCues.asked("¿Qué canciones de Violeta Parra tengo?")).isTrue()
        assertThat(MediaCues.asked("¿Quién narró el audiolibro de Cien años de soledad?")).isTrue()
        assertThat(MediaCues.asked("¿Cuál es la banda sonora de Coco?")).isTrue()
        assertThat(MediaCues.asked("quiero escuchar música andina")).isTrue()
        assertThat(MediaCues.asked("¿Qué versión de Don Quijote está leída por una mujer?")).isTrue()
        assertThat(MediaCues.asked("¿quién compuso la Sinfonía del Nuevo Mundo?")).isTrue()
    }

    @Test fun spanishPhrasesAskWhatsOnThePhone() {
        assertThat(MediaCues.asked("¿Tengo algo de Mercedes Sosa?")).isTrue()
        assertThat(MediaCues.asked("¿Tengo a Mozart?")).isTrue()
        assertThat(MediaCues.asked("¿Tengo Holst?")).isTrue()
        assertThat(MediaCues.asked("¿Hay algo de Carlos Gardel?")).isTrue()
    }

    @Test fun ordinarySpanishQuestionsAreNot() {
        assertThat(MediaCues.asked("tengo un lunar que me pica, ¿es cáncer?")).isFalse()
        assertThat(MediaCues.asked("¿Quién inventó la grabadora?")).isFalse()
        assertThat(MediaCues.asked("¿qué es el ancho de banda?")).isFalse()
        assertThat(MediaCues.asked("¿cuánto cuesta la banda ancha en Chile?")).isFalse()
        assertThat(MediaCues.asked("¿a qué altura está la paz en bolivia?")).isFalse()
    }

    @Test fun spanishCuesAreOnlyForSpanish() {
        assertThat(MediaCues.asked("canción", listOf(ResearchLanguage.EN))).isFalse()
        assertThat(MediaCues.asked("canción", listOf(ResearchLanguage.ES))).isTrue()
    }
}
