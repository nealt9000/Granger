package app.granger

/**
 * A sidecar file on the phone ([path]) and the sha256 of the pack file it must have been built from (and that file's
 * name). Shared since Plan I5 Task 2, for the reference links; Android also builds one from a `File`.
 */
data class InstalledSidecar(val path: String, val packSha256: String, val packName: String? = null)
