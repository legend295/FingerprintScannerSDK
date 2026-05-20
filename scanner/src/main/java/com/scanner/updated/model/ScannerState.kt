package com.scanner.updated.model

/**
 * Finite state machine for the dual-reader scanning session.
 *
 * Valid transitions:
 *
 * ```
 * Idle ──► Initializing ──► Ready ──► Scanning ──► Success
 *                     │                     │
 *                     ▼                     ▼
 *                  Failed               Failed / Cancelled
 * ```
 *
 * The UI layer observes this as a [kotlinx.coroutines.flow.StateFlow] from
 * [com.scanner.updated.reader.ScannerSessionManager.state].
 */
sealed class ScannerState {

    /** Nothing has started yet. Initial state on construction. */
    object Idle : ScannerState()

    /**
     * USB power cycle + SDK initialisation + reader session open in progress.
     * The UI should show a blocking "Initializing…" dialog.
     */
    object Initializing : ScannerState()

    /**
     * Both reader sessions are open and the scanner is ready to accept a scan command.
     * The UI should show the "Start Scan" button.
     */
    object Ready : ScannerState()

    /**
     * Scan / extract / identify is actively running on both readers.
     * The UI should show a cancel button and live preview images.
     */
    object Scanning : ScannerState()

    /**
     * Both readers completed their scan passes without error.
     *
     * @param reader0Result  Result from reader 0 (typically left finger).
     * @param reader1Result  Result from reader 1 (typically right finger).
     */
    data class Success(
        val reader0Result: ReaderResult,
        val reader1Result: ReaderResult,
    ) : ScannerState()

    /**
     * The operation was cancelled — either by the user or programmatically because one reader
     * encountered an error or spoof.
     *
     * @param reason  Human-readable cancellation reason (not for display; for logging).
     */
    data class Cancelled(val reason: String) : ScannerState()

    /**
     * Initialisation or scan failed in a non-recoverable way.
     *
     * @param reason    Human-readable error description.
     * @param readerNo  The reader index that caused the failure, or null if it was global.
     */
    data class Failed(
        val reason: String,
        val readerNo: Int? = null,
    ) : ScannerState()
}
