package com.spruceid.mobilesdkexample.utils

import com.spruceid.mobile.sdk.rs.Crypto
import com.spruceid.mobile.sdk.rs.VerificationResult
import java.security.PublicKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

class CryptoImpl : Crypto {
    override fun p256Verify(
        certificateDer: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): VerificationResult {
        try {
            var certificate: Certificate =
                CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream())
            var publicKey: PublicKey = certificate.publicKey
            var verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload)
            if (verifier.verify(signature)) {
                return VerificationResult.Success
            } else {
                return VerificationResult.Failure("signature could not be verified")
            }
        } catch (e: Throwable) {
            return VerificationResult.Failure(e.toString())
        }
    }
}