package com.scanner.updated.model

import android.graphics.Bitmap
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.scanner.utils.enums.PreviewListenerType

/**
 * All events produced by a single [com.scanner.updated.reader.FingerprintReaderWrapper].
 *
 * Events flow upward through [com.scanner.updated.reader.ScannerSessionManager] to the UI layer.
 * The sequence for a successful registration pass is:
 *   [PreviewFrame]* → [ExtractionDone] → [FileSaved]* → [ScanCompleted]
 *
 * The sequence for a successful verification pass is:
 *   [PreviewFrame]* → [IdentificationDone] → [ScanCompleted]
 *
 * If anything goes wrong at any step a [ReaderError] is emitted and the flow terminates.
 */
sealed class ScannerEvent {

    /**
     * Live preview frame captured by the sensor during an active scan.
     *
     * @param readerNo      Which physical reader (0 or 1) produced this frame.
     * @param image         Raw grayscale byte array from the sensor.
     * @param bitmap        ARGB_8888 bitmap ready for display.
     * @param status        Current scan status at the time of this preview.
     * @param previewType   Whether this frame came from an extraction or identification pass.
     */
    data class PreviewFrame(
        val readerNo: Int,
        val image: ByteArray,
        val bitmap: Bitmap,
        val status: NBDeviceScanStatus,
        val previewType: PreviewListenerType,
    ) : ScannerEvent() {
        override fun equals(other: Any?): Boolean {
            return super.equals(other)
        }

        override fun hashCode(): Int {
            return super.hashCode()
        }
    }

    /**
     * Emitted immediately after the SDK's extract() call returns, before any files are saved.
     *
     * @param readerNo  Which reader produced this result.
     * @param status    SDK extraction status (OK = success).
     */
    data class ExtractionDone(
        val readerNo: Int,
        val status: NBBiometricsStatus?,
    ) : ScannerEvent()

    /**
     * Emitted immediately after the SDK's identify() call returns.
     *
     * @param readerNo  Which reader produced this result.
     * @param result    Full identification result from the SDK; null on hard error.
     */
    data class IdentificationDone(
        val readerNo: Int,
        val result: NBBiometricsIdentifyResult?,
    ) : ScannerEvent()

    /**
     * A file was successfully written to disk by this reader.
     *
     * @param readerNo  Which reader saved the file.
     * @param path      Absolute path of the saved file.
     * @param fileType  What kind of file was saved.
     */
    data class FileSaved(
        val readerNo: Int,
        val path: String,
        val fileType: FileType,
    ) : ScannerEvent() {
        /** Distinguishes the kind of artifact that was persisted. */
        enum class FileType { WSQ, BITMAP, BMP, TEMPLATE }
    }

    /**
     * A human-readable status message produced by reader internals, suitable for display in the UI.
     *
     * @param readerNo   Which reader generated the message.
     * @param text       Message text; may be empty to clear a previous message.
     * @param isError    True when the message indicates an error condition.
     */
    data class Message(
        val readerNo: Int,
        val text: String,
        val isError: Boolean,
    ) : ScannerEvent()

    /**
     * Liveness check (anti-spoof) failed for this reader.
     * When emitted, [com.scanner.updated.reader.ScannerSessionManager] will cancel the
     * other reader immediately.
     *
     * @param readerNo  The reader that detected the spoofed finger.
     */
    data class SpoofDetected(val readerNo: Int) : ScannerEvent()

    /**
     * The device is currently in low-power (sleep) mode and cannot accept scan commands.
     * Emitted at the very start of a scan attempt, before any hardware interaction.
     * [com.scanner.updated.reader.ScannerSessionManager] treats this the same as an
     * "Invalid operation" sleep-mode error — it sets [com.scanner.updated.reader.ScannerSessionManager.isLowPowerEnabled] and surfaces
     * [ScannerState.Failed] so the activity can trigger a full re-initialization.
     *
     * @param readerNo  The reader that detected the sleep state.
     */
    data class DeviceInSleepMode(val readerNo: Int) : ScannerEvent()

    /**
     * Unrecoverable error on this reader (hardware fault, SDK exception, or file I/O failure).
     * When emitted the flow terminates, and [com.scanner.updated.reader.ScannerSessionManager]
     * cancels the opposite reader.
     *
     * @param readerNo  Which reader encountered the error.
     * @param cause     The underlying throwable.
     */
    data class ReaderError(
        val readerNo: Int,
        val cause: Throwable,
    ) : ScannerEvent()

    /**
     * Terminal event — emitted last when the reader finishes successfully.
     * Collecting this event means the reader's work is done.
     *
     * @param result  The fully populated [ReaderResult] for this reader.
     */
    data class ScanCompleted(val result: ReaderResult) : ScannerEvent()
}
