package app.granger.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journeys a new user takes first (Plan E1 item 6): cold start, onboarding's Welcome, then each rail tool, Get more
 * and Kiwix's long list. The benchmark app starts with empty data, so these are first-run screens; that is what a
 * slow phone shows a tester first. Steps that find nothing (a screen that changed) are skipped, not failed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun firstRun() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.tap("Skip")
        for (tool in listOf("Library", "Browse", "Search", "Home", "Settings", "Library")) device.tool(tool)
        // Get more, then Kiwix: the 900-pack list, scrolled.
        if (device.tapDesc("Get more")) {
            if (device.tap("Kiwix")) {
                device.wait(Until.hasObject(By.scrollable(true)), 15_000)
                device.findObject(By.scrollable(true))?.let { list -> repeat(3) { list.fling(Direction.DOWN) } }
                device.pressBack()
            }
            device.pressBack()
        }
    }

    private fun UiDevice.tool(label: String) {
        if (tapDesc(label)) waitForIdle()
    }

    private fun UiDevice.tap(text: String): Boolean {
        val node = wait(Until.findObject(By.text(text)), 5_000) ?: return false
        node.click(); waitForIdle(); return true
    }

    private fun UiDevice.tapDesc(desc: String): Boolean {
        val node = wait(Until.findObject(By.desc(desc)), 5_000) ?: return false
        node.click(); waitForIdle(); return true
    }

    private companion object {
        const val PACKAGE = "app.granger.benchmark"
    }
}
