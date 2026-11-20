package app.granger.explore

import androidx.annotation.StringRes
import app.granger.R

/**
 * The Explore enums' labels as Android string resources (Plan I3 Task 4: the shared enums carry the resources' names,
 * which the iPhone's String Catalog uses as keys). Generated from the enums when they moved; a test checks every name.
 */
@get:StringRes
val Subject.label: Int get() = when (this) {
    Subject.HISTORY -> R.string.explore_subject_history
    Subject.SOCIETY -> R.string.explore_subject_society
    Subject.SCIENCE -> R.string.explore_subject_science
    Subject.PLACES -> R.string.explore_subject_places
    Subject.NATURE -> R.string.explore_subject_nature
    Subject.HEALTH -> R.string.explore_subject_health
    Subject.TECHNOLOGY -> R.string.explore_subject_technology
    Subject.BUSINESS -> R.string.explore_subject_business
    Subject.ART -> R.string.explore_subject_art
    Subject.LITERATURE -> R.string.explore_subject_literature
    Subject.MUSIC -> R.string.explore_subject_music
    Subject.FILM -> R.string.explore_subject_film
    Subject.SPORTS -> R.string.explore_subject_sports
    Subject.GAMES -> R.string.explore_subject_games
    Subject.RELIGION -> R.string.explore_subject_religion
    Subject.LANGUAGES -> R.string.explore_subject_languages
    Subject.FOOD -> R.string.explore_subject_food
}

@get:StringRes
val Era.label: Int get() = when (this) {
    Era.PREHISTORY -> R.string.explore_era_prehistory
    Era.ANCIENT -> R.string.explore_era_ancient
    Era.MIDDLE_AGES -> R.string.explore_era_middle_ages
    Era.EARLY_MODERN -> R.string.explore_era_early_modern
    Era.C19 -> R.string.explore_era_c19
    Era.WARS -> R.string.explore_era_wars
    Era.RECENT -> R.string.explore_era_recent
}

@get:StringRes
val Era.span: Int get() = when (this) {
    Era.PREHISTORY -> R.string.explore_era_prehistory_span
    Era.ANCIENT -> R.string.explore_era_ancient_span
    Era.MIDDLE_AGES -> R.string.explore_era_middle_ages_span
    Era.EARLY_MODERN -> R.string.explore_era_early_modern_span
    Era.C19 -> R.string.explore_era_c19_span
    Era.WARS -> R.string.explore_era_wars_span
    Era.RECENT -> R.string.explore_era_recent_span
}
