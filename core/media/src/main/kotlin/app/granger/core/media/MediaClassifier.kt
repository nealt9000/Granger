package app.granger.core.media

/** Music or audiobook (spec §3.1). */
object MediaClassifier {
    fun isAudiobook(f: FoundFile): Boolean =
        f.systemAudiobook || f.inAudiobookFolder || f.displayName.endsWith(".m4b", ignoreCase = true) ||
            f.folder.split('/').any { it.equals("Audiobooks", ignoreCase = true) }
}
