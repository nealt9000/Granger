package app.granger.explore

/** What a thumbnail's file name says (Plan EX P0), shared since Plan I3: a drawing or an emblem is shown whole, never cropped. */
object ThumbnailNames {
    /** The file-name ending of a thumbnail to show whole on a plain ground (a flag, a seal, a logo, a map, a diagram). */
    const val WHOLE_SUFFIX = ".w.jpg"

    fun isWhole(thumbnail: String?): Boolean = thumbnail?.endsWith(WHOLE_SUFFIX) == true
}
