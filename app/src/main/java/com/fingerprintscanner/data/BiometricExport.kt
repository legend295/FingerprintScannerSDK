package com.fingerprintscanner.data

import android.content.Context
import android.util.Log
import com.scanner.updated.UpdatedFingerprintScanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hands the raw biometric artifacts to an assessor: the ISO `.dat` templates and the `.bmp`
 * images, either for one unique ID or for every ID on the terminal.
 *
 * Templates live in app-private storage, which nothing outside the app can read, so an export
 * stages copies into the app's external files directory where the FileProvider can share them.
 * Staging rather than widening the FileProvider to cover internal storage keeps everything else
 * in there — the history log, preferences — unshareable.
 */
class BiometricExport(private val context: Context) {

    private val scanner by lazy { UpdatedFingerprintScanner() }

    /** Where staged exports go. Cleared on each export so stale copies do not pile up. */
    private fun exportDir(clear: Boolean): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
        if (clear) dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        return dir
    }

    /** Every artifact for one ID, staged for sharing. Empty if that ID has none. */
    fun single(uniqueId: String): List<File> {
        val files = registrationFiles(uniqueId) + verificationFiles(uniqueId)
        if (files.isEmpty()) return emptyList()

        val dir = File(exportDir(clear = true), uniqueId).apply { mkdirs() }
        return files.mapNotNull { f ->
            runCatching {
                // Prefix with the ID so the files stay identifiable once they have been
                // detached from their folder by email or a chat app.
                File(dir, "${uniqueId}_${f.name}").also { f.copyTo(it, overwrite = true) }
            }.onFailure { Log.w(TAG, "could not stage ${f.name}: ${it.message}") }.getOrNull()
        }
    }

    /**
     * Every artifact for every registered ID, as one zip.
     *
     * A zip rather than a pile of loose files: a batch is easily dozens of items, which no
     * share target handles gracefully, and the folder structure carries the ID.
     */
    fun batchZip(): File? {
        val ids = registeredIds()
        if (ids.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(exportDir(clear = true), "fingerprint-artifacts-$stamp.zip")
        return runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                ids.forEach { id ->
                    registrationFiles(id).forEach { f ->
                        out.putNextEntry(ZipEntry("$id/registration/${f.name}"))
                        f.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    // Transaction captures kept apart in the archive too, so nobody mistakes
                    // one for the registration reference.
                    verificationFiles(id).forEach { f ->
                        out.putNextEntry(ZipEntry("$id/verifications/${f.name}"))
                        f.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            Log.i(TAG, "zipped artifacts for ${ids.size} ID(s) into ${zip.name}")
            zip
        }.onFailure { Log.w(TAG, "batch export failed: ${it.message}") }.getOrNull()
    }

    fun countFor(uniqueId: String): Int =
        registrationFiles(uniqueId).size + verificationFiles(uniqueId).size

    /** Every unique ID with registration templates on this device. */
    fun registeredIds(): List<String> = scanner.localRegisteredIds(context)

    private fun registrationFiles(uniqueId: String): List<File> =
        File(context.filesDir, uniqueId).listFiles()
            ?.filter { it.isFile && it.isartifact() }
            .orEmpty()

    private fun verificationFiles(uniqueId: String): List<File> =
        File(File(context.filesDir, VERIFICATIONS_DIR), uniqueId).listFiles()
            ?.filter { it.isFile && it.isartifact() }
            .orEmpty()

    private fun File.isartifact() =
        name.endsWith(".dat") || name.endsWith(".bmp") || name.endsWith(".jpg")

    private companion object {
        const val TAG = "BiometricExport"

        /** Must match `FingerprintReaderWrapper.VERIFICATIONS_DIR` in the scanner module. */
        const val VERIFICATIONS_DIR = "verifications"
    }
}
