package app.granger.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val music = WidgetModel("Mars", "Holst", null, playing = true, secondary = Secondary.NEXT, progress = 0.5f, empty = false)

    @Test fun smallSizeShowsCoverAndPlay() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(PlayerWidget.SMALL)
        provideComposable { PlayerWidgetContent(music, cover = null) }
        onNode(hasTestTag(PlayerWidgetTags.COVER)).assertExists()
        onNode(hasTestTag(PlayerWidgetTags.PLAY_PAUSE)).assertExists()
        onNode(hasTestTag(PlayerWidgetTags.TITLE)).assertDoesNotExist()
        onNode(hasTestTag(PlayerWidgetTags.SECONDARY)).assertDoesNotExist()
        onNode(hasTestTag(PlayerWidgetTags.PROGRESS)).assertDoesNotExist()
    }

    @Test fun mediumSizeShowsTitleAndSecondary() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(PlayerWidget.MEDIUM)
        provideComposable { PlayerWidgetContent(music, cover = null) }
        onNode(hasText("Mars")).assertExists()
        onNode(hasText("Holst")).assertExists()
        onNode(hasTestTag(PlayerWidgetTags.SECONDARY)).assertExists()
        onNode(hasTestTag(PlayerWidgetTags.PROGRESS)).assertDoesNotExist()
    }

    @Test fun largeSizeShowsProgress() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { PlayerWidgetContent(music, cover = null) }
        onNode(hasTestTag(PlayerWidgetTags.PROGRESS)).assertExists()
        onNode(hasText("Mars")).assertExists()
    }

    @Test fun emptyShowsOpenGranger() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(PlayerWidget.MEDIUM)
        provideComposable { PlayerWidgetContent(WidgetModel.EMPTY, cover = null) }
        onNode(hasText("Nothing playing")).assertExists()
        onNode(hasText("Open Granger")).assertExists()
        onNode(hasTestTag(PlayerWidgetTags.PLAY_PAUSE)).assertDoesNotExist()
    }
}
