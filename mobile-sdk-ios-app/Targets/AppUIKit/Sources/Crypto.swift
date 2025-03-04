import Foundation
import SpruceIDMobileSdkRs

class CryptoImpl: Crypto {
    func p256Verify(certificateDer: Data, payload: Data, signature: Data) -> SpruceIDMobileSdkRs.VerificationResult {
        guard let certificate = SecCertificateCreateWithData(nil, certificateDer as CFData) else {
            return .failure(cause: "unable to parse certificate")
        }
        guard let pk = SecCertificateCopyKey(certificate) else {
            return .failure(cause: "unable to extract public key from certificate")
        }
        var error: Unmanaged<CFError>?
        let algorithm: SecKeyAlgorithm = SecKeyAlgorithm.ecdsaSignatureMessageX962SHA256
        guard SecKeyVerifySignature(pk, algorithm, payload as CFData, signature as CFData, &error) else {
            return .failure(cause: "signature verification failed: \(error.debugDescription)")
        }
        return .success
    }
}
