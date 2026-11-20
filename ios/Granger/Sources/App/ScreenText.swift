import Foundation

/// The English words for the string keys the shared models use (Subject and Era labels, UiText keys): Android's
/// strings, written by tools/ios/make_text_ids.py. Templates keep Android's %1$s / %1$d placeholders; `ScreenText.text`
/// fills them.
enum ScreenText {
    static let strings: [String: String] = [
        "explore_era_ancient": "Ancient world",
        "explore_era_ancient_span": "3000 BC – AD 500",
        "explore_era_c19": "The 1800s",
        "explore_era_c19_span": "1800 – 1900",
        "explore_era_early_modern": "Renaissance to 1800",
        "explore_era_early_modern_span": "1500 – 1800",
        "explore_era_middle_ages": "Middle Ages",
        "explore_era_middle_ages_span": "500 – 1500",
        "explore_era_prehistory": "Prehistory",
        "explore_era_prehistory_span": "before 3000 BC",
        "explore_era_recent": "Since 1946",
        "explore_era_recent_span": "1946 – now",
        "explore_era_wars": "1900 to 1945",
        "explore_era_wars_span": "two world wars",
        "explore_subject_art": "Art & architecture",
        "explore_subject_business": "Business & economy",
        "explore_subject_film": "Film & TV",
        "explore_subject_food": "Food & farming",
        "explore_subject_games": "Games",
        "explore_subject_health": "Health & medicine",
        "explore_subject_history": "History",
        "explore_subject_languages": "Languages",
        "explore_subject_literature": "Books & writing",
        "explore_subject_music": "Music",
        "explore_subject_nature": "Nature & animals",
        "explore_subject_places": "Places",
        "explore_subject_religion": "Philosophy & religion",
        "explore_subject_science": "Science & maths",
        "explore_subject_society": "Society & politics",
        "explore_subject_sports": "Sports",
        "explore_subject_technology": "Technology",
    ]

    static let plurals: [String: [String: String]] = [
        :
    ]
}
