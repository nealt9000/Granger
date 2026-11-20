package app.granger.core.catalog

/** Fails closed until the app sets [CatalogSignature.verifier] with CryptoKit. */
internal actual fun platformVerifier(): Ed25519Verifier = Ed25519Verifier { _, _, _ -> false }
