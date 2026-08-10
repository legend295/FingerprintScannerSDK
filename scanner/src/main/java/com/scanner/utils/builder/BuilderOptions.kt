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

    /**
     * Allow the same fingerprints to be registered under more than one unique ID.
     *
     * Off by default: a duplicate registration is exactly what a biometric registry should
     * refuse. Turning it on is useful when one person's fingers must stand in for several
     * test identities during a demo, which is otherwise impossible with a single pair of hands.
     */
    var allowDuplicateFingerprints: Boolean = false

    /**
     * Keep the fingerprints that authorised a successful verification, under `verifications/`.
     *
     * Stores a biometric per transaction rather than per identity, so it grows without bound
     * and carries a materially larger data-protection footprint than the registration
     * templates alone. Failed verifications are never stored.
     */
    var saveVerificationCaptures: Boolean = false
//    var firebaseFireStore: FirebaseFirestore? = null
}

