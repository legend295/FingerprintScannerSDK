package com.scanner.utils.constants

internal object Constant {
    const val FINGER_PRINT_READ_INFO = "100,0,1.0"
    const val SCANNING_OPTIONS = "scanning_options"
    const val TRANSACTION_DISTANCE = 20
    const val REGISTRATION = "Registration"
    const val TRANSACTION = "Transaction"

    /** `type` discriminator on a [com.scanner.model.Transaction] document. */
    const val VERIFICATION = "Verification"
    const val USERS = "users"
    const val CUSTOM_OBJECT = "customObject"

    /**
     * Suffix every saved ISO template file carries, locally and in Storage.
     *
     * Lives here rather than on the reader because the per-user directory is no longer templates
     * only — BMP exports sit alongside them — so anything counting or downloading "the
     * fingerprints" has to tell the two apart, on both sides of the wire.
     */
    const val TEMPLATE_FILE_SUFFIX = "-ISO-Template.dat"
}