package app.granger.explore

/** How the library map arranges the library around its centre (mockup C's tabs; Places waits for the Atlas, LL6). */
enum class MapArrange { SUBJECTS, TIME }

/** Where the library map is centred: the whole library, one subject, or one era. */
sealed interface MapCentre {
    data object Library : MapCentre
    data class OfSubject(val subject: Subject) : MapCentre
    data class OfEra(val era: Era) : MapCentre
}

/** One circle on the library map: a subject, an era or an article, with its count when it stands for many. */
data class MapSpot(val key: String, val title: String, val picture: String?, val count: String? = null, val weight: Double = 1.0)
