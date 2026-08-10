package com.scanner.updated

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import com.scanner.model.User
import com.scanner.updated.repository.UserRepository
import com.scanner.utils.builder.BuilderOptions
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.Constant.CUSTOM_OBJECT
import com.scanner.utils.constants.Constant.SCANNING_OPTIONS
import com.scanner.updated.reader.FingerprintReaderWrapper
import com.scanner.utils.enums.ScanningType
import org.json.JSONObject
import java.io.File

/**
 * Primary entry point for the updated Fingerprint Scanner SDK.
 *
 * This class has two distinct roles:
 *
 * **1. Launching a scan session** — use the nested [Builder] to configure and start
 * [UpdatedScannerActivity]:
 * ```kotlin
 * UpdatedFingerprintScanner.Builder(context)
 *     .setScanningType(ScanningType.REGISTRATION)
 *     .setUniqueId("123456789")
 *     .setKey("encryptionKey")
 *     .start(activity, launcher)
 * ```
 *
 * **2. Utility operations** — create an instance to call suspend functions for Firebase
 * reads/writes without touching an Activity:
 * ```kotlin
 * val scanner = UpdatedFingerprintScanner()
 * lifecycleScope.launch {
 *     val result = scanner.getUser("123456789")
 *     result.onSuccess { user -> … }
 * }
 * ```
 *
 * @param storagePath Firebase Storage folder prefix for uploaded files.
 *                    Defaults to [DEFAULT_STORAGE_PATH] (`"biometrics/"`).
 */
