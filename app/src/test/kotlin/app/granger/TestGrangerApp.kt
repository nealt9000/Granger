package app.granger

/**
 * Robolectric uses this in place of [GrangerApp] for every JVM test (it looks for `Test` + the manifest's application
 * class in the same package). It skips the eager player and phone-scan start, so no test builds the whole
 * [AppContainer] or binds a MediaController just by starting.
 */
class TestGrangerApp : GrangerApp() {
    var mediaStarted = false
        private set

    override fun startMedia() {
        mediaStarted = true
    }

    override fun startVectorDownloads() = Unit
}
