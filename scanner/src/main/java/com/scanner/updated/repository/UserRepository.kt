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
import com.scanner.model.Transaction
import com.scanner.model.User
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.Keys.TRANSACTION_COLLECTION_PATH
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

    private companion object {
        /**
         * Cloud sub-folder for verification captures. Mirrors the on-device layout written by
         * [com.scanner.updated.reader.FingerprintReaderWrapper].
         */
        const val VERIFICATIONS_DIR = "verifications"
    }

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

    /**
     * The BMP counterpart of [getUnsyncedUsersFromCache]: users whose raw BMP exports are still
     * owed to the cloud (i.e. [User.fingerPrintBmpSyncedOnCloud] == false).
     *
     * A separate query rather than a filter on the template sweep, because the two states are
     * independent — a user's templates can be fully synced while their BMPs are not, and vice
     * versa. Users with no BMPs leave the field null, which `whereEqualTo(..., false)` does not
     * match, so they never enter this result set.
     *
     * @return [Result] containing a list of [DocumentSnapshot], or a failure on error.
     */
    suspend fun getUnsyncedBmpUsersFromCache(): Result<List<DocumentSnapshot>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = db.collection(USER_COLLECTION_PATH)
                    .whereEqualTo("fingerPrintBmpSyncedOnCloud", false)
                    .get(Source.CACHE)
                    .await()
                snapshot.documents
                    .also { logDebug("$tag getUnsyncedBmpUsersFromCache() → ${it.size} docs") }
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag getUnsyncedBmpUsersFromCache() → ${it.message}")
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Transactions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Appends a [Transaction] record to the `transaction` collection under an auto-generated
     * document ID.
     *
     * The collection is append-only — each successful verification adds one document, so a user
     * accumulates a transaction history rather than overwriting a single record.
     *
     * @param transaction  The record to write.
     * @return [Result] containing the generated document ID, or a failure on error.
     */
    suspend fun saveTransaction(transaction: Transaction): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val doc = db.collection(TRANSACTION_COLLECTION_PATH).document()
                doc.set(transaction).await()
                logDebug("$tag saveTransaction(${transaction.bvnNumber}) → ${doc.id}")
                doc.id
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag saveTransaction(${transaction.bvnNumber}) → ${it.message}")
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
     * Uploads a per-transaction verification capture to
     * `<storagePath><uniqueId>/verifications/<filename>`.
     *
     * Deliberately a sub-folder rather than the user's registration folder: [listAll] returns
     * only the direct children of a prefix, so keeping these one level down means they cannot be
     * miscounted by [isCloudStorageComplete] or pulled down as templates by
     * [downloadTemplatesForUser].
     *
     * @param uniqueId  Unique user identifier; used as the storage sub-folder.
     * @param fileUri   Local [Uri] of the capture (JPEG or raw BMP).
     * @return [Result] containing the storage path of the uploaded file, or a failure on error.
     */
    suspend fun uploadVerificationCapture(uniqueId: String, fileUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val remotePath = "$storagePath$uniqueId/$VERIFICATIONS_DIR/${fileUri.lastPathSegment}"
                storageRef.child(remotePath).putFile(fileUri).await()
                logDebug("$tag uploadVerificationCapture($uniqueId) → $remotePath")
                remotePath
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag uploadVerificationCapture($uniqueId) → ${it.message}")
            }
        }

    /**
     * Downloads the template files for [uniqueId] from Firebase Storage into [destDir].
     *
     * Only files carrying [Constant.TEMPLATE_FILE_SUFFIX] are fetched. The user's Storage folder
     * also holds their BMP exports, and pulling those down would spend a slow connection on
     * megabytes of imagery the verification flow never reads — while inflating the returned count
     * that the caller uses to decide whether the enrolment arrived.
     *
     * Existing files in [destDir] are overwritten. The download is sequential (one file at a time)
     * to avoid overwhelming the device network stack.
     *
     * @param uniqueId  Unique user identifier; used as the storage sub-folder.
     * @param destDir   Local directory where downloaded files will be written.
     * @return [Result] containing the number of template files downloaded, or a failure on error.
     */
    suspend fun downloadTemplatesForUser(uniqueId: String, destDir: File): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                destDir.mkdirs()
                val listResult = storageRef.child("$storagePath$uniqueId/").listAll().await()
                var count = 0
                for (item in listResult.items.filter { it.name.endsWith(Constant.TEMPLATE_FILE_SUFFIX) }) {
                    val localFile = File(destDir, item.name)
                    item.getFile(localFile).await()
                    count++
                    logDebug("$tag downloadTemplatesForUser → downloaded ${item.name}")
                }
                logDebug("$tag downloadTemplatesForUser($uniqueId) → $count template files")
                count
            }.onFailure {
                NewRelic.recordHandledException(it as Exception)
                logError("$tag downloadTemplatesForUser($uniqueId) → ${it.message}")
            }
        }

    /**
     * Deletes every file in the user's Storage folder — templates and BMP exports alike.
     *
     * The breadth is deliberate despite the name: the caller is discarding a registration
     * (low-quality retry), and leaving the rejected pass's BMPs behind would strand imagery
     * belonging to templates that no longer exist. Verification captures survive, being one
     * level down in `verifications/`.
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
     * Deletes the Firestore user document for [uniqueId].
     *
     * Does not touch Storage — call [deleteCloudTemplatesForUser] for that. Kept separate so a
     * caller can remove the record while leaving the biometric files in place, or vice versa.
     *
     * @return [Result.success] on delete, [Result.failure] on error.
     */
    suspend fun deleteUser(uniqueId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            db.collection(USER_COLLECTION_PATH).document(uniqueId).delete().await()
            logDebug("$tag deleteUser($uniqueId) → OK")
        }.onFailure {
            NewRelic.recordHandledException(it as Exception)
            logError("$tag deleteUser($uniqueId) → ${it.message}")
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
                // Templates only. BMP exports share this folder, so counting every item made a
                // fully-enrolled user with BMPs report 4 != 2 and read as incomplete — which
                // opens the "already registered" guard and lets the BVN be re-registered.
                val items = storageRef.child("$storagePath$uniqueId/").listAll().await().items
                    .filter { it.name.endsWith(Constant.TEMPLATE_FILE_SUFFIX) }
                (items.size == expectedCount).also {
                    logDebug("$tag isCloudStorageComplete($uniqueId) → ${items.size} templates (expected $expectedCount) → $it")
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

    /**
     * Records the raw BMP exports for a user, after all BMP uploads have completed.
     *
     * The BMP counterpart of [markUserAsSynced], deliberately kept separate: BMPs are optional
     * and gated by their own flag, so their arrival says nothing about whether the *templates*
     * reached the cloud. This never touches `fingerPrintSyncedOnCloud` — that flag means
     * "the templates are safe", and letting a BMP upload set it would mark a user as synced
     * whose identity templates are still only on the device.
     *
     * @param uniqueId    Unique user identifier.
     * @param cloudPaths  Storage paths of the uploaded BMPs.
     * @param localPaths  On-device paths of those same BMPs — the paths themselves, not a count:
     *                    the field is `fingerPrintBmpLocalPath` and its template equivalent
     *                    (`fingerPrintLocalPath`) holds a list too.
     */
    suspend fun syncUserBmp(
        uniqueId: String,
        cloudPaths: List<String>,
        localPaths: List<String>,
    ): Result<Unit> = updateUserFields(
        uniqueId,
        mapOf(
            "fingerPrintBmpCloudPath" to cloudPaths,
            "fingerPrintBmpLocalPath" to localPaths,
            // Clears this user from the retry sweep — see [syncPendingBmpUploads].
            "fingerPrintBmpSyncedOnCloud" to true,
        ),
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Offline-first background sync
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs every pending-upload sweep: templates first, then raw BMPs.
     *
     * Templates lead because they are the identity — if the network only holds up long enough for
     * one of the two, it should be spent on the file that makes the user verifiable, not on an
     * optional export.
     *
     * @param localStorageDir  Root directory containing per-user sub-folders of template files.
     */
    suspend fun syncAllPendingUploads(localStorageDir: File) = withContext(Dispatchers.IO) {
        syncPendingUploads(localStorageDir)
        syncPendingBmpUploads()
    }

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

    /**
     * Retries the raw BMP uploads for any user left with `fingerPrintBmpSyncedOnCloud == false`.
     *
     * The BMP counterpart of [syncPendingUploads]. It cannot ride on that sweep: the template
     * sweep is keyed on `fingerPrintSyncedOnCloud`, and a user whose templates uploaded fine but
     * whose BMPs failed is not in it. BMP paths are absolute and recorded on the user document,
     * so unlike the template sweep this needs no local root directory.
     *
     * A file that no longer exists on disk is dropped from the record rather than retried
     * forever: the user record must not keep pointing at a path the device cannot produce. When
     * that leaves nothing to upload, the user is still cleared from the sweep — there is no work
     * left to do for them.
     *
     * Best-effort; individual failures are logged and leave the user queued for the next run.
     */
    suspend fun syncPendingBmpUploads() = withContext(Dispatchers.IO) {
        val pendingResult = getUnsyncedBmpUsersFromCache()
        if (pendingResult.isFailure) {
            logError("$tag syncPendingBmpUploads() → could not fetch users with pending BMPs")
            return@withContext
        }

        val documents = pendingResult.getOrDefault(emptyList())
        logDebug("$tag syncPendingBmpUploads() → ${documents.size} users to sync")

        for (doc in documents) {
            val user = runCatching { doc.toObject(User::class.java) }.getOrNull() ?: continue
            val uid = user.uniqueId ?: continue
            val recordedPaths = user.fingerPrintBmpLocalPath.orEmpty()

            val presentPaths = recordedPaths.filter { File(it).exists() }
            if (presentPaths.size < recordedPaths.size) {
                logError(
                    "$tag syncPendingBmpUploads() → ${recordedPaths.size - presentPaths.size} " +
                            "BMP file(s) for $uid no longer exist on disk; dropping them from the record"
                )
            }

            if (presentPaths.isEmpty()) {
                // Nothing recoverable — clear the flag so this user stops being swept every launch.
                syncUserBmp(uid, user.fingerPrintBmpCloudPath.orEmpty(), presentPaths)
                    .onFailure { logError("$tag syncPendingBmpUploads() → could not clear flag for $uid") }
                continue
            }

            val uploadedPaths = mutableListOf<String>()
            for (localPath in presentPaths) {
                uploadBmpFile(uid, Uri.fromFile(File(localPath)))
                    .onSuccess { remotePath -> uploadedPaths.add(remotePath) }
                    .onFailure { logError("$tag syncPendingBmpUploads() → upload failed for $localPath: ${it.message}") }
            }

            // Partial success stays pending: syncUserBmp() would flip the flag to true and the
            // missing BMPs would never be retried.
            if (uploadedPaths.size == presentPaths.size) {
                syncUserBmp(uid, uploadedPaths, presentPaths)
                    .onFailure { logError("$tag syncPendingBmpUploads() → syncUserBmp failed for $uid") }
            }
        }
    }
}
