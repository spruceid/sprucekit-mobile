package com.spruceid.mobile.sdk

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.spruceid.mobile.sdk.rs.CryptoCurveUtils
import com.spruceid.mobile.sdk.rs.KeyAlias
import com.spruceid.mobile.sdk.rs.KeyStore as SpruceKitKeyStore
import com.spruceid.mobile.sdk.rs.SigningKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Implementation of the secure key management with Strongbox and TEE as backup.
 */
class KeyManager: SpruceKitKeyStore {

    /**
     * Returns the Android Keystore.
     * @return instance of the key store.
     */
    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
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

    /**
     * Resets the Keystore by removing all of the keys.
     */
    fun reset() {
        val ks = getKeyStore()
        ks.aliases().iterator().forEach {
            ks.deleteEntry(it)
        }
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with Strongbox when
     * min SDK and hardware requirements are met, otherwise using TEE.
     * @property id of the secret key.
     * @returns KeyManagerEnvironment indicating the environment used to generate the key.
     */
    fun generateSigningKey(id: String): KeyManagerEnvironment {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                generateSigningKeyWithStrongbox(id)

                return KeyManagerEnvironment.Strongbox
            } else {
                generateSigningKeyTEE(id)

                return KeyManagerEnvironment.TEE
            }
        } catch (e: Exception) {
            generateSigningKeyTEE(id)

            return KeyManagerEnvironment.TEE
        }
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with Strongbox.
     * @property id of the secret key.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateSigningKeyWithStrongbox(id: String) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )
    
        val spec = KeyGenParameterSpec.Builder(
            id,
            KeyProperties.PURPOSE_SIGN
                    or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setIsStrongBoxBacked(true)
            .build()
    
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /**
     * Generates a secp256r1 signing key by id/alias in the Keystore with TEE.
     * @property id of the secret key.
     */
    private fun generateSigningKeyTEE(id: String) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        )

        val spec = KeyGenParameterSpec.Builder(
            id,
            KeyProperties.PURPOSE_SIGN
                    or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    /**
     * Assumes the value above 32 will always be 33.
     * BigInteger will add an extra byte to keep the number positive.
     * But the key values will always be 32 bytes.
     * @property input byte array to be processed.
     * @return byte array with 32 bytes.
     */
    fun clampOrFill(input: ByteArray): ByteArray {
        return if (input.size > 32) {
            input.drop(1).toByteArray()
        } else if (input.size < 32) {
            List(32 - input.size){ 0.toByte() }.toByteArray() + input
        } else {
            input
        }
    }

    /**
     * Returns a JWK for a particular secret key by key id.
     * @property id of the secret key.
     * @return the JWK as a string.
     */
    fun getJwk(id: String): String? {
        val ks = getKeyStore()
        val key = ks.getEntry(id, null)
    
        if (key is KeyStore.PrivateKeyEntry) {
            if (key.certificate.publicKey is ECPublicKey) {
                val ecPublicKey = key.certificate.publicKey as ECPublicKey
                val x = Base64.encodeToString(
                    clampOrFill(ecPublicKey.w.affineX.toByteArray()),
                    Base64.URL_SAFE
                            xor Base64.NO_PADDING
                            xor Base64.NO_WRAP
                )
                val y = Base64.encodeToString(
                    clampOrFill(ecPublicKey.w.affineY.toByteArray()),
                    Base64.URL_SAFE
                            xor Base64.NO_PADDING
                            xor Base64.NO_WRAP
                )
    
                return """{"kty":"EC","crv":"P-256","alg":"ES256","x":"$x","y":"$y"}"""
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
     * Signs the provided payload with a SHA256withECDSA private key.
     * @property id of the secret key.
     * @property payload to be signed.
     * @return the signed payload.
     */
    fun signPayload(id: String, payload: ByteArray): ByteArray? {
        val ks = getKeyStore()
        val entry: KeyStore.Entry = ks.getEntry(id, null)
        if (entry !is KeyStore.PrivateKeyEntry) {
            Log.w("KEYMAN", "Not an instance of a PrivateKeyEntry")
            return null
        }
    
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
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
    private fun generateEncryptionKeyWithStrongbox(id: String) {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
    
        val spec = KeyGenParameterSpec.Builder(
            id,
            KeyProperties.PURPOSE_ENCRYPT
                    or KeyProperties.PURPOSE_DECRYPT,
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
    private fun generateEncryptionKeyWithTEE(id: String) {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )

        val spec = KeyGenParameterSpec.Builder(
            id,
            KeyProperties.PURPOSE_ENCRYPT
                    or KeyProperties.PURPOSE_DECRYPT,
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
     * @property id  of the secret key.
     * @property iv initialization vector.
     * @property payload to be encrypted.
     * @return the decrypted payload.
     */
    fun decryptPayload(id: String, iv: ByteArray, payload: ByteArray): ByteArray {
        val secretKey = getSecretKey(id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(payload)
    }

    override fun getSigningKey(alias: KeyAlias): SigningKey {
        val jwk = this.getJwk(alias) ?: throw Error("key not found");
        return P256SigningKey(alias, jwk)
    }
}

class P256SigningKey(private val alias: String, private val jwk: String) : SigningKey {

    override fun jwk(): String = this.jwk

    override fun sign(payload: ByteArray): ByteArray {
        val derSignature = KeyManager().signPayload(alias, payload) ?: throw Error("key not found");
        return CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(derSignature) ?:
            throw Error("signature encoding not recognized");
    }
}
