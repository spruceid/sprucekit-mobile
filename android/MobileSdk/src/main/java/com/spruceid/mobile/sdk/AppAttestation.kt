package com.spruceid.mobile.sdk

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import org.jose4j.base64url.Base64Url
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest

/**
 * A class that handles App Attestation functionality for Android devices using Play Integrity API
 */
public class AppAttestation(private val context: Context) {

    /**
     * Exception thrown when attestation fails
     */
    public class AttestationException(
        val type: AttestationError,
        message: String? = null
    ) : Exception(message ?: type.defaultMessage)

    /**
     * Types of errors that can occur during attestation
     */
    public enum class AttestationError(val defaultMessage: String) {
        ATTESTATION_NOT_SUPPORTED("Device does not support Play Integrity"),
        INVALID_JWK("Invalid JWK format"),
        ENCODING_ERROR("Error encoding data"),
        JSON_SERIALIZATION_ERROR("Error serializing JSON"),
        KEYSTORE_ERROR("Error accessing keystore")
    }

    private val integrityManager = IntegrityManagerFactory.create(context)

    /**
     * Checks if the device supports App Attestation
     * @return Boolean indicating if attestation is supported on the current device
     */
    public fun isAttestationSupported(): Boolean {
        return GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    /**
     * Performs a complete attestation flow with the provided nonce
     * @param nonce The nonce retrieved from the verification service
     * @param keyAlias The keyAlias from your key stored on KeyStore
     * @param callback Callback to handle the result or error
     */
    public fun appAttest(nonce: String, keyAlias: String, callback: (Result<String>) -> Unit) {
        if (!isAttestationSupported()) {
            callback(Result.failure(AttestationException(AttestationError.ATTESTATION_NOT_SUPPORTED)))
            return
        }

        try {
            // Get certificate chain from AndroidKeyStore
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val certificateChain = keyStore.getCertificateChain(keyAlias)

            // Prepare encoded certificate chain
            val encodedChain = mutableListOf<String>()
            for (cert in certificateChain) {
                encodedChain.add(Base64Url.encode(cert.encoded))
            }

            // Generate nonce with certificate
            val md = MessageDigest.getInstance("SHA-256")
            md.update(nonce.toByteArray())
            md.update(certificateChain[0].encoded)
            val nonceBytes = md.digest()
            val nonceB64 = Base64Url.encode(nonceBytes)

            // Request integrity token
            val tokenRequest = IntegrityTokenRequest.builder()
                .setNonce(nonceB64)
                .build()
            
            integrityManager.requestIntegrityToken(tokenRequest)
                .addOnSuccessListener { response ->
                    try {
                        val payload = JSONObject().apply {
                            put("integrityToken", response.token())
                            put("nonce", nonce)
                            put("x5c", JSONArray(encodedChain))
                        }
                        callback(Result.success(payload.toString()))
                    } catch (e: Exception) {
                        callback(
                            Result.failure(
                                AttestationException(
                                    AttestationError.JSON_SERIALIZATION_ERROR,
                                    e.message
                                )
                            )
                        )
                    }
                }
                .addOnFailureListener { e ->
                    if (e is ApiException) {
                        callback(
                            Result.failure(
                                AttestationException(
                                    AttestationError.ENCODING_ERROR,
                                    "${CommonStatusCodes.getStatusCodeString(e.statusCode)}: ${e.message}"
                                )
                            )
                        )
                    } else {
                        callback(
                            Result.failure(
                                AttestationException(
                                    AttestationError.ENCODING_ERROR,
                                    e.message
                                )
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            callback(
                Result.failure(
                    AttestationException(
                        AttestationError.KEYSTORE_ERROR,
                        e.message
                    )
                )
            )
        }
    }
}

