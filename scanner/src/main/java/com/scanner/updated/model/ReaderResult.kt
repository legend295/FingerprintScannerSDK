package com.scanner.updated.model

import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsStatus

/**
 * Captures the final outcome of a single reader's scan session.
 *
 * Populated by [com.scanner.updated.reader.FingerprintReaderWrapper] after it finishes
 * either a registration (extract) or verification (identify) pass and emits it wrapped in
 * [ScannerEvent.ScanCompleted].
 *
 * @param readerNo        0 = left reader, 1 = right reader.
 * @param extractStatus   SDK status from the extraction step; null when the operation was
 *                        cancelled before extraction could complete.
 * @param identifyResult  Full identification result from the SDK; null for registration flows.
 * @param wsqPath         Absolute path of the WSQ-encoded fingerprint image saved to disk.
 * @param bitmapPath      Absolute path of the JPEG preview image saved to disk.
 * @param templatePath    Absolute path of the encrypted ISO-template (.dat) file saved to disk.
 * @param quality         Template quality as reported by the extraction (higher is better;
 *                        0 when the SDK did not populate it).
 * @param livenessScore   Highest anti-spoof score seen during the pass, or 0 if the module
 *                        never reported one. Compare against [livenessThreshold], not against
 *                        any assumed maximum — the scale has no known ceiling.
 * @param livenessThreshold  Anti-spoof cutoff actually programmed into this reader.
 * @param fingerDetect    Highest finger-detect (coverage) value seen during the pass.
 */
data class ReaderResult(
    val readerNo: Int,
    val extractStatus: NBBiometricsStatus?,
    val identifyResult: NBBiometricsIdentifyResult?,
    val wsqPath: String?,
    val bitmapPath: String?,
    val templatePath: String?,
    val quality: Int,
    val livenessScore: Int = 0,
    val livenessThreshold: Int = 0,
    val fingerDetect: Int = 0,
)
