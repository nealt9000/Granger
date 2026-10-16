package app.granger.core.catalog

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/** Ed25519 via BouncyCastle: the JCA only has Ed25519 from API 33, and minSdk is 29. */
object CatalogSignature {
    /** [signature] is a `.sig` file: the base64 of a 64-byte signature, surrounding whitespace allowed. */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        return try {
            val sig = Base64.getDecoder().decode(signature.decodeToString().trim())
            if (sig.size != 64) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(sig)
        } catch (e: Exception) {
            false
        }
    }
}
