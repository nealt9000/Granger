package app.granger.core.catalog

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal actual fun platformVerifier(): Ed25519Verifier = Ed25519Verifier { message, signature, publicKey ->
    val verifier = Ed25519Signer()
    verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
    verifier.update(message, 0, message.size)
    verifier.verifySignature(signature)
}
