import CryptoKit
import Foundation
@_exported import GrangerCore

/// The Swift half of the shared core (Plan I1): what Kotlin can't reach on iOS.
public enum GrangerCoreKit {
    /// Gives the core CryptoKit's Ed25519 check, so catalog signatures verify. Call once at start; until then every
    /// catalog fails its check (the core fails closed).
    public static func start() {
        GrangerCoreEntry.shared.setCatalogVerifier { message, signature, publicKey in
            // Kotlin's Boolean crosses as KotlinBoolean in a callback.
            guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return KotlinBoolean(value: false) }
            return KotlinBoolean(value: key.isValidSignature(signature, for: message))
        }
    }
}
