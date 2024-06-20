package com.scanner.model

import java.util.Date

internal data class User(
    var bvnNumber: String? = null,
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
    var amount: Int? = null,
    var timestamp: Date? = null,
    var gpsCoordinates: ArrayList<Double?>? = null,
)
