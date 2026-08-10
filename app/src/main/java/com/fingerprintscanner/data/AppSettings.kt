package com.fingerprintscanner.data

import android.content.Context
import androidx.core.content.edit

/**
 * Operator-changeable settings, as opposed to the values hardcoded into each scan launch.
 *
 * Kept separate so a demo or certification run can be adjusted on the terminal without a new
 * APK. Both flags are passed into the SDK through
 * [com.scanner.updated.UpdatedFingerprintScanner.Builder] at launch time — nothing here changes
 * behaviour on its own.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Allow the same fingerprints to be registered under more than one unique ID.
     *
     * Off by default: a duplicate registration is exactly what a registry should refuse. On is
     * useful when one person's fingers stand in for several test identities during a demo,
     * which is otherwise impossible with a single pair of hands.
     */
    var allowDuplicateFingerprints: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_DUPES, DETECT_DUPLICATES)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_DUPES, value) }

    /**
     * Keep the fingerprints that authorised an approved verification, under `verifications/`.
     *
     * Stores a biometric per transaction rather than per identity, so it grows without bound and
     * carries a materially larger data-protection footprint than the registration templates
     * alone. Worth leaving off unless the evidence trail is actually required.
     */
    var saveVerificationCaptures: Boolean
        get() = prefs.getBoolean(KEY_SAVE_VERIFICATIONS, SAVE_VERIFICATION_CAPTURES)
        set(value) = prefs.edit { putBoolean(KEY_SAVE_VERIFICATIONS, value) }

    private companion object {
        const val PREFS = "scanner_settings"
        const val KEY_ALLOW_DUPES = "allow_duplicate_fingerprints"
        const val KEY_SAVE_VERIFICATIONS = "save_verification_captures"
        const val DETECT_DUPLICATES = true
        const val SAVE_VERIFICATION_CAPTURES = false
    }
}
