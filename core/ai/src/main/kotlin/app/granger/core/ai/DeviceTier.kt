package app.granger.core.ai

enum class DeviceTier { LIBRARY_ONLY, ASK }

object DeviceTiers {
    private const val GIB = 1024L * 1024 * 1024

    // totalMem excludes kernel and hardware carve-outs, so phones report less than the marketed RAM:
    // a "6 GB" phone reports ~5.1 GiB and an "8 GB" Pixel 6 ~6.8–7.1 GiB.
    const val ASK_MIN_BYTES: Long = 9 * GIB / 2 // 4.5 GiB → marketed 6 GB phones

    fun forTotalRam(totalBytes: Long): DeviceTier = when {
        totalBytes >= ASK_MIN_BYTES -> DeviceTier.ASK
        else -> DeviceTier.LIBRARY_ONLY
    }
}
