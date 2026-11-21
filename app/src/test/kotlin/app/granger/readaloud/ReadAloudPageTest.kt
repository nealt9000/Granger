package app.granger.readaloud

import app.granger.core.online.ReadAloudPage
import app.granger.reader.ReadAloudScript
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page script both phones run (Plan I5 Task 8): Android's, unchanged, now from the shared core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadAloudPageTest {
    @Test fun quotesAsOrgJsonDoes() {
        for (s in listOf("", "plain", "a \"quote\" and \\ slash", "</script>", "tab\tline\nret\r", "\u0001\u0085 x", "élan — ok")) {
            assertThat(ReadAloudPage.quote(s)).isEqualTo(JSONObject.quote(s))
        }
    }

    @Test fun androidRunsTheSharedScriptAndCallsAgree() {
        assertThat(ReadAloudScript.SOURCE).isEqualTo(ReadAloudPage.SOURCE)
        assertThat(ReadAloudPage.SOURCE).contains("GrangerBridge.onUserScroll")
        assertThat(ReadAloudPage.call("mark", "g3", "It's \"here\"", 0, 12, true))
            .isEqualTo(ReadAloudScript.call("mark", "g3", "It's \"here\"", 0, 12, true))
    }
}
