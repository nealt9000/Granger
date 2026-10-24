package app.granger.ebook

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentFactory
import app.granger.R
import org.readium.r2.navigator.VisualNavigator

/** The fragment container; one id, so the fragment manager restores a navigator into it after rotation. */
private val ContainerId = R.id.readium_navigator_container

private fun navigatorTag(bookId: String, look: String) = if (look.isEmpty()) "readium:$bookId" else "readium:$bookId:$look"

/**
 * Readium's navigator fragment (EPUB or PDF) in a [FragmentContainerView]. The fragment is committed only when the
 * container doesn't already hold a live navigator for [bookId], so after rotation the one the system restored is
 * reused. A stand-in or foreign fragment there (a [NavigatorStandIn] the activity didn't drop, or another book's) is
 * replaced. Leaving the screen removes the fragment; rotating keeps it. [fragments] builds a new navigator; the reader
 * rebuilds it whenever the place changes, so a navigator rebuilt after rotation opens at the latest place.
 * A change of [look] replaces the navigator with a fresh one (a PDF's Night, which PDFium only applies on layout).
 * [onReady] gets every navigator shown.
 */
@SuppressLint("RestrictedApi") // FragmentManager.onContainerAvailable, as androidx.fragment.compose.AndroidFragment uses it.
@Composable
fun NavigatorHost(
    bookId: String,
    factory: BookNavigator,
    fragments: FragmentFactory,
    onReady: (VisualNavigator) -> Unit,
    modifier: Modifier = Modifier,
    look: String = "",
    /** True once the reader's view model is cleared: a screen still composing on its way out claims nothing. */
    isCleared: () -> Boolean = { false },
) {
    val activity = LocalContext.current.findFragmentActivity()
    val fragmentManager = activity.supportFragmentManager
    val ready by rememberUpdatedState(onReady)
    // What ReadiumFragmentFactory builds a restored navigator with. This reader is current again, too, when it
    // returns to the top after another reader of the same book took over and was cleared.
    SideEffect {
        if (isCleared()) return@SideEffect
        if (OpenBooks.current?.second !== factory) OpenBooks.current = bookId to factory
        OpenBooks.navigatorFragments = factory to fragments
    }

    val holder = remember { arrayOfNulls<FragmentContainerView>(1) }
    AndroidView(
        modifier = modifier,
        factory = { context -> FragmentContainerView(context).apply { id = ContainerId }.also { holder[0] = it } },
    )

    DisposableEffect(fragmentManager, bookId, factory, look) {
        val container = holder[0]
        if (container == null) {
            Log.e("Granger", "E-book navigator container missing; the book can't be shown")
            return@DisposableEffect onDispose {}
        }
        if (fragmentManager.isDestroyed) return@DisposableEffect onDispose {}
        val tag = navigatorTag(bookId, look)
        var navigator: Fragment? = fragmentManager.findFragmentByTag(tag)?.takeIf { it is VisualNavigator }
        if (navigator != null) {
            // Restored before this container existed: its view was created detached; attach it here.
            if (navigator.view?.parent == null) fragmentManager.onContainerAvailable(container)
            val view = navigator.view
            if (view != null && view.parent !== container) navigator = null
        }
        if (navigator == null) {
            val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
            fragmentManager.fragments
                .filter { it.id == ContainerId || it.tag == tag }
                .forEach { transaction.remove(it) }
            val fresh = fragments.instantiate(activity.classLoader, factory.fragmentClass)
            transaction.add(container, fresh, tag)
            if (fragmentManager.isStateSaved) transaction.commitNowAllowingStateLoss() else transaction.commitNow()
            navigator = fresh
        }
        ready(navigator as VisualNavigator)
        val shown: Fragment = navigator
        onDispose {
            // Rotation keeps the fragment so the system restores it into the next activity.
            if (!activity.isChangingConfigurations && !fragmentManager.isDestroyed && shown.isAdded) {
                fragmentManager.beginTransaction().remove(shown).commitNowAllowingStateLoss()
            }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> error("The e-book reader needs a FragmentActivity")
}
