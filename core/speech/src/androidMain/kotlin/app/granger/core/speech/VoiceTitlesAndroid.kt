package app.granger.core.speech

import android.content.Context

/** The voice titles in the phone's language, from this module's resources. */
fun VoiceTitles.Companion.of(context: Context): VoiceTitles = VoiceTitles(
    context.getString(R.string.speech_granger_voice),
    context.getString(R.string.speech_system_voice),
    context.getString(R.string.speech_system_voice_default),
)
