package app.granger.core.research

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Spanish back-references. "La", "los" and "las" are articles far more often than pronouns, so they count only right
 * after a question word or "no" ("¿quién la pintó?"); "lo" not before "que"/"más"; "le"/"les" not when "a" doubles them.
 */
class FollowUpEsTest {
    @Test fun theSpanishEvalsFollowUpsReferBack() {
        // tune-es.json's follow-up group, all but "¿con quién se casó?" (below).
        listOf(
            "¿y cómo murió?", "en qué año lo pintó", "¿y en qué año fue?", "cuándo le dieron el nobel",
            "¿y cuál era su comida favorita?",
        ).forEach { assertWithMessage(it).that(FollowUp.refersBack(it)).isTrue() }
    }

    @Test fun otherSpanishBackReferences() {
        listOf(
            "¿quién la pintó?", "¿cuándo los construyeron?", "¿dónde nació él?", "¿Qué tal en invierno?",
            "¿Y qué hay de Lima?", "pero ¿cuántos habitantes tiene?", "o mejor, ¿cuándo llegó?", "¿qué pasó con ella después?",
            "¿cuánto mide esa montaña?", "¿qué se puede ver allí?", "¿sus hijos también fueron pintores?",
        ).forEach { assertWithMessage(it).that(FollowUp.refersBack(it)).isTrue() }
    }

    @Test fun spanishQuestionsThatStandAloneDont() {
        listOf(
            "¿cuándo fue la batalla de Ayacucho?", "cuál es la capital de bolivia, la paz o sucre",
            "¿quién escribió La casa de los espíritus?", "por qué en chile a los bebés les dicen guagua",
            "de dónde les viene el color verde a las plantas", "¿dónde nace el río Amazonas?",
            "¿qué es lo que más se come en Perú?", "¿de dónde son originarios los porotos?", "el libro de la familia que vive siete generaciones",
            "¿a qué altura está la paz en bolivia?", "¿cuál era la canción favorita de Frida Kahlo?",
        ).forEach { assertWithMessage(it).that(FollowUp.refersBack(it)).isFalse() }
    }

    @Test fun aDroppedSubjectIsNotCaught() {
        // Spanish drops the subject: "¿con quién se casó?" names no one and has no pronoun. A known miss.
        assertWithMessage("pro-drop").that(FollowUp.refersBack("¿con quién se casó?")).isFalse()
    }

    @Test fun spanishWordsDontCountForEnglish() {
        assertWithMessage("English alone").that(FollowUp.refersBack("¿y cómo murió?", listOf(ResearchLanguage.EN))).isFalse()
        assertWithMessage("Spanish alone").that(FollowUp.refersBack("How did he die?", listOf(ResearchLanguage.ES))).isFalse()
    }
}
