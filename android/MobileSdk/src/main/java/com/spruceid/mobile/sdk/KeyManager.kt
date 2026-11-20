package com.spruceid.mobile.sdk

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.annotation.RequiresApi
import com.spruceid.mobile.sdk.rs.CryptoCurveUtils
import com.spruceid.mobile.sdk.rs.Jwk
import com.spruceid.mobile.toolkit.CryptoException
import com.spruceid.mobile.toolkit.KeyAlias
import com.spruceid.mobile.toolkit.KeyStore as SpruceKitKeyStore
import com.spruceid.mobile.toolkit.SigningKey
import com.spruceid.mobile.sdk.rs.coseKeyEc2P256PublicKey
import com.spruceid.mobile.sdk.rs.jwkFromPublicP256
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Implementation of the secure key management with Strongbox and TEE as backup. */
class KeyManager : SpruceKitKeyStore {

    private companion object {
        /** GCM standard 96-bit (12-byte) initialization vector. */
        const val GCM_IV_LENGTH = 12

        /** GCM 128-bit authentication tag. */
        const val GCM_TAG_LENGTH_BITS = 128

        val secureRandom = SecureRandom()
    }

    /**
     * Returns the Android Keystore.
     * @return instance of the key store.
     */
    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    /**
     * Returns a secret key - based on the id of the key.
     * @property id of the secret key.
     * @return the public secret key interface object.
     */
    private fun getSecretKey(id: String): SecretKey? {
        val ks = getKeyStore()

        val entry: KeyStore.Entry = ks.getEntry(id, null)
        if (entry !is KeyStore.SecretKeyEntry) {
            Log.w("KEYMAN", "Not an instance of a SecretKeyEntry")
            return null
        }

        return entry.secretKey
    }

    /** Resets the Keystore by removing all of the keys. */
    fun reset() {
        val ks = getKeyStore()
        ks.aliases().iterator().forEach { ks.deleteEntry(it) }
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with Strongbox when min SDK and
     * hardware requirements are met, otherwise using TEE.
     * @property id of the secret key.
     * @property userAuthentication when set, the key can only sign shortly after the user
     * authenticates (see [UserAuthenticationPolicy]). If the device can't satisfy the policy, a
     * [UserAuthenticationUnavailableException] is thrown; no key without the policy is created.
     * @returns KeyManagerEnvironment indicating the environment used to generate the key.
     */
    fun generateSigningKey(
        id: String,
        challenge: ByteArray? = null,
        userAuthentication: UserAuthenticationPolicy? = null,
    ): KeyManagerEnvironment {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                generateSigningKeyWithStrongbox(id, challenge, userAuthentication)

                return KeyManagerEnvironment.Strongbox
            } else {
                generateSigningKeyTEE(id, challenge, userAuthentication)

                return KeyManagerEnvironment.TEE
            }
        } catch (e: Exception) {
            if (userAuthentication == null) {
                generateSigningKeyTEE(id, challenge)

                return KeyManagerEnvironment.TEE
            }
            if (e is UserAuthenticationUnavailableException) {
                throw e
            }
            // The Strongbox failure may be the policy (no secure lock screen, no strong
            // biometric) or Strongbox itself. The TEE attempt carries the same policy, so it
            // either succeeds with it or fails for the same reason; it never drops the policy.
            try {
                generateSigningKeyTEE(id, challenge, userAuthentication)
            } catch (teeError: Exception) {
                if (teeError is UserAuthenticationUnavailableException) {
                    teeError.addSuppressed(e)
                    throw teeError
                }
                throw UserAuthenticationUnavailableException(id, teeError).apply { addSuppressed(e) }
            }

            return KeyManagerEnvironment.TEE
        }
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with Strongbox.
     * @property id of the secret key.
     * @property userAuthentication see [generateSigningKey].
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun generateSigningKeyWithStrongbox(
        id: String,
        challenge: ByteArray? = null,
        userAuthentication: UserAuthenticationPolicy? = null,
    ) {
        val generator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )

