package com.scanner.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptWrapper(private val context: Context) {

    val sharedPrefs = context.getSharedPreferences(
        "${context.packageName}.EncryptedDataPrefs",
        Context.MODE_PRIVATE
    )
    @Throws(Exception::class)
    fun generateSecretKey(): SecretKey? {
        val secureRandom = SecureRandom()
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
        //generate a key with secure random
        keyGenerator?.init(128, secureRandom)
        return keyGenerator?.generateKey()
    }

    fun saveSecretKey(secretKey: SecretKey): String {
        val encodedKey = Base64.encodeToString(secretKey.encoded, Base64.NO_WRAP)
        sharedPrefs.edit().putString("3ksd3ssdsd2", encodedKey).apply()
        return encodedKey
    }

    /*@Throws(Exception::class)
    fun readFile(filePath: String): ByteArray {
        val file = File(filePath)
        val fileContents = file.readBytes()
        val inputBuffer = BufferedInputStream(
            FileInputStream(file)
        )

        inputBuffer.read(fileContents)
        inputBuffer.close()

        return fileContents
    }*/

    @Throws(Exception::class)
    fun encrypt(yourKey: SecretKey, fileData: ByteArray): ByteArray {
        val data = yourKey.encoded
        val skeySpec = SecretKeySpec(data, 0, data.size, "AES")
        val cipher = Cipher.getInstance("AES", "BC")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            skeySpec,
            IvParameterSpec(ByteArray(cipher.getBlockSize()))
        )
        return cipher.doFinal(fileData)
    }

    fun encryptDownloadedFile() {
        /*try {
            val filePath = filesDir.absolutePath + "/filename"
            val fileData = readFile(filePath)

            //get secret key
            val secretKey = getSecretKey(sharedPref)
            //encrypt file
            val encodedData = encrypt(secretKey, fileData)

            saveFile(encodedData, filePath)

        } catch (e: Exception) {
            Log.d(mTag, e.message)
        }*/
    }

    fun getSecretKey(): SecretKey {
        val key = sharedPrefs.getString("3ksd3ssdsd2", null)

        if (key == null) {
            //generate secure random
            val secretKey = generateSecretKey()
            saveSecretKey(secretKey!!)
            return secretKey
        }

        val decodedKey = Base64.decode(key, Base64.NO_WRAP)
        val originalKey = SecretKeySpec(decodedKey, 0, decodedKey.size, "AES")

        return originalKey
    }

    @Throws(Exception::class)
    fun decrypt(yourKey: SecretKey, fileData: ByteArray): ByteArray {
        val decrypted: ByteArray
        val cipher = Cipher.getInstance("AES", "BC")
        cipher.init(Cipher.DECRYPT_MODE, yourKey, IvParameterSpec(ByteArray(cipher.blockSize)))
        decrypted = cipher.doFinal(fileData)
        return decrypted
    }

    private fun decryptEncryptedFile(): ByteArray? {
//        val filePath = /*filesDir.absolutePath + */"/filename"
//        val fileData = readFile(filePath)
//        val secretKey = getSecretKey()
//        return decode(secretKey, fileData)
        return null
    }
}