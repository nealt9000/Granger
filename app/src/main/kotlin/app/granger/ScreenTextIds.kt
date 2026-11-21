package app.granger

/**
 * The Android ids for the string keys the shared models use (`UiText.Res("key")`). Written by
 * tools/ios/make_text_ids.py; `ScreenTextIdsTest` fails when a key is missing. An explicit map, not a lookup by
 * name, so resource shrinking keeps every string.
 */
object ScreenTextIds {
    fun string(key: String): Int = when (key) {
        "explore_era_ancient" -> R.string.explore_era_ancient
        "explore_era_ancient_span" -> R.string.explore_era_ancient_span
        "explore_era_c19" -> R.string.explore_era_c19
        "explore_era_c19_span" -> R.string.explore_era_c19_span
        "explore_era_early_modern" -> R.string.explore_era_early_modern
        "explore_era_early_modern_span" -> R.string.explore_era_early_modern_span
        "explore_era_middle_ages" -> R.string.explore_era_middle_ages
        "explore_era_middle_ages_span" -> R.string.explore_era_middle_ages_span
        "explore_era_prehistory" -> R.string.explore_era_prehistory
        "explore_era_prehistory_span" -> R.string.explore_era_prehistory_span
        "explore_era_recent" -> R.string.explore_era_recent
        "explore_era_recent_span" -> R.string.explore_era_recent_span
        "explore_era_wars" -> R.string.explore_era_wars
        "explore_era_wars_span" -> R.string.explore_era_wars_span
        "explore_subject_art" -> R.string.explore_subject_art
        "explore_subject_business" -> R.string.explore_subject_business
        "explore_subject_film" -> R.string.explore_subject_film
        "explore_subject_food" -> R.string.explore_subject_food
        "explore_subject_games" -> R.string.explore_subject_games
        "explore_subject_health" -> R.string.explore_subject_health
        "explore_subject_history" -> R.string.explore_subject_history
        "explore_subject_languages" -> R.string.explore_subject_languages
        "explore_subject_literature" -> R.string.explore_subject_literature
        "explore_subject_music" -> R.string.explore_subject_music
        "explore_subject_nature" -> R.string.explore_subject_nature
        "explore_subject_places" -> R.string.explore_subject_places
        "explore_subject_religion" -> R.string.explore_subject_religion
        "explore_subject_science" -> R.string.explore_subject_science
        "explore_subject_society" -> R.string.explore_subject_society
        "explore_subject_sports" -> R.string.explore_subject_sports
        "explore_subject_technology" -> R.string.explore_subject_technology
        "readiness_covers" -> R.string.readiness_covers
        "readiness_covers_slice" -> R.string.readiness_covers_slice
        "readiness_no_packs_in" -> R.string.readiness_no_packs_in
        "readiness_no_packs_in_yours" -> R.string.readiness_no_packs_in_yours
        "readiness_signal_no_essentials_for" -> R.string.readiness_signal_no_essentials_for
        "readiness_signal_nothing_for" -> R.string.readiness_signal_nothing_for
        "readiness_signal_stale" -> R.string.readiness_signal_stale
        "readiness_signal_unsearchable" -> R.string.readiness_signal_unsearchable
        "readiness_source_archive_org" -> R.string.readiness_source_archive_org
        "readiness_source_granger" -> R.string.readiness_source_granger
        "readiness_source_kiwix" -> R.string.readiness_source_kiwix
        "readiness_source_librivox" -> R.string.readiness_source_librivox
        "readiness_source_models" -> R.string.readiness_source_models
        "readiness_source_my_books" -> R.string.readiness_source_my_books
        "readiness_source_phone_media" -> R.string.readiness_source_phone_media
        "readiness_source_voices" -> R.string.readiness_source_voices
        "sources_adds_inside" -> R.string.sources_adds_inside
        "sources_adds_inside_your" -> R.string.sources_adds_inside_your
        "sources_adds_to" -> R.string.sources_adds_to
        "sources_adds_to_your" -> R.string.sources_adds_to_your
        "sources_category_drama" -> R.string.sources_category_drama
        "sources_category_games" -> R.string.sources_category_games
        "sources_category_howto" -> R.string.sources_category_howto
        "sources_category_literature" -> R.string.sources_category_literature
        "sources_category_maps" -> R.string.sources_category_maps
        "sources_category_medical" -> R.string.sources_category_medical
        "sources_category_novels" -> R.string.sources_category_novels
        "sources_category_opera" -> R.string.sources_category_opera
        "sources_category_other" -> R.string.sources_category_other
        "sources_category_philosophy" -> R.string.sources_category_philosophy
        "sources_category_poetry" -> R.string.sources_category_poetry
        "sources_category_programming" -> R.string.sources_category_programming
        "sources_category_qa" -> R.string.sources_category_qa
        "sources_category_reference" -> R.string.sources_category_reference
        "sources_category_regional" -> R.string.sources_category_regional
        "sources_category_religion" -> R.string.sources_category_religion
        "sources_category_science" -> R.string.sources_category_science
        "sources_category_scripture" -> R.string.sources_category_scripture
        "sources_dotted" -> R.string.sources_dotted
        "sources_family_devdocs" -> R.string.sources_family_devdocs
        "sources_family_maps" -> R.string.sources_family_maps
        "sources_family_stack_exchange" -> R.string.sources_family_stack_exchange
        "sources_flavour_mini" -> R.string.sources_flavour_mini
        "sources_flavour_no_pictures" -> R.string.sources_flavour_no_pictures
        "sources_flavour_standard" -> R.string.sources_flavour_standard
        "sources_flavour_with_pictures" -> R.string.sources_flavour_with_pictures
        "sources_get_books" -> R.string.sources_get_books
        "sources_licence_cc" -> R.string.sources_licence_cc
        "sources_licence_kiwix" -> R.string.sources_licence_kiwix
        "sources_licence_not_stated" -> R.string.sources_licence_not_stated
        "sources_licence_open" -> R.string.sources_licence_open
        "sources_licence_public_domain" -> R.string.sources_licence_public_domain
        "sources_licence_public_domain_us" -> R.string.sources_licence_public_domain_us
        "sources_licence_see" -> R.string.sources_licence_see
        "sources_scope_granger" -> R.string.sources_scope_granger
        "sources_scope_gutenberg" -> R.string.sources_scope_gutenberg
        "sources_scope_kiwix" -> R.string.sources_scope_kiwix
        "sources_scope_kiwix_books" -> R.string.sources_scope_kiwix_books
        "sources_scope_models" -> R.string.sources_scope_models
        "sources_set_wikipedia" -> R.string.sources_set_wikipedia
        "sources_set_wikivoyage" -> R.string.sources_set_wikivoyage
        "sources_updated" -> R.string.sources_updated
        else -> error("no Android string for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }

    fun plural(key: String): Int = when (key) {
        "sources_adds_about" -> R.plurals.sources_adds_about
        "sources_adds_about_your" -> R.plurals.sources_adds_about_your
        "sources_languages_more" -> R.plurals.sources_languages_more
        "sources_packs" -> R.plurals.sources_packs
        "sources_regions" -> R.plurals.sources_regions
        else -> error("no Android plural for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }
}