        val spec =
            KeyGenParameterSpec.Builder(
                id,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setIsStrongBoxBacked(true)
                .apply {
                    if (challenge != null) {
                        setAttestationChallenge(challenge)
                    }
                    userAuthentication?.applyTo(this)
                }
                .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with TEE.
     * @property id of the secret key.
     * @property userAuthentication see [generateSigningKey].
     */
    fun generateSigningKeyTEE(
        id: String,
        challenge: ByteArray? = null,
        userAuthentication: UserAuthenticationPolicy? = null,
    ) {
        val generator =
            KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )

        val spec =
            KeyGenParameterSpec.Builder(
                id,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .apply {
                    if (challenge != null) {
                        setAttestationChallenge(challenge)
                    }
                    userAuthentication?.applyTo(this)
                }
                .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /**
     * Assumes the value above 32 will always be 33. BigInteger will add an extra byte to keep the
     * number positive. But the key values will always be 32 bytes.
     * @property input byte array to be processed.
     * @return byte array with 32 bytes.
     */
    fun clampOrFill(input: ByteArray): ByteArray {
        return if (input.size > 32) {
            input.drop(1).toByteArray()
        } else if (input.size < 32) {
            List(32 - input.size) { 0.toByte() }.toByteArray() + input
        } else {
            input
        }
    }

    /**
     * Returns a JWK for a particular secret key by key id.
     * @property id of the secret key.
     * @return the JWK.
     */
    fun getJwk(id: String): Jwk? {
        val ks = getKeyStore()
        val key = ks.getEntry(id, null)

        if (key is KeyStore.PrivateKeyEntry) {
            if (key.certificate.publicKey is ECPublicKey) {
                val ecPublicKey = key.certificate.publicKey as ECPublicKey
                val x = clampOrFill(ecPublicKey.w.affineX.toByteArray())
                val y = clampOrFill(ecPublicKey.w.affineY.toByteArray())
                return jwkFromPublicP256(x, y)
            }
        }

        return null
    }

    /**
     * Returns the given public key as a JWK. Generates a new key if it doesn't exist.
     * @property id of the key pair.
     * @property userAuthentication applied only when a new key is generated; an existing key
     * keeps the protection it was created with.
     * @return the JWK.
     */
    fun getOrInsertJwk(id: String, userAuthentication: UserAuthenticationPolicy? = null): Jwk {
        if (!keyExists(id)) {
            generateSigningKey(id, userAuthentication = userAuthentication)
        }

        return getJwk(id)!!
    }

    /**
     *
     * Returns the public key as CBOR-encoded COSE key.
     */
    fun coseKeyEc2P256PubKey(id: String): ByteArray? {
        val ks = getKeyStore()
        val key = ks.getEntry(id, null)

        if (key is KeyStore.PrivateKeyEntry) {
            if (key.certificate.publicKey is ECPublicKey) {
                val ecPublicKey = key.certificate.publicKey as ECPublicKey
                val x = clampOrFill(ecPublicKey.w.affineX.toByteArray())
                val y = clampOrFill(ecPublicKey.w.affineY.toByteArray())

                return coseKeyEc2P256PublicKey(x, y, id.toByteArray())
            }
        }

        return null
    }

    /**
     *  Returns the certificate corresponding to the key ID provided
     */
    fun keyCertificateChain(id: String): List<ByteArray>? {
        val ks = getKeyStore()
        val key = ks.getEntry(id, null)

        if (key is KeyStore.PrivateKeyEntry) {
            if (key.certificate.publicKey is ECPublicKey) {
                return key.certificateChain.map { it.encoded }
            }
        }

        return null
    }

    /**
     * Checks to see if a key already exists.
     * @property id of the secret key.
     * @return indication if the key exists.
     */
    fun keyExists(id: String): Boolean {
        val ks = getKeyStore()
        return ks.containsAlias(id) && ks.isKeyEntry(id)
    }

    /**
     * Checks whether a key was generated with a [UserAuthenticationPolicy].
     * @property id of the key pair.
     * @return true if the key needs a recent user authentication to sign, false if it doesn't or
     * if no private key exists under [id].
     */
    fun isUserAuthenticationRequired(id: String): Boolean {
        val entry = getKeyStore().getEntry(id, null) as? KeyStore.PrivateKeyEntry ?: return false
        val factory = KeyFactory.getInstance(entry.privateKey.algorithm, "AndroidKeyStore")
        return factory.getKeySpec(entry.privateKey, KeyInfo::class.java).isUserAuthenticationRequired
    }

    /**
     * Signs the provided payload with a SHA256withECDSA private key.
     * @property id of the secret key.
     * @property payload to be signed.
     * @return DER-encoded signature
     * @throws UserAuthenticationRequiredException if the key requires user authentication and the
     * user hasn't authenticated within its validity window. Authenticate (for example with
     * [authenticateUser]) and retry.
     * @throws UserAuthenticationUnavailableException if the key was permanently invalidated (the
     * secure lock screen was removed or the enrolled biometrics changed).
     */
    fun signPayload(id: String, payload: ByteArray): ByteArray? {
        val ks = getKeyStore()
        val entry: KeyStore.Entry = ks.getEntry(id, null)
        if (entry !is KeyStore.PrivateKeyEntry) {
            Log.w("KEYMAN", "Not an instance of a PrivateKeyEntry")
            return null
        }

        return Signature.getInstance("SHA256withECDSA").run {
            try {
                initSign(entry.privateKey)
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw UserAuthenticationUnavailableException(
                    id, e, UserAuthenticationUnavailableException.INVALIDATED_DETAIL
                )
            } catch (e: UserNotAuthenticatedException) {
                throw UserAuthenticationRequiredException(id, e)
            }
            update(payload)
            sign()
        }
    }

    /**
     * Generates an AES encryption key with a provided id in the Keystore.
     * @property id of the secret key.
     * @returns KeyManagerEnvironment indicating the environment used to generate the key.
     */
    fun generateEncryptionKey(id: String): KeyManagerEnvironment {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                generateEncryptionKeyWithStrongbox(id)

                return KeyManagerEnvironment.Strongbox
            } else {
                generateEncryptionKeyWithTEE(id)

                return KeyManagerEnvironment.TEE
            }
        } catch (e: Exception) {
            generateEncryptionKeyWithTEE(id)

            return KeyManagerEnvironment.TEE
        }
    }

