package com.scanner.model

import java.util.Date

data class User(
    var uniqueId: String? = null,
    var deviceId: String? = null,
    var userId: String? = null,
    var phoneNumber: String? = null,
    var bankProvider: String? = null,
    var loginType: String? = null,
    var type: String? = null,// Registration or Transaction
    var fingerprintVerificationStatus: Boolean? = null,
    var fingerPrintCount: Int? = null,
    var fingerPrintLocalPath: ArrayList<String>? = null,
    var fingerPrintCloudPath: ArrayList<String>? = null,
    var fingerPrintSyncedOnCloud: Boolean? = null,
    var timestamp: Date? = null,
    var gpsCoordinates: ArrayList<Double?>? = null,
    var customObject: MutableMap<String, Any>? = null,
    // Raw BMP exports, tracked separately from the templates: they are optional
    // (enableBmpExport), uploaded under a different flag (uploadBmpToFirebase), and must never be
    // mistaken for the .dat templates that identify the user.
    //
    // Appended rather than grouped with the other fingerPrint* fields on purpose — this class is
    // public API and is constructed positionally (see ScannerActivity), so inserting mid-list
    // would silently rebind every argument after the insertion point.
    var fingerPrintBmpLocalPath: ArrayList<String>? = null,
    var fingerPrintBmpCloudPath: ArrayList<String>? = null,
    /**
     * BMP upload state, and the query key for the background retry sweep.
     *
     * Three-valued on purpose: `null` means BMPs are not in play for this user at all — either
     * none were exported or the host opted out of uploading them — and a null never matches
     * `whereEqualTo(..., false)`, so those users are skipped without a per-user check. `false`
     * means BMPs exist and are still owed to the cloud; `true` means they all arrived.
     *
     * Kept apart from [fingerPrintSyncedOnCloud], which speaks only for the identity templates.
     */
    var fingerPrintBmpSyncedOnCloud: Boolean? = null,
)