class UpdatedFingerprintScanner(
    storagePath: String = DEFAULT_STORAGE_PATH,
) {

    companion object {
        const val DEFAULT_STORAGE_PATH = "biometrics/"
    }

    private val repository = UserRepository(storagePath)

    // -----------------------------------------------------------------------------------------
    // Utility — Firebase / local-storage helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Retrieves a user record from Firestore by unique ID.
     *
     * @param uniqueId The user's unique identifier (e.g. BVN number).
     * @return [Result.success] wrapping the [User] when found, [Result.success] wrapping `null`
     *         when no document exists, or [Result.failure] on a network or Firestore error.
     */
    suspend fun getUser(uniqueId: String): Result<User?> =
        repository.getUser(uniqueId)

    /**
     * Writes or overwrites a user record in Firestore.
     *
     * @param user The [User] to persist.
     * @return [Result.success] on success or [Result.failure] on error.
     */
    suspend fun saveUser(user: User): Result<Unit> =
        repository.saveUser(user)

    /**
     * Queries the Firestore `users` collection for all documents that match every key-value
     * pair in [queryMap] (conditions are AND-ed).
     *
     * @param queryMap  Field name → expected value pairs used to filter documents.
     * @param source    [Source.SERVER] forces a server fetch; [Source.CACHE] uses the local
     *                  Firestore cache; `null` lets the SDK decide automatically.
     * @return [Result.success] wrapping the [QuerySnapshot] or [Result.failure] on error.
     */
    suspend fun queryUsers(
        queryMap: HashMap<String, Any>,
        source: Source = Source.DEFAULT,
    ): Result<QuerySnapshot> = repository.queryUsers(queryMap, source)

    /**
     * Merges [customData] into the `customObject` field of an existing Firestore document.
     * Only the specified keys are updated; all other document fields remain unchanged.
     *
     * @param uniqueId   The user's unique identifier.
     * @param customData Key-value pairs to merge into the `customObject` map in Firestore.
     * @return [Result.success] on success or [Result.failure] on error.
     */
    suspend fun updateCustomData(
        uniqueId: String,
        customData: Map<String, Any>,
    ): Result<Unit> = repository.updateUserFields(
        uniqueId,
        mapOf(CUSTOM_OBJECT to customData),
    )

    /**
     * Returns `true` if at least one encrypted `.dat` template file exists in local app storage
     * for the given [uniqueId].
     *
     * This is a synchronous, local-only check — no network call is made.
     *
     * @param context  Any valid [Context] used to resolve `filesDir`.
     * @param uniqueId The user's unique identifier.
     */
    fun hasLocalFiles(context: Context, uniqueId: String): Boolean {
        val dir = File(context.filesDir, uniqueId)
        return dir.exists() && dir.listFiles()?.any { it.extension == "dat" } == true
    }

    /**
     * Checks whether Firebase Storage contains the expected number of template files for the
     * given [uniqueId].
     *
     * @param uniqueId      The user's unique identifier.
     * @param expectedCount Minimum number of files that must be present (default `2`).
     * @return [Result.success] wrapping `true` when the full set is present, `false` when the
     *         set is incomplete, or [Result.failure] on a storage or network error.
     */
    suspend fun hasCloudFiles(
        uniqueId: String,
        expectedCount: Int = 2,
    ): Result<Boolean> = repository.isCloudStorageComplete(uniqueId, expectedCount)

    /**
     * Deletes all Firebase Storage files associated with [uniqueId].
     *
     * @param uniqueId The user's unique identifier.
     * @return [Result.success] wrapping the number of files deleted, or [Result.failure] on error.
     */
    suspend fun deleteCloudFiles(uniqueId: String): Result<Int> =
        repository.deleteCloudTemplatesForUser(uniqueId)

    /**
     * Lists every unique ID that has registration templates stored on this device.
     *
     * The `verifications/` subdirectory is excluded — it holds per-transaction captures, not
     * registered identities.
     *
     * @param context Any valid [Context] used to resolve `filesDir`.
     */
    fun localRegisteredIds(context: Context): List<String> =
        (context.filesDir.listFiles { f ->
            f.isDirectory && f.name != FingerprintReaderWrapper.VERIFICATIONS_DIR
        } ?: emptyArray())
            .filter { dir ->
                dir.listFiles { f -> f.name.endsWith(FingerprintReaderWrapper.TEMPLATE_SUFFIX) }
                    ?.isNotEmpty() == true
            }
            .map { it.name }
            .sorted()

    /**
     * Removes every trace of one [uniqueId]: its templates and verification captures on this
     * device, its Firebase Storage files, and its Firestore user document.
     *
     * Scoped to that ID alone — nothing else in the project is touched. There is no undo, so
     * callers should confirm with the operator first.
     *
     * @param context  Any valid [Context] used to resolve `filesDir`.
     * @param uniqueId The identifier to purge.
     * @return A human-readable summary of what was removed.
     */
    suspend fun purge(context: Context, uniqueId: String): String {
        val localFiles = listOf(
            File(context.filesDir, uniqueId),
            File(File(context.filesDir, FingerprintReaderWrapper.VERIFICATIONS_DIR), uniqueId),
        ).sumOf { dir ->
            if (!dir.isDirectory) {
                0
            } else {
                val removed = dir.listFiles()?.count { it.delete() } ?: 0
                dir.delete()
                removed
            }
        }

        val cloudFiles = repository.deleteCloudTemplatesForUser(uniqueId).getOrDefault(0)
        val docDeleted = repository.deleteUser(uniqueId).isSuccess

        return "$localFiles local file(s), $cloudFiles cloud file(s), " +
                if (docDeleted) "user record removed" else "user record NOT removed"
    }

    /**
     * Uploads any locally stored files that have not yet been synced to Firebase Storage —
     * fingerprint templates first, then any raw BMP exports still owed to the cloud.
     *
     * This is a best-effort operation: partial failures are logged internally but do not cause
     * this function to throw. Call this from a background-safe coroutine scope (e.g.
     * `lifecycleScope` or `viewModelScope`).
     *
     * @param context Any valid [Context] used to resolve the local storage directory.
     */
    suspend fun syncPendingUploads(context: Context) {
        repository.syncAllPendingUploads(File(context.filesDir.absolutePath))
    }

    // -----------------------------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------------------------

    /**
     * Fluent builder for configuring and launching a fingerprint scan session.
     *
     * **Mandatory fields for all scan types:**
     * - [setScanningType]
     * - [setKey]
     * - [setUniqueId] (unless [skipFirebaseActions] is `true`)
     *
     * **Additional mandatory field for [ScanningType.VERIFICATION]:**
     * - [setAmount] (must be > 0)
     *
     * @param context Calling [Context] — used for validation error [Toast]s and building the
     *                launch [Intent]. The scanner itself runs in its own Activity.
     */
    class Builder(private val context: Context) {

        private val options = BuilderOptions()

        /**
         * Sets whether this session performs a registration or verification scan.
         * This field is mandatory.
         */
        fun setScanningType(scanningType: ScanningType): Builder {
            options.scanningType = scanningType
            return this
        }

        /**
         * Sets the user's unique identifier (e.g. BVN number). Required unless
         * [skipFirebaseActions] is `true`.
         */
        fun setUniqueId(uniqueId: String): Builder {
            options.uniqueId = uniqueId
            return this
        }

        /** Sets the user's phone number, stored in the user record. */
        fun setPhoneNumber(phoneNumber: String): Builder {
            options.phoneNumber = phoneNumber
            return this
        }

        /**
         * Transaction amount in the smallest currency unit (e.g. kobo). Required and must be
         * greater than zero for [ScanningType.VERIFICATION] scans.
         */
        fun setAmount(amount: Int): Builder {
            options.amount = amount
            return this
        }

        /**
         * AES encryption key used to protect the fingerprint template files at rest.
         * Must not be blank.
         */
        fun setKey(key: String): Builder {
            options.key = key
            return this
        }

        /** Name of the bank or payment provider; stored in the Firestore user record. */
        fun setBankProvider(bankProviderName: String): Builder {
            options.bankProvider = bankProviderName
            return this
        }

        /** Login or session type; stored in the Firestore user record. */
        fun setLoginType(loginType: String): Builder {
            options.loginType = loginType
            return this
        }

        /** Arbitrary user ID (distinct from [setUniqueId]); stored in the user record. */
        fun setUserId(userId: String): Builder {
            options.userId = userId
            return this
        }

        /** Overrides the default scanner UI colors and drawables. */
        fun setThemeOptions(themeOptions: ThemeOptions): Builder {
            options.themeOptions = themeOptions
            return this
        }

        /** Attaches a JSON object that is persisted alongside the user record in Firestore. */
        fun setCustomData(customObject: JSONObject): Builder {
            options.customObject = customObject
            return this
        }

        /**
         * When `true`, GPS location capture is skipped entirely and no location permission
         * dialog is shown to the user.
         */
        fun skipLocation(skipLocation: Boolean): Builder {
            options.skipLocation = skipLocation
            return this
        }

        /** New Relic application token for APM and crash reporting inside the scanner session. */
        fun newRelicToken(newRelicToken: String): Builder {
            options.newRelicToken = newRelicToken
            return this
        }

        /** Firebase Storage folder prefix for uploaded files (e.g. `"biometrics/"`). */
        fun storagePath(path: String): Builder {
            options.storagePath = path
            return this
        }

        /**
         * When `true`, all Firestore and Firebase Storage operations are bypassed entirely.
         * Useful for offline-only deployments or test builds.
         */
        fun skipFirebaseActions(skipFirebaseActions: Boolean): Builder {
            options.skipFirebaseActions = skipFirebaseActions
            return this
        }

        /**
         * When `true`, an additional `.bmp` image is saved alongside the WSQ and JPEG outputs
         * for each reader. Default: `false`.
         */
        fun enableBmpExport(enable: Boolean): Builder {
            options.enableBmpExport = enable
            return this
        }

        /**
         * When `true`, the exported BMP file is uploaded to Firebase Storage in addition to
         * the encrypted template files. Only meaningful when [enableBmpExport] is also `true`.
         */
        fun uploadBmpToFirebase(enable: Boolean): Builder {
            options.uploadBmpToFirebase = enable
            return this
        }

        /**
         * When `true`, the same fingerprints may be registered under more than one unique ID.
         *
         * Default `false`, which makes registration compare the freshly captured fingers
         * against every other unique ID's templates held on this device and refuse a match.
         * Turn it on for demos where one person's fingers stand in for several identities.
         */
        fun allowDuplicateFingerprints(allow: Boolean): Builder {
            options.allowDuplicateFingerprints = allow
            return this
        }

        /**
         * When `true`, the fingerprints that authorised a **successful** verification are
         * saved under `verifications/<uniqueId>/`. Default: `false`.
         *
         * This keeps a biometric per transaction rather than per identity, so storage grows
         * without bound — leave it off unless the evidence trail is actually required.
         */
        fun saveVerificationCaptures(enable: Boolean): Builder {
            options.saveVerificationCaptures = enable
            return this
        }

        /**
         * Validates the configured options and launches [UpdatedScannerActivity] from an
         * [Activity].
         *
         * Validation errors are surfaced as a [Toast] rather than thrown exceptions, matching
         * typical button-handler usage where a crash would be disruptive.
         *
         * @param activity The host [Activity] whose context is used to build the [Intent].
         * @param launcher [ActivityResultLauncher] that will receive the scan result intent.
         */
        fun start(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
            try {
                validate()
                launcher.launch(buildIntent(activity))
            } catch (e: IllegalArgumentException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Validates the configured options and launches [UpdatedScannerActivity] from a
         * [Fragment].
         *
         * @param fragment The host [Fragment]; its attached activity provides the [Intent]
         *                 context.
         * @param launcher [ActivityResultLauncher] that will receive the scan result intent.
         * @throws IllegalStateException if the fragment is not currently attached to an activity.
         */
        fun start(fragment: Fragment, launcher: ActivityResultLauncher<Intent>) {
            val activity = checkNotNull(fragment.activity) {
                "Fragment is not attached to an Activity"
            }
            try {
                validate()
                launcher.launch(buildIntent(activity))
            } catch (e: IllegalArgumentException) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Validates all mandatory and conditional options.
         *
         * Throws [IllegalArgumentException] with a human-readable message on the first
         * violation found. All checks use [require] for consistent exception type.
         */
        private fun validate() {
            require(options.scanningType != null) {
                "Scanning type cannot be null"
            }
            if (options.skipFirebaseActions) return
            require(!options.uniqueId.isNullOrEmpty()) {
                "Unique ID (BVN) cannot be null or empty"
            }
            if (options.scanningType == ScanningType.VERIFICATION) {
                require((options.amount ?: 0) > 0) {
                    "A valid amount greater than zero is required for verification"
                }
            }
            require(!options.key.isNullOrBlank()) {
                "Encryption key cannot be null or empty"
            }
        }

        /**
         * Serializes the current [BuilderOptions] to JSON and packages it into a launch
         * [Intent] targeting [UpdatedScannerActivity].
         *
         * @param activity A non-null [Activity] used as the [Intent] source context.
         * @return A fully configured [Intent] ready for [ActivityResultLauncher.launch].
         */
        private fun buildIntent(activity: Activity): Intent {
            val bundle = Bundle().apply {
                putString(SCANNING_OPTIONS, Gson().toJson(options))
            }
            return Intent(activity, UpdatedScannerActivity::class.java).putExtras(bundle)
        }
    }
}
