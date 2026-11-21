package app.granger.sources

import app.granger.core.downloads.QueuePrefs

/** Also the queue's own settings ([QueuePrefs]): Pause all survives a restart (Plan DL Decision 3). */
interface DownloadPrefs : QueuePrefs {
    /** Spec: Wi-Fi only by default; the user can turn it off. */
    var wifiOnly: Boolean

    /** The chosen volume's root path; null means the first (internal) volume. */
    var volumePath: String?

    /** Get asks first for a download over [LARGE_DOWNLOAD_BYTES] (Plan P2); on by default, "Don't ask again" clears it. */
    var askBeforeLarge: Boolean

    companion object {
        /** 100 MB, in the decimal units `bytesText` shows. */
        const val LARGE_DOWNLOAD_BYTES = 100L * 1000 * 1000
    }
}
