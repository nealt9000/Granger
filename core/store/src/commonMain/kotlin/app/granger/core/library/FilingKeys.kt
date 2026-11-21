package app.granger.core.library

/** The Kiwix folder's key and name, which the database's migrations write (Plan I3: shared with `Filing`). */
object FilingKeys {
    const val FOLDER_KEY = "kiwix"
    const val FOLDER_NAME = "Kiwix"

    /** Granger's book collections by knowledge language (Plan L3; shared with `Filing` for Get more, Plan I4b Task 6). */
    const val GRANGER_CLASSICS = "granger-classics"
    const val CLASICOS_GRANGER = "clasicos-granger"
    val GRANGER_COLLECTIONS = mapOf("en" to GRANGER_CLASSICS, "es" to CLASICOS_GRANGER)
}
