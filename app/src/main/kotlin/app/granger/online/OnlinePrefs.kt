package app.granger.online

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Online's first rows (Plan O2 Task 2; builder's call 5): Offline pictures (spec §9, Neal 2026-11-18: whether
 * what you keep and what's saved for now carries its pictures) and Saved for now's cap.
 */
class OnlinePrefs(context: Context) {
    enum class OfflinePictures { SAVE, TEXT_ONLY }

    private val prefs = context.applicationContext.getSharedPreferences("online", Context.MODE_PRIVATE)

    private val pictures = MutableStateFlow(runCatching { OfflinePictures.valueOf(prefs.getString(PICTURES, null) ?: "") }.getOrDefault(OfflinePictures.SAVE))
    val offlinePictures: StateFlow<OfflinePictures> = pictures.asStateFlow()

    private val cap = MutableStateFlow(prefs.getInt(CAP, DEFAULT_CAP_MB))
    val savedCapMb: StateFlow<Int> = cap.asStateFlow()

    fun setOfflinePictures(value: OfflinePictures) {
        prefs.edit().putString(PICTURES, value.name).apply()
        pictures.value = value
    }

    fun setSavedCapMb(mb: Int) {
        prefs.edit().putInt(CAP, mb).apply()
        cap.value = mb
    }

    companion object {
        const val DEFAULT_CAP_MB = 300
        val CAP_CHOICES = listOf(100, 300, 1000)
        private const val PICTURES = "offline_pictures"
        private const val CAP = "saved_cap_mb"
    }
}
