package com.spruceid.mobile.sdk

import android.content.Context
import android.util.Base64
import android.util.Log
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.toolkit.StorageManagerException
import com.spruceid.mobile.toolkit.StorageManagerInterface
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class StorageManager(val context: Context) : StorageManagerInterface {
    /// Function: add
    ///
    /// Adds a key-value pair to storage.  Should the key already exist, the value will be
    /// replaced.
    ///
    /// Arguments:
    /// key - The key to add
    /// value - The value to add under the key
    override suspend fun add(key: String, value: ByteArray) {
        val encryptedValue = encryptHybrid(value)
        context.openFileOutput(filename(key), 0).use {
            it.write(encryptedValue)
            it.close()
        }
    }

    /// Function: get
    ///
    /// Retrieves the value from storage identified by key.
    ///
    /// Reads both stored formats: hybrid values, and legacy `<iv>;<ciphertext>` values, which
    /// SDK versions before 0.11.2 wrote and which are never rewritten.
    ///
    /// Returns null if no value is stored under the key. Throws
    /// StorageManagerException.CouldNotDecryptValue if the stored value is in neither format or
    /// cannot be decrypted.
    ///
    /// Arguments:
    /// key - The key to retrieve
    override suspend fun get(key: String): ByteArray? {
        val bytes: ByteArray
        try {
            context.openFileInput(filename(key)).use {
                bytes = it.readBytes()
                it.close()
            }
        } catch (e: FileNotFoundException) {
            return null
        }

        try {
            return if (isHybridFormat(bytes)) {
                decryptHybrid(bytes)
            } else {
                decrypt(bytes)
            }
        } catch (e: Exception) {
            // The key is not logged: a scoped key embeds the scope, which can identify the user.
            Log.w(TAG, "could not decrypt a stored value: ${e.javaClass.name}")
            throw StorageManagerException.CouldNotDecryptValue()
        }
    }

    /// Function: remove
    ///
    /// Removes a key-value pair from storage by key.
    ///
    /// Arguments:
    /// key - The key to remove
    override suspend fun remove(key: String) {
        File(context.filesDir, filename(key)).delete()
    }

    /// Function: list
    ///
    /// Lists all key-value pair in storage
    override suspend fun list(): List<String> {
        val list = context.filesDir.list() ?: throw Exception("cannot list stored objects")

        return list.mapNotNull {
            if (it.startsWith(FILENAME_PREFIX)) {
                it.substring(FILENAME_PREFIX.length + 1)
            } else {
                null
            }
        }
    }

    companion object {
        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        private const val KEY_NAME = "sprucekit/datastore"
        private const val HYBRID_PREFIX = "HYBRID|"
        private const val TAG = "sprucekit"

        /// Function: isHybridFormat
        ///
        /// Efficiently detects if the encrypted data uses hybrid format by checking the prefix.
        /// Only converts the minimum necessary bytes to string for performance.
        ///
        /// Arguments:
        /// bytes - The encrypted data to analyze
        ///
        /// Returns:
        /// true if hybrid format, false if not hybrid format
        private fun isHybridFormat(bytes: ByteArray): Boolean {
            if (bytes.size < HYBRID_PREFIX.length) {
                return false
            }

            val prefixBytes = bytes.sliceArray(0 until HYBRID_PREFIX.length)
            val prefixString = try {
                prefixBytes.decodeToString()
            } catch (e: Exception) {
                return false
            }

            return prefixString == HYBRID_PREFIX
        }

        /// Function: encryptHybrid
        ///
        /// Encrypts large payloads using hybrid encryption (envelope pattern).
        /// Android Keystore protects only a 32-byte AES key, data is encrypted with software AES.
        ///
        /// Arguments:
        /// value - The byte array value to be encrypted
        private suspend fun encryptHybrid(value: ByteArray): ByteArray {
            return suspendCoroutine { continuation ->
                val keyManager = KeyManager()
                if (!keyManager.keyExists(KEY_NAME)) {
                    keyManager.generateEncryptionKey(KEY_NAME)
                }

                // Generate random 256-bit AES key for data encryption
                val dataKey = ByteArray(32)
                SecureRandom().nextBytes(dataKey)

                // Encrypt payload with software AES using data key
                val (dataIv, encryptedData) = keyManager.encryptWithDirectKey(dataKey, value)

                // Encrypt data key with Android Keystore
                val (keyIv, encryptedDataKey) = keyManager.encryptPayload(KEY_NAME, dataKey)

                // Combine everything in format: HYBRID|<key_iv>|<encrypted_key>|<data_iv>|<encrypted_data>
                val keyIvB64 = Base64.encodeToString(keyIv, B64_FLAGS)
                val encKeyB64 = Base64.encodeToString(encryptedDataKey, B64_FLAGS)
                val dataIvB64 = Base64.encodeToString(dataIv, B64_FLAGS)
                val encDataB64 = Base64.encodeToString(encryptedData, B64_FLAGS)

                val result = "HYBRID|$keyIvB64|$encKeyB64|$dataIvB64|$encDataB64".toByteArray()
                continuation.resume(result)
            }
        }

        /// Function: decrypt
        ///
        /// Decrypts the legacy `<iv>;<ciphertext>` format.
        /// Throws an Exception if the value does not have exactly two parts, a part is not valid
        /// Base64, or decryption fails, so that `get` can report it as CouldNotDecryptValue.
        ///
        /// Arguments:
        /// value - The byte array to be decrypted
        private suspend fun decrypt(value: ByteArray): ByteArray {
            return suspendCoroutine { continuation ->
                val keyManager = KeyManager()
                if (!keyManager.keyExists(KEY_NAME)) {
                    throw Exception("Cannot retrieve values before creating encryption keys")
                }
                val decoded = value.decodeToString().split(";")
                if (decoded.size != 2) {
                    throw Exception("Invalid legacy format - expected 2 parts, got ${decoded.size}")
                }
                val iv = Base64.decode(decoded[0], B64_FLAGS)
                val encrypted = Base64.decode(decoded[1], B64_FLAGS)
                val decrypted = keyManager.decryptPayload(KEY_NAME, iv, encrypted)
                continuation.resume(decrypted)
            }
        }

        /// Function: decryptHybrid
        ///
        /// Decrypts hybrid format for maximum performance.
        /// Only the 32-byte AES key is decrypted with Android Keystore, data with software AES.
        /// Throws if the value is not in the hybrid format or cannot be decrypted.
        ///
        /// Arguments:
        /// value - The byte array to be decrypted
        private suspend fun decryptHybrid(value: ByteArray): ByteArray {
            return suspendCoroutine { continuation ->
                val keyManager = KeyManager()
                if (!keyManager.keyExists(KEY_NAME)) {
                    throw Exception("Cannot retrieve values before creating encryption keys")
                }

                // Parse hybrid format: HYBRID|<key_iv>|<encrypted_key>|<data_iv>|<encrypted_data>
                val dataString = value.decodeToString()
                if (!dataString.startsWith(HYBRID_PREFIX)) {
                    throw Exception("Not hybrid format - doesn't start with $HYBRID_PREFIX")
                }

                val parts = dataString.split("|")
                if (parts.size != 5) {
                    throw Exception("Invalid hybrid format - expected 5 parts, got ${parts.size}")
                }

                val keyIv = Base64.decode(parts[1], B64_FLAGS)
                val encryptedKey = Base64.decode(parts[2], B64_FLAGS)
                val dataIv = Base64.decode(parts[3], B64_FLAGS)
                val encryptedData = Base64.decode(parts[4], B64_FLAGS)

                // Decrypt data key with Android Keystore
                val dataKey = keyManager.decryptPayload(KEY_NAME, keyIv, encryptedKey)

                // Decrypt payload with software AES
                val decryptedData =
                    keyManager.decryptWithDirectKey(dataKey, dataIv, encryptedData)

                continuation.resume(decryptedData)
            }
        }

        private const val FILENAME_PREFIX = "sprucekit:datastore"

        private fun filename(filename: String) = "$FILENAME_PREFIX:$filename"
    }
}
