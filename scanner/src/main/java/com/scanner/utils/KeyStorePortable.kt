package com.scanner.utils

import android.util.Log
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

internal object KeyStorePortable {

    private val tag = KeyStorePortable::class.java.simpleName

    private const val ITERATIONS = 65536
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    // ---------------------------------------------------------------------------
    // Key Derivation
    // ---------------------------------------------------------------------------

    /**
     * Derives a consistent AES-256 key from [bvnNumber] + [appSecret].
     * No device-specific material is used, so the same key is produced
     * on every device for the same inputs.
     */
    private fun deriveKey(bvnNumber: String, appSecret: String): SecretKeySpec {
        val salt = bvnNumber.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(
            (appSecret + bvnNumber).toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH
        )
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // ---------------------------------------------------------------------------
    // Encrypt
    // ---------------------------------------------------------------------------

    /**
     * Encrypts [data] using AES-GCM.
     *
     * The returned [ByteArray] is structured as:
     *   [ IV (12 bytes) | Ciphertext + GCM Auth Tag ]
     *
     * Save this entire blob to your .dat file — the IV travels with the file
     * so no SharedPreferences or external storage is needed.
     *
     * @param data      Raw fingerprint template bytes to encrypt.
     * @param bvnNumber The user's BVN used to derive the encryption key.
     * @param appSecret Your app-level secret (e.g. BuildConfig.KEY).
     * @return          IV-prefixed ciphertext blob ready to write to disk.
     */
    fun encryptData(
        data: ByteArray,
        bvnNumber: String,
        appSecret: String
    ): ByteArray {
        val secretKey = deriveKey(bvnNumber, appSecret)

        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE, iv))
        val ciphertext = cipher.doFinal(data)

        // Prepend IV so decryption is self-contained from just the file
        return iv + ciphertext
    }

    // ---------------------------------------------------------------------------
    // Decrypt
    // ---------------------------------------------------------------------------

    /**
     * Decrypts a file that was encrypted with [encryptData].
     *
     * Reads the file at [filePath], extracts the first 12 bytes as the IV,
     * then decrypts the remainder.
     *
     * @param filePath  Absolute path to the encrypted .dat file.
     * @param bvnNumber The user's BVN — must match the one used during encryption.
     * @param appSecret Your app-level secret — must match the one used during encryption.
     * @return          Decrypted plaintext bytes, or null if decryption fails.
     */
    fun decryptData(
        filePath: String,
        bvnNumber: String,
        appSecret: String
    ): ByteArray? {
        return try {
            val blob = File(filePath).readBytes()

            if (blob.size <= IV_SIZE) {
                Log.e(tag, "decryptData -> file too small to contain IV: $filePath")
                return null
            }

            val iv = blob.copyOfRange(0, IV_SIZE)
            val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)

            val secretKey = deriveKey(bvnNumber, appSecret)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE, iv))

            try {
                cipher.doFinal(ciphertext)
            } catch (e: AEADBadTagException) {
                Log.e(tag, "decryptData -> AEADBadTagException (wrong key or corrupted file): ${e.message}")
                null
            }

        } catch (e: Exception) {
            Log.e(tag, "decryptData -> Exception: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------

    /**
     * Extracts the reader number suffix from a file name formatted as:
     *   yyyy-MM-dd-HH-mm-ss{readerNo}-ISO-Template.dat
     *
     * Example:
     *   "2026-02-18-15-34-481-ISO-Template.dat" → "1"
     *
     * @return The reader number string, or null if the file name is malformed.
     */
    fun extractReaderNumber(filePath: String): String? {
        val suffix = "-ISO-Template.dat"
        val fileName = filePath.substringAfterLast("/")
        if (!fileName.endsWith(suffix)) return null
        val dateAndReaderNo = fileName.removeSuffix(suffix)
        val dateLength = 19
        return if (dateAndReaderNo.length > dateLength) {
            dateAndReaderNo.substring(dateLength)
        } else {
            null
        }
    }
}
