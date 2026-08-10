package com.scanner.model

import java.util.Date

/**
 * One document in the `transaction` collection — the audit record written after a successful
 * verification.
 *
 * The first five properties are the original shape and must keep their order: the legacy
 * [com.scanner.activity.ScannerActivity] constructs this positionally. Everything below them was
 * added for the verification record and defaults to null/empty, so a partially-populated
 * transaction still serializes cleanly.
 *
 * @param amount            Transaction amount supplied by the host app, if any.
 * @param timestamp         When the verification completed. Firestore stores this as a Timestamp.
 * @param bvnNumber         Unique ID of the verified user.
 * @param gpsCoordinates    `[latitude, longitude]` at the time of the transaction; entries are
 *                          null when no fix was available.
 * @param customObject      Flattened custom fields passed through the builder.
 * @param deviceId          Android ID of the device that performed the verification.
 * @param matchScore        Best identification score across both readers.
 * @param fingerprintVerificationStatus  Always true — a record is only written on a match.
 * @param fingerPrintLocalPath     On-device paths of the enrolled templates, when known.
 * @param fingerPrintCloudPath     Storage paths of the *enrolled* templates this verification
 *                                 matched against, copied from the user record — a verification
 *                                 produces no template of its own. The per-transaction captures
 *                                 live under `<storagePath>/<bvn>/verifications/`.
 * @param fingerPrintBmpLocalPath  On-device paths of the raw BMP captures.
 * @param fingerPrintBmpCloudPath  Storage paths of those BMPs, empty when not uploaded.
 * @param fingerPrintSyncedOnCloud True only when every captured artifact reached the cloud.
 * @param lastVerifiedAt    Epoch millis of the verification, denormalised for cheap querying.
 * @param type              Record discriminator; [com.scanner.utils.constants.Constant.VERIFICATION].
 */
internal data class Transaction(
    var amount: Int? = null,
    var timestamp: Date? = null,
    var bvnNumber: String? = null,
    var gpsCoordinates: ArrayList<Double?>? = null,
    var customObject: MutableMap<String, Any>? = null,
    var deviceId: String? = null,
    var matchScore: Long? = null,
    var fingerprintVerificationStatus: Boolean? = null,
    var fingerPrintLocalPath: List<String> = emptyList(),
    var fingerPrintCloudPath: List<String> = emptyList(),
    var fingerPrintBmpLocalPath: List<String> = emptyList(),
    var fingerPrintBmpCloudPath: List<String> = emptyList(),
    var fingerPrintSyncedOnCloud: Boolean? = null,
    var lastVerifiedAt: Long? = null,
    var type: String? = null,
)
