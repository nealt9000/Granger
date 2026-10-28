package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The titles are the packs' own `<title>`s, read with libzim on the Mac (2026-10-28). */
class PageTitlesTest {
    @Test fun wikipediasSuffixGoes() {
        assertThat(PageTitles.clean("Apocalypse - Wikipedia")).isEqualTo("Apocalypse")
        assertThat(PageTitles.clean("Book of Revelation – Wikipedia")).isEqualTo("Book of Revelation")
    }

    @Test fun aSiteAfterABarGoes() {
        assertThat(PageTitles.clean("Aljotta | Based Cooking", "based.cooking LukeSmith")).isEqualTo("Aljotta")
        assertThat(PageTitles.clean("Älplermagronen (Alpine Macaroni) | Based Cooking")).isEqualTo("Älplermagronen (Alpine Macaroni)")
    }

    @Test fun aStackExchangeSiteGoesButADashInTheQuestionStays() {
        assertThat(PageTitles.clean("Night feeds and naps - Parenting Stack Exchange")).isEqualTo("Night feeds and naps")
        assertThat(PageTitles.clean("Architecture - calculating miss penalty - Computer Science Stack Exchange", "Computer Science Q&A"))
            .isEqualTo("Architecture - calculating miss penalty")
    }

    @Test fun iFixitTitlesHaveNoSiteAndStayAsTheyAre() {
        assertThat(PageTitles.clean("1997-2001 Jeep Cherokee XJ", "iFixit in English")).isEqualTo("1997-2001 Jeep Cherokee XJ")
        assertThat(PageTitles.clean("Access HD Digital Converter Teardown", "iFixit in English")).isEqualTo("Access HD Digital Converter Teardown")
    }

    @Test fun aTitleThatIsOnlyTheSiteStays() {
        assertThat(PageTitles.clean("Based Cooking")).isEqualTo("Based Cooking")
        assertThat(PageTitles.clean("Parenting Stack Exchange")).isEqualTo("Parenting Stack Exchange")
        assertThat(PageTitles.clean("Wikipedia", "Wikipedia")).isEqualTo("Wikipedia")
    }

    @Test fun thePacksTitleAsASuffixGoes() {
        assertThat(PageTitles.clean("Slow sand filters - Water Treatment Library", "Water Treatment Library")).isEqualTo("Slow sand filters")
        assertThat(PageTitles.clean("Slow sand filters — water treatment library", "Water Treatment Library")).isEqualTo("Slow sand filters")
        assertThat(PageTitles.clean("Slow sand filters - Water Treatment Library")).isEqualTo("Slow sand filters - Water Treatment Library")
    }

    @Test fun aMainPageIsCalledByThePack() {
        assertThat(PageTitles.clean("Main Page", "WikEM")).isEqualTo("WikEM")
        assertThat(PageTitles.clean("Main page - Wikipedia", "Wikipedia")).isEqualTo("Wikipedia")
        assertThat(PageTitles.clean("Home", "Appropedia")).isEqualTo("Appropedia")
        assertThat(PageTitles.clean("Index", "Bash")).isEqualTo("Bash")
        assertThat(PageTitles.clean("Highest Voted Questions", "Computer Science Q&A", mainPage = true)).isEqualTo("Computer Science Q&A")
        // Without the pack's name there's nothing better.
        assertThat(PageTitles.clean("Main Page")).isEqualTo("Main Page")
        assertThat(PageTitles.clean("Home Brewing", "Appropedia")).isEqualTo("Home Brewing")
    }
}
