package app.granger.core.media

/** Manifests shaped like the recorded archive.org and LibriVox items (core/archive/src/test/resources). */
object ManifestFixtures {
    const val HOLST = "holst-the-planets-holst-london-symphony-orchestra-1922-1924"

    /** The Holst item's seven VBR MP3 names, exactly as archive.org lists them (track 01–07, no per-file title or album). */
    val HOLST_NAMES = listOf(
        "The Planets Op. 32, H. 125_ I. Mars, The Bringer of War.mp3",
        "The Planets Op. 32, H. 125_ II. Venus, The Bringer of Peace [B09zijg_gQ4].mp3",
        "The Planets Op. 32, H. 125_ III. Mercury, The Winged Messenger.mp3",
        "The Planets Op. 32, H. 125_ IV. Jupiter, The Bringer of Jollity.mp3",
        "The Planets Op. 32, H. 125_ V. Saturn, The Bringer of Old Age.mp3",
        "The Planets Op. 32, H. 125_ VI. Uranus, The Magician.mp3",
        "The Planets Op. 32, H. 125_ VII. Neptune, The Mystic.mp3",
    )

    fun file(ordinal: Int, remoteName: String, sizeBytes: Long, title: String = "", trackNo: Int? = ordinal + 1,
             readers: List<String> = emptyList(), sectionId: String? = null, durationMs: Long? = null) = ManifestFile(
        name = MediaManifests.localName(ordinal, title, remoteName), remoteName = remoteName,
        url = "https://archive.org/download/x/$remoteName", sizeBytes = sizeBytes, hash = "%040x".format(ordinal),
        hashAlgorithm = "SHA1", title = title, trackNo = trackNo, readers = readers, durationMs = durationMs, sectionId = sectionId,
    )

    fun holst(sizeBytes: Long = 1_000L) = MediaManifest(
        source = MediaManifest.ARCHIVE_ORG, kind = MediaManifest.ALBUM, ref = HOLST, archiveIdentifier = HOLST,
        pageUrl = "https://archive.org/details/$HOLST", title = HOLST, creators = listOf("Gustav Holst"),
        date = "2025-03-01", files = HOLST_NAMES.mapIndexed { i, n -> file(i, n, sizeBytes) }, gotAt = 1L,
    )

    fun timeMachine(sections: Int = 3, sizeBytes: Long = 1_000L) = MediaManifest(
        source = MediaManifest.LIBRIVOX, kind = MediaManifest.AUDIOBOOK, ref = "817", archiveIdentifier = "time_machine_0805_librivox",
        pageUrl = "https://librivox.org/the-time-machine-by-h-g-wells/", title = "Time Machine", creators = listOf("H. G. Wells"),
        readers = listOf("Kristin Hughes", "Mark Nelson"), description = "A Victorian scientist travels.", language = "English",
        genres = listOf("Science Fiction"), totalTimeMs = 15_000_000L,
        cover = ManifestFile("cover.jpg", "Time_Machine_1112.jpg", "https://archive.org/download/x/Time_Machine_1112.jpg", 4L, "%040x".format(99), "SHA1"),
        files = (0 until sections).map { i ->
            file(i, "timemachine_%02d_wells_64kb.mp3".format(i + 1), sizeBytes, title = "Chapter %02d".format(i + 1),
                readers = listOf(if (i % 2 == 0) "Kristin Hughes" else "Mark Nelson"), sectionId = "${4000 + i}",
                durationMs = 600_000L + i)
        },
        gotAt = 2L,
    )
}
