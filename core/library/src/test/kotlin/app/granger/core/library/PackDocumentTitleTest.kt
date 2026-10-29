package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PackDocumentTitleTest {
    private val path = "www.example.org/files/Water_storage-guide.pdf"

    @Test fun aPathForATitleGivesWayToTheLinkText() {
        // The "Food for preppers" pack's PDF on the phone (2026-10-29).
        val title = PackDocumentTitle.choose(path, "file://C:\\WINDOWS\\Desktop\\water.htm", "Storing water for a year", path)
        assertThat(title).isEqualTo("Storing water for a year")
    }

    @Test fun withNoGoodNameTheFileNameIsCleaned() {
        assertThat(PackDocumentTitle.choose("", "C:\\WINDOWS\\Desktop\\water.doc", "", path)).isEqualTo("Water storage guide")
        assertThat(PackDocumentTitle.choose("", "", "Click here", "docs/first%20aid_kit.pdf")).isEqualTo("first aid kit")
    }

    @Test fun theEntrysTitleThenThePdfsOwnComeFirst() {
        assertThat(PackDocumentTitle.choose("Emergency Childbirth", "Something else", "Link", path)).isEqualTo("Emergency Childbirth")
        assertThat(PackDocumentTitle.choose(path, "Emergency Childbirth", "Read the guide", path)).isEqualTo("Emergency Childbirth")
    }

    @Test fun junkTitlesAreRefused() {
        val junk = listOf(
            "", "   ", "file://C:\\WINDOWS\\Desktop\\water.htm", "C:\\Docs\\water", "D:/water", "water.pdf", "Water.DOC",
            "https://example.org/water", "www.example.org", "Microsoft Word - water", "Untitled", "untitled document",
            "Download", "click here", "PDF", "12", "--",
        )
        for (name in junk) assertWithMessage(name).that(PackDocumentTitle.isTitle(name)).isFalse()
    }

    @Test fun realTitlesAreKept() {
        for (name in listOf("Water: a guide", "Emergency Childbirth", "FM 21-76 Survival", "Canning 101")) {
            assertWithMessage(name).that(PackDocumentTitle.isTitle(name)).isTrue()
        }
    }

    @Test fun aLinksSizeAndKindAreTakenOff() {
        assertThat(PackDocumentTitle.choose("", "", "Root cellars (PDF, 1.2 MB)", path)).isEqualTo("Root cellars")
        assertThat(PackDocumentTitle.choose("", "", "  Root\n cellars  [pdf] ", path)).isEqualTo("Root cellars")
    }

    @Test fun theFileNameLosesItsExtensionAndSeparators() {
        assertThat(PackDocumentTitle.fromFileName("a/b/food_for-preppers.pdf")).isEqualTo("food for preppers")
        assertThat(PackDocumentTitle.fromFileName("plain")).isEqualTo("plain")
        assertThat(PackDocumentTitle.fromFileName("a/_.pdf")).isEqualTo("PDF")
    }
}
