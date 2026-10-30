package app.granger.core.speech

import android.content.Context

/**
 * How voices are named in the lists, as format strings: [of] reads them from the module's resources. [BARE], the names
 * alone with no words around them, is the default for code (mostly tests) that never shows a title.
 */
class VoiceTitles(private val granger: String, private val system: String, private val systemDefault: String) {
    /** "Granger voice (Maggie)". */
    fun granger(name: String): String = granger.format(name)

    /** "System voice iom (en-US)", or "System voice default (en-US)" when [short] is null. */
    fun system(short: String?, languageTag: String): String =
        if (short == null) systemDefault.format(languageTag) else system.format(short, languageTag)

    companion object {
        fun of(context: Context): VoiceTitles = VoiceTitles(
            context.getString(R.string.speech_granger_voice),
            context.getString(R.string.speech_system_voice),
            context.getString(R.string.speech_system_voice_default),
        )

        val BARE = VoiceTitles("%1\$s", "%1\$s (%2\$s)", "(%1\$s)")
    }
}
