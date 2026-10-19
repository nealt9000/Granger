package app.granger.core.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Calls [onConnected] when a wired headset or Bluetooth A2DP device connects (spec §6, "autoplay on headset
 * connect", off by default). The setting and the "something loaded and paused" check are the engine's
 * ([PlayerEngine.onHeadsetConnected]). Devices already connected when [start] runs don't count: the system reports
 * them right after registration, and that first report is only remembered.
 */
class HeadsetAutoplay(context: Context, private val onConnected: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var primed = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!primed) { primed = true; return }
            if (addedDevices.any { it.isSink && it.type in HEADSETS }) onConnected()
        }
    }

    fun start() = audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private companion object {
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
    }
}
