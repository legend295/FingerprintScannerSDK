package com.scanner.utils.constants

object ScannerConstants {
    const val DATA = "file_data"
    const val TEMPLATE_DATA = "template_data"
    const val CUSTOM_DATA = "custom_data"
    const val VERIFICATION_RESULT = "verification_result"

    /**
     * JSON summary of the pass that just finished, for the host app's audit log.
     *
     * Shape: `{"liveness":"#0=41230 #1=39100","threshold":32768,"quality":"#0=78 #1=81",
     * "matchScore":123}` — `matchScore` present for verification only. Values are per reader,
     * keyed by reader index. Compare liveness against `threshold`, not against any assumed
     * maximum: the score has no known ceiling.
     */
    const val SCAN_SUMMARY = "scan_summary"
}