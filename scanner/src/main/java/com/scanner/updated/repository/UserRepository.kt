package com.scanner.updated.repository

import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.newrelic.agent.android.NewRelic
import com.scanner.model.User
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.Keys.USER_COLLECTION_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single-responsibility data layer for all Firebase Firestore and Storage operations.
 *
 * Every method is a suspending function returning [Result] so callers never need to handle
 * exceptions directly — a failure is always represented as [Result.failure] with the original
 * [Throwable] attached.
 *
 * Designed to be instantiated once per [UpdatedScannerActivity] (or via a ViewModel) and
 * injected wherever Firebase access is needed. No Android lifecycle references are held.
 *
 * @param storagePath  Root path inside Firebase Storage under which all fingerprint files are
 *                     stored, e.g. `"biometrics/"`. Must end with a `/`.
 */
internal class UserRepository(
    private val storagePath: String = "biometrics/",
) {
    private val tag = "UserRepository"
    private val db = Firebase.firestore
    private val storageRef = Firebase.storage.reference

    // ──────────────────────────────────────────────────────────────────────────
    // User CRUD
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [User] document by its unique identifier.
     *
     * @param uniqueId  The document ID in the Firestore `users` collection.
     * @return [Result] containing the [User] if found, null if the document doesn't exist, or
     *         a failure on network/permission errors.
     */
    suspend fun getUser(uniqueId: String): Result<User?> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = db.collection(USER_COLLECTION_PATH).document(uniqueId).get().await()
            snapshot.toObject(User::class.java)
                .also { logDebug("$tag getUser($uniqueId) → ${if (it != null) "found" else "not found"}") }
        }.onFailure {
            NewRelic.recordHandledException(it as Exception)
            logError("$tag getUser($uniqueId) → ${it.message}")
        }
    }

    /**
     * Creates or overwrites a [User] document using the user's [User.uniqueId] as the document ID.
     *
     * @param user  The user data to persist. [User.uniqueId] must not be null.
     * @return [Result.success] on write, [Result.failure] on error.
     */
    suspend fun saveUser(user: User): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = user.uniqueId ?: return@withContext Result.failure(
            IllegalArgumentException("User.uniqueId must not be null")
        )
        runCatching {
            db.collection(USER_COLLECTION_PATH).document(uid).set(user).await()
            logDebug("$tag saveUser($uid) → OK")
        }.onFailure {
            NewRelic.recordHandledException(it as Exception)
            logError("$tag saveUser($uid) → ${it.message}")
        }
    }

    /**
     * Partially updates an existing [User] document with the provided key-value map.
     *
     * Use this to update individual fields (e.g. fingerprint paths, sync status) without
     * overwriting the entire document.
     *
     * @param uniqueId   Document ID of the user to update.
     * @param fields     Map of field names → new values (supports dot-notation for nested fields).
     * @return [Result.success] on write, [Result.failure] on error.
     */
    suspend fun updateUserFields(uniqueId: String, fields: Map<String, Any>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                db.collection(USER_COLLECTION_PATH).document(uniqueId).update(fields).await()
                logDebug("$tag updateUserFields($uniqueId) → OK (fields: ${fields.keys})")
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag updateUserFields($uniqueId) → ${it.message}")
            }
        }

    /**
     * Queries the `users` collection by matching multiple key-value pairs.
     * All conditions are combined with AND logic.
     *
     * @param queryMap   Map of field names → expected values.
     * @param source     Firestore source (SERVER, CACHE, or DEFAULT).
     * @return [Result] containing the matching [QuerySnapshot], or a failure on error.
     */
    suspend fun queryUsers(
        queryMap: Map<String, Any>,
        source: Source = Source.DEFAULT,
    ): Result<QuerySnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val filters = queryMap.map { (key, value) -> Filter.equalTo(key, value) }
            val combined = Filter.and(*filters.toTypedArray())
            db.collection(USER_COLLECTION_PATH).where(combined).get(source).await()
                .also { logDebug("$tag queryUsers() → ${it.size()} results") }
        }.onFailure {
            NewRelic.recordHandledException(it as Exception)
            logError("$tag queryUsers() → ${it.message}")
        }
    }

    /**
     * Fetches all users from the local Firestore cache whose fingerprints have not yet been
     * synced to cloud storage (i.e. [User.fingerPrintSyncedOnCloud] == false).
     *
     * @return [Result] containing a list of [DocumentSnapshot], or a failure on error.
     */
    suspend fun getUnsyncedUsersFromCache(): Result<List<DocumentSnapshot>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = db.collection(USER_COLLECTION_PATH)
                    .whereEqualTo("fingerPrintSyncedOnCloud", false)
                    .get(Source.CACHE)
                    .await()
                snapshot.documents
                    .also { logDebug("$tag getUnsyncedUsersFromCache() → ${it.size} docs") }
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag getUnsyncedUsersFromCache() → ${it.message}")
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Storage: template files
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Uploads a local template file to Firebase Storage under `<storagePath><uniqueId>/<filename>`.
     *
     * After both template files for a user are uploaded, [updateUserFields] is called
     * automatically to mark the user as cloud-synced and record the cloud paths.
     *
     * The upload is idempotent — uploading the same file twice will overwrite the previous version.
     *
     * @param uniqueId   Unique user identifier; used as the storage sub-folder.
     * @param fileUri    Local [Uri] of the template file to upload.
     * @return [Result] containing the storage path of the uploaded file, or a failure on error.
     */
    suspend fun uploadTemplateFile(uniqueId: String, fileUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val remotePath = "$storagePath$uniqueId/${fileUri.lastPathSegment}"
                val fileRef = storageRef.child(remotePath)
                fileRef.putFile(fileUri).await()
                logDebug("$tag uploadTemplateFile($uniqueId) → $remotePath")
                remotePath
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag uploadTemplateFile($uniqueId) → ${it.message}")
            }
        }

    /**
     * Uploads a BMP image file to Firebase Storage.
     * Only called when the host app enables BMP export via [com.scanner.utils.builder.BuilderOptions].
     *
     * @param uniqueId   Unique user identifier; used as the storage sub-folder.
     * @param fileUri    Local [Uri] of the BMP file to upload.
     * @return [Result] containing the storage path of the uploaded file, or a failure on error.
     */
    suspend fun uploadBmpFile(uniqueId: String, fileUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val remotePath = "$storagePath$uniqueId/${fileUri.lastPathSegment}"
                storageRef.child(remotePath).putFile(fileUri).await()
                logDebug("$tag uploadBmpFile($uniqueId) → $remotePath")
                remotePath
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag uploadBmpFile($uniqueId) → ${it.message}")
            }
        }

    /**
     * Downloads all template files for [uniqueId] from Firebase Storage into [destDir].
     *
     * Existing files in [destDir] are overwritten. The download is sequential (one file at a time)
     * to avoid overwhelming the device network stack.
     *
     * @param uniqueId  Unique user identifier; used as the storage sub-folder.
     * @param destDir   Local directory where downloaded files will be written.
     * @return [Result] containing the number of files downloaded, or a failure on error.
     */
    suspend fun downloadTemplatesForUser(uniqueId: String, destDir: File): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                destDir.mkdirs()
                val listResult = storageRef.child("$storagePath$uniqueId/").listAll().await()
                var count = 0
                for (item in listResult.items) {
                    val localFile = File(destDir, item.name)
                    item.getFile(localFile).await()
                    count++
                    logDebug("$tag downloadTemplatesForUser → downloaded ${item.name}")
                }
                logDebug("$tag downloadTemplatesForUser($uniqueId) → $count files")
                count
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag downloadTemplatesForUser($uniqueId) → ${it.message}")
            }
        }

    /**
     * Deletes all template files in Firebase Storage for [uniqueId].
     * Useful when re-registering a user or retrying after low-quality scans.
     *
     * @param uniqueId  Unique user identifier whose cloud files should be removed.
     * @return [Result] containing the number of deleted files, or a failure on error.
     */
    suspend fun deleteCloudTemplatesForUser(uniqueId: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val listResult = storageRef.child("$storagePath$uniqueId/").listAll().await()
                var count = 0
                for (item in listResult.items) {
                    item.delete().await()
                    count++
                }
                logDebug("$tag deleteCloudTemplatesForUser($uniqueId) → deleted $count files")
                count
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag deleteCloudTemplatesForUser($uniqueId) → ${it.message}")
            }
        }

    /**
     * Checks whether the cloud storage folder for [uniqueId] contains exactly [expectedCount]
     * files. Used to determine whether a user's fingerprints are fully synced.
     *
     * @return [Result] containing true if the item count matches [expectedCount], false otherwise.
     */
    suspend fun isCloudStorageComplete(uniqueId: String, expectedCount: Int = 2): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val items = storageRef.child("$storagePath$uniqueId/").listAll().await().items
                (items.size == expectedCount).also {
                    logDebug("$tag isCloudStorageComplete($uniqueId) → ${items.size} items (expected $expectedCount) → $it")
                }
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag isCloudStorageComplete($uniqueId) → ${it.message}")
            }
        }

    /**
     * Marks a user's fingerprints as cloud-synced and records the uploaded file paths.
     * Called after all template uploads for a user have completed.
     *
     * @param uniqueId       Unique user identifier.
     * @param cloudPaths     List of storage paths that were uploaded.
     * @param localPathCount Total number of local template files (for the `fingerPrintCount` field).
     */
    suspend fun markUserAsSynced(
        uniqueId: String,
        cloudPaths: List<String>,
        localPathCount: Int,
    ): Result<Unit> = updateUserFields(
        uniqueId,
        mapOf(
            "fingerPrintSyncedOnCloud" to true,
            "fingerPrintCloudPath" to cloudPaths,
            "fingerPrintCount" to localPathCount,
        ),
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Offline-first background sync
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to upload local template files for any users that are stored in the Firestore
     * cache with `fingerPrintSyncedOnCloud == false`.
     *
     * This is a best-effort background operation; individual file failures are logged but do not
     * abort the overall sync loop.
     *
     * @param localStorageDir  Root directory containing per-user sub-folders of template files.
     *                         Each sub-folder should be named with the user's unique ID.
     */
    suspend fun syncPendingUploads(localStorageDir: File) = withContext(Dispatchers.IO) {
        val unsyncedResult = getUnsyncedUsersFromCache()
        if (unsyncedResult.isFailure) {
            logError("$tag syncPendingUploads() → could not fetch unsynced users")
            return@withContext
        }

        val documents = unsyncedResult.getOrDefault(emptyList())
        logDebug("$tag syncPendingUploads() → ${documents.size} users to sync")

        for (doc in documents) {
            val user = runCatching { doc.toObject(User::class.java) }.getOrNull() ?: continue
            val uid = user.uniqueId ?: continue
            val localPaths = user.fingerPrintLocalPath ?: continue

            val uploadedPaths = mutableListOf<String>()
            for (localPath in localPaths) {
                val file = File(localPath)
                if (!file.exists()) continue
                uploadTemplateFile(uid, Uri.fromFile(file))
                    .onSuccess { remotePath -> uploadedPaths.add(remotePath) }
                    .onFailure { logError("$tag syncPendingUploads() → upload failed for $localPath: ${it.message}") }
            }

            if (uploadedPaths.size == localPaths.size) {
                markUserAsSynced(uid, uploadedPaths, localPaths.size)
                    .onFailure { logError("$tag syncPendingUploads() → markAsSynced failed for $uid") }
            }
        }
    }
}
