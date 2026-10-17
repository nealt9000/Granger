package app.granger.ebook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import app.granger.R
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Hosts the restore the way MainActivity does: factory before super.onCreate, stand-ins dropped after (if [drop]). */
class RestoreHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        factory?.let { supportFragmentManager.fragmentFactory = it }
        super.onCreate(savedInstanceState)
        if (drop) dropNavigatorStandIns(supportFragmentManager)
    }

    companion object {
        var factory: FragmentFactory? = null
        var drop = false
    }
}

/** Plays the navigator: a view holding a pager container, with a page fragment in it, as Readium's has. */
class PagerParent : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).apply { id = PAGER_ID }

    companion object {
        const val PAGER_ID = 0x0badf00d
    }
}

class PageChild : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = View(inflater.context)
}

/**
 * Process death with a book open: the fragment manager restores the navigator (here [PagerParent]) and its page
 * children before Compose has made the container, and moves them on in the activity's onStart.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigatorRestoreTest {
    private val tag = "readium:book:granger:gc-hamlet"

    @After fun reset() {
        RestoreHostActivity.factory = null
        RestoreHostActivity.drop = false
        OpenBooks.current = null
        OpenBooks.navigatorFragments = null
    }

    /** The saved state of an activity showing the navigator with one page, as the system keeps it across process death. */
    private fun savedWithNavigator(): Bundle {
        val first = Robolectric.buildActivity(RestoreHostActivity::class.java).setup()
        val activity = first.get()
        activity.setContentView(FrameLayout(activity).apply { id = R.id.readium_navigator_container })
        val parent = PagerParent()
        activity.supportFragmentManager.beginTransaction().add(R.id.readium_navigator_container, parent, tag).commitNow()
        parent.childFragmentManager.beginTransaction().add(PagerParent.PAGER_ID, PageChild()).commitNow()
        val bundle = Bundle()
        first.pause().stop()
        first.saveInstanceState(bundle)
        first.destroy()
        return bundle
    }

    /** Relaunches from [saved] with nothing open, the way the process comes back; no content view, as before Compose. */
    private fun relaunch(saved: Bundle) =
        Robolectric.buildActivity(RestoreHostActivity::class.java).create(saved).start().postCreate(saved).resume()

    @Test fun aBlankFragmentWithoutAViewCrashesTheRelaunch() {
        // The bug this guards against: the old factory's plain Fragment() has no view for its restored page to sit in.
        val saved = savedWithNavigator()
        RestoreHostActivity.factory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                if (className == PagerParent::class.java.name) Fragment() else super.instantiate(classLoader, className)
        }
        val error = assertThrows(IllegalStateException::class.java) { relaunch(saved) }
        assertThat(error).hasMessageThat().contains("does not have a view")
    }

    @Test fun theStandInSurvivesTheRelaunchWithItsPagesDropped() {
        val saved = savedWithNavigator()
        RestoreHostActivity.factory = ReadiumFragmentFactory(PagerParent::class.java.name)
        val activity = relaunch(saved).get()
        val standIn = activity.supportFragmentManager.findFragmentByTag(tag)
        assertThat(standIn).isInstanceOf(NavigatorStandIn::class.java)
        assertThat(standIn!!.view).isNotNull()
        assertThat(standIn.childFragmentManager.fragments).isEmpty()
    }

    @Test fun theActivityDropsTheStandInBeforeOnStart() {
        val saved = savedWithNavigator()
        RestoreHostActivity.factory = ReadiumFragmentFactory(PagerParent::class.java.name)
        RestoreHostActivity.drop = true
        val activity = relaunch(saved).get()
        assertThat(activity.supportFragmentManager.findFragmentByTag(tag)).isNull()
        assertThat(activity.supportFragmentManager.fragments.filterIsInstance<NavigatorStandIn>()).isEmpty()
    }

    @Test fun aLiveNavigatorIsKeptForTheHostToAdopt() {
        // Rotation with the book open: the factory builds the real navigator, and dropping leaves it alone.
        val saved = savedWithNavigator()
        RestoreHostActivity.factory = object : FragmentFactory() {} // Builds PagerParent itself, as the open book would.
        RestoreHostActivity.drop = true
        val activity = Robolectric.buildActivity(RestoreHostActivity::class.java).create(saved).get()
        assertThat(activity.supportFragmentManager.findFragmentByTag(tag)).isInstanceOf(PagerParent::class.java)
    }
}
