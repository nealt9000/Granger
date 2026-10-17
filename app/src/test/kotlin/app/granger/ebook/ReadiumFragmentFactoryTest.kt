package app.granger.ebook

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import app.granger.testPublication
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stands in for a navigator fragment: what the paired fragment factory builds. */
class MarkerFragment : Fragment()

/** Any other fragment class the activity restores. */
class ForeignFragment : Fragment()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadiumFragmentFactoryTest {
    private val navigatorClass = EpubNavigatorFragment::class.java.name
    private val loader = javaClass.classLoader!!
    private val factory = EpubNavigatorFactory(testPublication())
    private val builds = object : FragmentFactory() {
        override fun instantiate(classLoader: ClassLoader, className: String): Fragment = MarkerFragment()
    }

    @After fun forget() {
        OpenBooks.current = null
        OpenBooks.navigatorFragments = null
    }

    @Test fun nothingOpenGivesAStandIn() {
        OpenBooks.navigatorFragments = factory to builds // Left from a reader whose book is gone.
        val fragment = ReadiumFragmentFactory().instantiate(loader, navigatorClass)
        assertThat(fragment).isInstanceOf(NavigatorStandIn::class.java)
    }

    @Test fun fragmentsPairedWithAnotherBookGiveAStandIn() {
        OpenBooks.current = "b" to factory
        OpenBooks.navigatorFragments = EpubNavigatorFactory(testPublication()) to builds
        assertThat(ReadiumFragmentFactory().instantiate(loader, navigatorClass)).isInstanceOf(NavigatorStandIn::class.java)
        OpenBooks.navigatorFragments = null
        assertThat(ReadiumFragmentFactory().instantiate(loader, navigatorClass)).isInstanceOf(NavigatorStandIn::class.java)
    }

    @Test fun matchingPairBuildsTheNavigator() {
        OpenBooks.current = "b" to factory
        OpenBooks.navigatorFragments = factory to builds
        assertThat(ReadiumFragmentFactory().instantiate(loader, navigatorClass)).isInstanceOf(MarkerFragment::class.java)
    }

    @Test fun otherClassesAreBuiltAsUsual() {
        OpenBooks.current = "b" to factory
        OpenBooks.navigatorFragments = factory to builds
        assertThat(ReadiumFragmentFactory().instantiate(loader, ForeignFragment::class.java.name))
            .isInstanceOf(ForeignFragment::class.java)
    }

    @Test fun releaseForgetsOnlyItsOwnFactory() {
        val other = EpubNavigatorFactory(testPublication())
        OpenBooks.current = "b" to other
        OpenBooks.navigatorFragments = other to builds
        OpenBooks.release(factory)
        assertThat(OpenBooks.current?.second).isSameInstanceAs(other)
        assertThat(OpenBooks.navigatorFragments?.first).isSameInstanceAs(other)
        OpenBooks.release(other)
        assertThat(OpenBooks.current).isNull()
        assertThat(OpenBooks.navigatorFragments).isNull()
    }
}
