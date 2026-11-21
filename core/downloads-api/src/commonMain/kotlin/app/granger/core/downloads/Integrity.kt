package app.granger.core.downloads

enum class Integrity {
    VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING;

    companion object {
        /**
         * The size check Readiness makes of a one-file download (spec "Readiness"), on both phones. A voice is a zip
         * unpacked into a folder: Android's IntegrityChecker checks that itself.
         */
        fun quickFile(record: DownloadRecord): Integrity = when {
            !Files.isFile(record.filePath) -> MISSING
            Files.size(record.filePath) != record.sizeBytes -> INCOMPLETE
            // A pack added from a file the catalog doesn't list has no checksum to pass (Plan E1 item 4).
            record.hash.startsWith(UNVERIFIED_PREFIX) -> SIZE_OK
            record.verifiedAt != null -> VERIFIED
            else -> SIZE_OK
        }
    }
}
