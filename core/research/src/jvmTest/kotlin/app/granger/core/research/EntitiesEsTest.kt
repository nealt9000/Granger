package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntitiesEsTest {
    private val es = listOf(ResearchLanguage.ES)

    @Test fun spanishDateQuestionsAreCued() {
        assertThat(Entities.asked("¿cuántos años tenía García Márquez cuando ganó el Nobel?")).isTrue()
        assertThat(Entities.asked("qué edad tenía bolívar en la batalla de boyacá")).isTrue()
        assertThat(Entities.asked("¿cuántos años pasaron entre la batalla de Boyacá y la de Ayacucho?")).isTrue()
        assertThat(Entities.asked("¿cuántos años antes de Rayuela se publicó Pedro Páramo?")).isTrue()
        assertThat(Entities.asked("quién nació antes, sor juana o cervantes")).isTrue()
        assertThat(Entities.asked("¿Pudo Cervantes haber leído a Shakespeare?")).isTrue()
        assertThat(Entities.asked("¿Ya era presidente Lincoln cuando se publicó El origen de las especies?")).isTrue()
        assertThat(Entities.asked("¿Vivía aún Darwin cuando Mendeléyev publicó su tabla?")).isTrue()
        assertThat(Entities.asked("¿Llegó Darwin a ver la tabla periódica?")).isTrue()
    }

    @Test fun ordinarySpanishQuestionsAreNot() {
        assertThat(Entities.asked("¿quién escribió Rayuela?")).isFalse()
        assertThat(Entities.asked("¿puedo dejar de tomar mis pastillas si ya me siento bien?")).isFalse()
        assertThat(Entities.asked("¿cuál es la capital de Bolivia?")).isFalse()
        assertThat(Entities.asked("cuántos años tenía", listOf(ResearchLanguage.EN))).isFalse()
    }

    @Test fun spanishOpenersArentNames() {
        assertThat(Entities.named("¿Cuántos años tenía Cervantes cuando salió la primera parte del Quijote?", emptyList()))
            .containsExactly("cervantes", "quijote").inOrder()
        assertThat(Entities.named("¿Cuándo murió Simón Bolívar?", emptyList())).containsExactly("simon bolivar")
        assertThat(Entities.named("¿Quién escribió La casa de los espíritus?", emptyList())).isEmpty()
    }

    @Test fun deJoinsASpanishName() {
        assertThat(Entities.runs("¿Hay vuelos diarios de Santiago a la Isla de Pascua?")).containsExactly("Santiago", "Isla de Pascua").inOrder()
        assertThat(Entities.runs("¿Cuándo se publicó Don Quijote de la Mancha?")).containsExactly("Don Quijote de la Mancha")
        assertThat(Entities.runs("¿Cuántos años pasaron entre la caída del Muro de Berlín y la de Constantinopla?"))
            .containsExactly("Muro de Berlín", "Constantinopla").inOrder()
        // "y" doesn't join: two names side by side are the usual case, "Ramón y Cajal" the rare one.
        assertThat(Entities.runs("¿Quién nació antes, Simón Bolívar y Francisco de Paula Santander?"))
            .containsExactly("Simón Bolívar", "Francisco de Paula Santander").inOrder()
        // A Spanish title capitalises its first word only; the rewrite's keywords name it.
        assertThat(Entities.runs("¿cuándo se publicó Cien años de soledad?")).containsExactly("Cien")
    }

    @Test fun englishRunsDontJoinOnDe() {
        assertThat(Entities.runs("Did Miguel de Cervantes die before Shakespeare?")).containsExactly("Miguel", "Cervantes", "Shakespeare").inOrder()
    }

    @Test fun spanishTitlesMatch() {
        assertThat(Entities.match("Batalla de Boyacá", "boyaca", es)).isEqualTo(2)
        assertThat(Entities.match("Santiago Ramón y Cajal", "cajal", es)).isEqualTo(2)
        assertThat(Entities.match("Santiago Ramón y Cajal", "cajal")).isEqualTo(0)
        assertThat(Entities.match("Bartolomé de las Casas", "casas", es)).isEqualTo(2)
    }
}
