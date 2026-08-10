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
     * [image] and [bitmap] are **nullable**: the sensor emits status-only frames that carry no
     * pixels, and the anti-spoof numbers on those frames still matter. In particular a terminal
     * SPOOF or DONE frame may arrive without an image, and it is the one that turns the panel
     * red — so the readout must not depend on pixels being present. Consumers should leave the
     * previous image on screen when [bitmap] is null rather than blanking the view.
     *
     * @param readerNo      Which physical reader (0 or 1) produced this frame.
     * @param image         Raw grayscale byte array from the sensor, or null on a status-only frame.
     * @param bitmap        ARGB_8888 bitmap ready for display, or null when there are no pixels.
     * @param status        Current scan status at the time of this preview.
     * @param previewType   Whether this frame came from an extraction or identification pass.
     */
    data class PreviewFrame(
        val readerNo: Int,
        val width: Int,
        val height: Int,
        val image: ByteArray?,
        val bitmap: Bitmap?,
        val status: NBDeviceScanStatus,
        val fingerDetect: Int,
        val liveness: Int,
        val thresholdLiveness: Int,
        val spoof: Boolean,
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
     * The device rejected the presentation as not a live finger.
     * When emitted, [com.scanner.updated.reader.ScannerSessionManager] will cancel the
     * other reader immediately.
     *
     * This is only ever raised from the *result* of `extract`/`identify` — never from a
     * preview frame. Preview fires continuously while a finger settles onto the platen, and
     * those early partial-contact frames routinely read as a spoof on a perfectly real
     * finger; latching on the first one rejects genuine users.
     *
     * @param readerNo  The reader that rejected the presentation.
     * @param kind      Whether a fake finger or a latent (residue) print was detected.
     * @param detail    Operator-facing explanation, including the measured liveness figures.
     */
    data class SpoofDetected(
        val readerNo: Int,
        val kind: SpoofKind,
        val detail: String,
    ) : ScannerEvent() {
        /** Which anti-spoof defence rejected the scan. */
        enum class SpoofKind {
            /** A fake finger: the liveness score fell below the device's threshold. */
            FAKE_FINGER,

            /** A latent print — residue revived on the platen rather than a finger. */
            LATENT_PRINT,
        }
    }

    /**
     * These fingerprints are already registered under a different unique ID.
     *
     * Only raised when [com.scanner.utils.builder.BuilderOptions.allowDuplicateFingerprints]
     * is false. The comparison runs against templates held on *this device*, so it catches a
     * repeat registration on the same terminal — not one performed elsewhere.
     *
     * @param readerNo          The reader whose finger matched.
     * @param existingUniqueId  The unique ID the fingerprints are already registered under.
     * @param score             Match score reported by the SDK.
     */
    data class DuplicateDetected(
        val readerNo: Int,
        val existingUniqueId: String,
        val score: Int,
    ) : ScannerEvent()

    /**
     * The sensor pad appears soiled: it keeps reporting a finger on an empty platen, so the
     * device will never begin a scan. Sweat and oil build up over a run of captures and read
     * as a permanent partial finger, which no amount of lifting clears.
     *
     * @param readerNo  The reader whose pad looks dirty.
     * @param detail    Operator-facing instruction.
     */
    data class SensorDirty(
        val readerNo: Int,
        val detail: String,
    ) : ScannerEvent()

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
     * The readers did not wake within
     * [com.scanner.updated.reader.ScannerSessionManager.WAKE_TIMEOUT_MS] of entering
     * [ScannerState.AwaitingWake].
     *
     * The UI must ask the user whether to keep waiting and report the answer back via
     * [com.scanner.updated.reader.ScannerSessionManager.onWakeWaitDecision] — initialisation
     * stays suspended until it does. If this event is missed (activity not started), the
     * prompt is recovered from
     * [com.scanner.updated.reader.ScannerSessionManager.isAwaitingWakeDecision] when
     * [ScannerState.AwaitingWake] is next rendered.
     */
    object ReadersNotResponding : ScannerEvent()

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
