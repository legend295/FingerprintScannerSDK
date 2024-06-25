package com.scanner.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.legend295.fingerprintscanner.BuildConfig
import com.scanner.app.ScannerApp
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.Exception
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore


internal object KeyStore {

    private val tag = KeyStore::class.java.simpleName

    private fun getKeyStore(): KeyStore {
        val keystore = KeyStore.getInstance("AndroidKeyStore")
        keystore.load(null)
        return keystore
    }

    fun generateKey(key: String): SecretKey {
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            key,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(false)
            .build()
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    fun checkKey() {
        // need to create key with BVN_NUMBER +  BuildConfig.KEY + 0 & 1(for 2 fingerprints)
        val key = BuildConfig.KEY
        val secretKey = getKeyStore().getKey(key, null) as SecretKey?
        if (secretKey == null)
            generateKey(key)
    }

    private fun generateIv(): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        return iv
    }

    fun Context.encryptData(data: ByteArray, callback: (ByteArray) -> Unit) {
        val key = BuildConfig.KEY
        val secretKey = getKeyStore().getKey(key, null) as SecretKey?
//        val secretKey: SecretKey? = EncryptWrapper(this).generateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = generateIv()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encryptedData = cipher.doFinal(data)
        callback(encryptedData)
        saveEncryptedData(key, iv)
    }

    fun Context.decryptData(filePath: String, callback: (ByteArray?) -> Unit) {
        try {
            val byteArray = readAllBytes(filePath)
            val key = BuildConfig.KEY
            val encryptedData = getEncryptedData(Pair(key, "$key.iv"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = getKeyStore().getKey(key, null) as SecretKey?
            secretKey ?: return
            if (encryptedData.second == null) {
                callback(null)
                return
            }
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, encryptedData.second))
            try {
//            return cipher.doFinal(encryptedData.first)
                callback(cipher.doFinal(byteArray))
            } catch (e: AEADBadTagException) {
                callback(null)
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun readAllBytes(fileName: String): ByteArray? {
        var ous: ByteArrayOutputStream? = null
        var ios: InputStream? = null
        try {
            val buffer = ByteArray(4096)
            ous = ByteArrayOutputStream()
            ios = FileInputStream(fileName)
            var read = 0
            while (ios.read(buffer).also { read = it } != -1) {
                ous.write(buffer, 0, read)
            }
        } finally {
            ous?.close()
            ios?.close()
        }
        return ous?.toByteArray()
    }

    @Throws(Exception::class)
    fun readFile(filePath: String): ByteArray {
        val file = File(filePath)
        val fileContents = file.readBytes()
        val inputBuffer = BufferedInputStream(
            FileInputStream(file)
        )

        inputBuffer.read(fileContents)
        inputBuffer.close()

        return fileContents
    }

    private fun Context.saveEncryptedData(key: String, iv: ByteArray) {
        val sharedPrefs = getSharedPreferences(
            "${ScannerApp.getInstance().packageName}.EncryptedDataPrefs",
            Context.MODE_PRIVATE
        )
        with(sharedPrefs.edit()) {
//            putString(key, Base64.encodeToString(encryptedData, Base64.DEFAULT))
            putString("$key.iv", Base64.encodeToString(iv, Base64.DEFAULT))
            apply()
        }
    }

    private fun Context.getEncryptedData(pair: Pair<String, String>): Pair<ByteArray?, ByteArray?> {
        val sharedPrefs = getSharedPreferences(
            "${ScannerApp.getInstance().packageName}.EncryptedDataPrefs",
            Context.MODE_PRIVATE
        )
        val encryptedDataString = sharedPrefs.getString(pair.first, null)
        val encryptedIvString = sharedPrefs.getString(pair.second, null)
        /* val dataByte = encryptedDataString?.let {
             Base64.decode(it, Base64.DEFAULT)
         }*/

        val ivByte = encryptedIvString?.let {
            Base64.decode(it, Base64.DEFAULT)
        }
        return Pair(null, ivByte)
    }

    fun deleteAllKeys() {
        try {
            val aliases = getKeyStore().aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                getKeyStore().deleteEntry(alias)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


}