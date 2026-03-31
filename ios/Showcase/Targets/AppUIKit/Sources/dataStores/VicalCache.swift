import Foundation
import SpruceIDMobileSdkRs

/// Caches VICAL bytes in UserDefaults and provides trust anchor loading.
///
/// The VICAL (Verified Issuer Certificate Authority List) contains IACA root
/// certificates from US states participating in mDL. This cache persists the
/// raw CBOR bytes between app launches so the Rust SDK can skip the network
/// fetch on subsequent calls.
enum VicalCache {
    private static let bytesKey = "cached_vical_bytes"

    static var bytes: Data? {
        get { UserDefaults.standard.data(forKey: bytesKey) }
        set { UserDefaults.standard.set(newValue, forKey: bytesKey) }
    }

    /// Build trust anchor PEM strings by fetching/parsing the AAMVA VICAL
    /// and merging with additional IACA certificates.
    ///
    /// - Must be called off the main thread (makes blocking network requests).
    /// - Falls back to cached VICAL bytes if the network fetch fails.
    /// - Falls back to only `additionalPems` if both network and cache are unavailable.
    static func loadTrustAnchors(additionalPems: [String]) -> [String] {
        do {
            let result = try fetchAndBuildTrustAnchors(
                cachedVicalBytes: bytes,
                additionalIacaPems: additionalPems
            )

            if let updated = result.updatedVicalBytes {
                bytes = updated
            }

            return result.trustAnchorPems
        } catch {
            print("VICAL fetch failed, using local certs only: \(error)")
            return additionalPems
        }
    }
}
