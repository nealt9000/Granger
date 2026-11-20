package app.granger

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Every string key the shared models use (`UiText.Res("key")`, `UiText.Plural("key")`) has its Android id in
 * [ScreenTextIds] (Plan I3 Task 4). Missing ones: run tools/ios/make_text_ids.py.
 */
class ScreenTextIdsTest {
    private val shared = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "core/screens/src/commonMain/kotlin") }.first { it.isDirectory }

    private fun keys(pattern: Regex) = shared.walk().filter { it.extension == "kt" }
        .flatMap { f -> pattern.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()

    @Test fun everySharedStringHasAnId() {
        val missing = keys(Regex("""UiText\.Res\(\s*"([a-z0-9_]+)"""")).filter { runCatching { ScreenTextIds.string(it) }.getOrDefault(0) == 0 }
        assertThat(missing).isEmpty()
    }

    @Test fun everySharedPluralHasAnId() {
        val missing = keys(Regex("""UiText\.Plural\(\s*"([a-z0-9_]+)"""")).filter { runCatching { ScreenTextIds.plural(it) }.getOrDefault(0) == 0 }
        assertThat(missing).isEmpty()
    }

    @Test fun keyedAndIdTextResolveTheSameResource() {
        assertThat(UiText.Res(R.string.games_title).resId).isEqualTo(R.string.games_title)
        assertThat(UiText.Res(R.string.games_title)).isNotEqualTo(UiText.Res("games_title"))
    }
}
