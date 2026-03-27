package com.scanner.utils.builder

import com.scanner.utils.enums.ScanningType
import org.json.JSONObject

internal class BuilderOptions {

    var uniqueId: String? = null

    var phoneNumber: String? = null
    var userId: String? = null
    var bankProvider: String? = null
    var loginType: String? = null
    var scanningType: ScanningType? = null
    var amount: Int? = null
    var key: String? = null
    var themeOptions: ThemeOptions? = null
    var customObject: JSONObject? = null
    var skipLocation: Boolean = false
    var newRelicToken: String? = null
    var storagePath: String? = null
    var skipFirebaseActions: Boolean = false
    var enableBmpExport: Boolean = false
    var uploadBmpToFirebase: Boolean = false
//    var firebaseFireStore: FirebaseFirestore? = null
}

