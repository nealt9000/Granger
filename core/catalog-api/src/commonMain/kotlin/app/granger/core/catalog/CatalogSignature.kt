package app.granger.core.catalog

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Checks an Ed25519 signature: BouncyCastle on Android (the JCA has Ed25519 only from API 33); CryptoKit on iOS. */
fun interface Ed25519Verifier {
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

object CatalogSignature {
    /**
     * Who checks signatures. On iOS Kotlin can't reach CryptoKit (Swift only) and Apple's C crypto has no Ed25519, so
     * the app sets this at start with a CryptoKit `Curve25519.Signing` check (Plan I1, builder's call 4). Until then
     * every signature fails: a catalog is never trusted unchecked.
     */
    var verifier: Ed25519Verifier = platformVerifier()

    /** [signature] is a `.sig` file: the base64 of a 64-byte signature, surrounding whitespace allowed. */
    @OptIn(ExperimentalEncodingApi::class)
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        return try {
            val sig = Base64.decode(signature.decodeToString().trim())
            if (sig.size != 64) return false
            verifier.verify(data, sig, publicKey)
        } catch (e: Exception) {
            false
        }
    }
}

internal expect fun platformVerifier(): Ed25519Verifier
