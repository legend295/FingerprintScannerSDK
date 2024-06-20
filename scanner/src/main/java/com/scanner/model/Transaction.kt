package com.scanner.model

import java.util.Date

internal data class Transaction(
    var amount: Int? = null,
    var timestamp: Date? = null,
    var bvnNumber: String? = null,
    var gpsCoordinates: ArrayList<Double?>? = null,
)
