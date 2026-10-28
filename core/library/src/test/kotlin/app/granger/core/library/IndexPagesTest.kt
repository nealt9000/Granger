package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The paths are the ones read from the real Stack Exchange and iFixit packs (see [IndexPages]). */
class IndexPagesTest {
    @Test fun stackExchangeIndexesAndPeopleAreIndexPages() {
        listOf(
            "users", "tags", "questions",
            "users_page=2", "tags_page=14", "questions_page=3",
            "users/1234/kviiri", "users/7/apocalypse", "tags/algorithms", "a/56789",
        ).forEach { assertWithMessage(it).that(IndexPages.isIndex(it)).isTrue() }
    }

    @Test fun questionsAndArticlesAreNot() {
        listOf(
            "questions/1234/what-is-a-turing-machine", "Apocalypse", "Book_of_Revelation", "home/home",
            "Guide/Replacing+the+Battery/1234", "Device/iPhone_6", "Teardown/iPhone+6+Teardown/29213", "Info/Glossary",
            "usersguide", "tagsoup", "about",
        ).forEach { assertWithMessage(it).that(IndexPages.isIndex(it)).isFalse() }
    }

    @Test fun iFixitPeopleAreIndexPages() {
        assertThat(IndexPages.isIndex("User/12345/Jane+Doe")).isTrue()
    }

    @Test fun anOldStyleNamespaceIsIgnored() {
        assertThat(IndexPages.isIndex("A/users/1234/kviiri")).isTrue()
        assertThat(IndexPages.isIndex("/tags")).isTrue()
        assertThat(IndexPages.isIndex("C/questions_page=2")).isTrue()
        assertThat(IndexPages.isIndex("A/Apocalypse")).isFalse()
    }
}
