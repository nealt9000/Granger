package app.granger.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which edge of the screen the rail (the toolbar) sits on. */
enum class RailSide { LEFT, RIGHT }

/** Where the app opens after a normal close (Plan H2): where you left off (the default), or Home. */
enum class OpenTo { RESUME, HOME }

/** Field (light) or Night (dark), or whichever the phone is in (Plan T1, Decision 1). */
enum class ThemeChoice {
    SYSTEM, FIELD, NIGHT;

    /** Whether this choice is Night, given whether the phone is in dark mode. */
    fun night(phoneDark: Boolean): Boolean = when (this) {
        SYSTEM -> phoneDark
        FIELD -> false
        NIGHT -> true
    }
}

/** Settings → Appearance. */
interface AppearancePrefs {
    /** Left by default. */
    val railSide: StateFlow<RailSide>
    fun setRailSide(side: RailSide)

    /** [OpenTo.RESUME] by default. */
    val openTo: StateFlow<OpenTo>
    fun setOpenTo(openTo: OpenTo)

    /** [ThemeChoice.SYSTEM] by default. */
    val theme: StateFlow<ThemeChoice>
    fun setTheme(theme: ThemeChoice)
}

class SharedAppearancePrefs(private val prefs: SharedPreferences) : AppearancePrefs {
    constructor(context: Context) : this(context.getSharedPreferences("appearance", Context.MODE_PRIVATE))

    private val state = MutableStateFlow(
        prefs.getString(KEY_RAIL_SIDE, null)?.let { stored -> RailSide.entries.firstOrNull { it.name == stored } } ?: RailSide.LEFT,
    )

    override val railSide: StateFlow<RailSide> = state

    override fun setRailSide(side: RailSide) {
        prefs.edit().putString(KEY_RAIL_SIDE, side.name).apply()
        state.value = side
    }

    private val openToState = MutableStateFlow(
        prefs.getString(KEY_OPEN_TO, null)?.let { stored -> OpenTo.entries.firstOrNull { it.name == stored } } ?: OpenTo.RESUME,
    )

    override val openTo: StateFlow<OpenTo> = openToState

    override fun setOpenTo(openTo: OpenTo) {
        prefs.edit().putString(KEY_OPEN_TO, openTo.name).apply()
        openToState.value = openTo
    }

    private val themeState = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { stored -> ThemeChoice.entries.firstOrNull { it.name == stored } } ?: ThemeChoice.SYSTEM,
    )

    override val theme: StateFlow<ThemeChoice> = themeState

    override fun setTheme(theme: ThemeChoice) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        themeState.value = theme
    }

    private companion object {
        const val KEY_RAIL_SIDE = "railSide"
        const val KEY_OPEN_TO = "openTo"
        const val KEY_THEME = "theme"
    }
}
