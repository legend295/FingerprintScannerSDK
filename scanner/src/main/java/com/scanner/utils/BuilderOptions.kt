package com.scanner.utils

import com.google.firebase.firestore.FirebaseFirestore
import com.scanner.utils.enums.ScanningType

internal class BuilderOptions {

    var bvnNumber: String? = null

    var phoneNumber: String? = null
    var scanningType: ScanningType? = null
    var amount: Int? = null
//    var firebaseFireStore: FirebaseFirestore? = null
}