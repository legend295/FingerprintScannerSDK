package com.scanner.utils.builder

import com.github.legend295.fingerprintscanner.R
import com.scanner.utils.enums.ScanningType
import org.json.JSONObject

internal class BuilderOptions {

    var bvnNumber: String? = null

    var phoneNumber: String? = null
    var scanningType: ScanningType? = null
    var amount: Int? = null
    var key: String? = null
    var themeOptions: ThemeOptions? = null
    var customObject: JSONObject? = null
//    var firebaseFireStore: FirebaseFirestore? = null
}