    /**
     * Generates an AES encryption key with a provided id in the Keystore.
     * @property id of the secret key.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun generateEncryptionKeyWithStrongbox(id: String) {
        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )

        val spec =
            KeyGenParameterSpec.Builder(
                id,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setIsStrongBoxBacked(true)
                .build()

        generator.init(spec)
        generator.generateKey()
    }

    /**
     * Generates an AES encryption key with a provided id in the Keystore.
     * @property id of the secret key.
     */
    fun generateEncryptionKeyWithTEE(id: String) {
        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )

        val spec =
            KeyGenParameterSpec.Builder(
                id,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()

        generator.init(spec)
        generator.generateKey()
    }

    /**
     * Encrypts payload by a key referenced by key id.
     * @property id of the secret key.
     * @property payload to be encrypted.
     * @return initialization vector with the encrypted payload.
     */
    fun encryptPayload(id: String, payload: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = getSecretKey(id)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(payload)
        return Pair(iv, encrypted)
    }

    /**
     * Decrypts the provided payload by a key id and initialization vector.
     * @property id of the secret key.
     * @property iv initialization vector.
     * @property payload to be encrypted.
     * @return the decrypted payload.
     */
    fun decryptPayload(id: String, iv: ByteArray, payload: ByteArray): ByteArray {
        val secretKey = getSecretKey(id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(payload)
    }

    /**
     * Encrypts data using envelope encryption pattern with direct AES/GCM. This method is much
     * faster for large payloads than hardware security modules.
     *
     * Reference:https://cloud.google.com/kms/docs/envelope-encryption
     *
     * @property key 32-byte AES key (data encryption key)
     * @property data Data to encrypt
     * @return Pair<IV, EncryptedData>
     */
    fun encryptWithDirectKey(key: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")

        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val encrypted = cipher.doFinal(data)

        return Pair(iv, encrypted)
    }

    /**
     * Decrypts data using envelope encryption pattern with direct AES/GCM. This method is much
     * faster for large payloads than hardware security modules.
     *
     * Reference: https://cloud.google.com/kms/docs/envelope-encryption
     *
     * @property key 32-byte AES key (data encryption key)
     * @property iv Initialization vector
     * @property encryptedData Data to decrypt
     * @return Decrypted data
     */
    fun decryptWithDirectKey(key: ByteArray, iv: ByteArray, encryptedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encryptedData)
    }

    // CryptoException is the error type the Rust KeyStore trait declares; any other throwable
    // crossing the callback makes the Rust side panic.
    override fun getSigningKey(alias: KeyAlias): SigningKey {
        val jwk = this.getJwk(alias) ?: throw CryptoException.General("key '$alias' not found")
        return P256SigningKey(alias, jwk.toString())
    }
}

class P256SigningKey(private val alias: String, private val jwk: String) : SigningKey {

    override fun jwk(): String = this.jwk

    /**
     * Signs [payload]. When the key requires user authentication and the user hasn't
     * authenticated recently, throws a [CryptoException] whose message starts with
     * [UserAuthenticationRequiredException.MESSAGE_PREFIX]; when the key was permanently
     * invalidated, with [UserAuthenticationUnavailableException.MESSAGE_PREFIX]. The marker
     * survives the trip through Rust to the caller of the flow.
     *
     * Only throws [CryptoException], the error type the Rust trait declares: any other throwable
     * crossing the callback makes the Rust side panic.
     */
    override fun sign(payload: ByteArray): ByteArray {
        val derSignature = try {
            KeyManager().signPayload(alias, payload)
        } catch (e: UserAuthenticationRequiredException) {
            throw CryptoException.General(e.message ?: UserAuthenticationRequiredException.MESSAGE_PREFIX)
        } catch (e: UserAuthenticationUnavailableException) {
            throw CryptoException.General(e.message ?: UserAuthenticationUnavailableException.MESSAGE_PREFIX)
        } catch (e: Exception) {
            throw CryptoException.General("Could not sign with key '$alias': $e")
        } ?: throw CryptoException.General("key '$alias' not found")
        return CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(derSignature)
            ?: throw CryptoException.General("signature encoding from key '$alias' not recognized")
    }
}
