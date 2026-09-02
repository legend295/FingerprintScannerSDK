package com.scanner.updated.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.Log
import com.newrelic.agent.android.NewRelic
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.biometrics.NBBiometricsFingerPosition
import com.nextbiometrics.biometrics.NBBiometricsSecurityLevel
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.biometrics.NBBiometricsTemplateType
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewEvent
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewListener
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceEncodeFormat
import com.nextbiometrics.devices.NBDeviceFingerPosition
import com.nextbiometrics.devices.NBDeviceImageQualityAlgorithm
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceState
import com.nextbiometrics.devices.NBDeviceType
import com.nextbiometrics.system.NextBiometricsException
import com.scanner.app.ScannerApp
import com.scanner.updated.model.ReaderResult
import com.scanner.updated.model.ScannerEvent
import com.scanner.utils.IsoTemplate
import com.scanner.utils.KeyStorePortable
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import com.scanner.utils.constants.Constant
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.AbstractMap
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps a single [NBDevice] with a coroutine-friendly API.
 *
 * Each physical fingerprint reader maps to one instance. The wrapper owns the device lifecycle:
 * call [setDevice] once, [init] to open the session, then [scanAndExtractFlow] to perform a
 * scan. Call [cancel] to abort a running scan and [close] to release hardware resources.
 *
 * Thread-safety: [cancel] and [close] may be called from any thread. All other public methods
 * should be called from the same coroutine scope.
 *
 * @param context  Android application context (used for file paths and SDK calls).
 * @param readerNo Index of this reader: 0 for the first (left) reader, 1 for the second (right).
 */
internal class FingerprintReaderWrapper(
    private val context: Context,
    val readerNo: Int,
) {
    private val tag = "FPReader[$readerNo]"

    // ── SDK handles ───────────────────────────────────────────────────────────
    private var device: NBDevice? = null
    private var scanFormatInfo: NBDeviceScanFormatInfo? = null

    // ── State flags ───────────────────────────────────────────────────────────
    private var isInitialized = false

    // ── Anti-spoof state ──────────────────────────────────────────────────────
    // All are written on the SDK's preview-callback thread and read on the scan coroutine,
    // so each needs @Volatile for the read to see the write.

    /** True only once [configureAntispoof] programmed the device without throwing. */
    @Volatile
    private var antispoofActive = false

    /** Anti-spoof cutoff actually programmed into this reader, from [chooseThreshold]. */
    @Volatile
    private var activeAntispoofThreshold = ANTISPOOF_THRESHOLD_FALLBACK

    /**
     * Highest liveness score seen this scan pass, or 0 if the module never reported one.
     *
     * Reported to the UI and logged for tuning; the accept/reject decision belongs to the
     * device, which applies [activeAntispoofThreshold] internally and answers through
     * `NBBiometricsStatus.SPOOF_DETECTED`. A peak is kept rather than the last value because
     * the frames trailing a capture say nothing about the finger that was captured.
     */
    @Volatile
    private var peakLiveness = 0

    /** Highest finger-detect (coverage) value seen this scan pass. */
    @Volatile
    private var peakDetect = 0

    /** Most recent liveness/detect pair, for the live preview readout. */
    @Volatile
    private var lastLiveness = 0

    @Volatile
    private var lastDetect = 0

    /** Set when the pad kept reporting a finger on an empty platen — see [ScannerEvent.SensorDirty]. */
    @Volatile
    private var sensorLooksDirty = false

    companion object {
        /**
         * Anti-spoof cutoff used when `getLivenessThreshold` is unsupported on the attached
         * module. Measured on the Telpo TPS900's FAP20 modules as the 1.0 % probe point.
         */
        private const val ANTISPOOF_THRESHOLD_FALLBACK = 32768

        /**
         * How hard to push anti-spoof.
         *
         * `getLivenessThreshold(pct)` takes a percentage in 1.0f..3.3f and returns the raw score
         * a presentation must reach. The SDK guide never says which end is stricter, so
         * [chooseThreshold] probes [ANTISPOOF_PROBE_POINTS] and ranks what the device actually
         * returns. Measured on the FAP20 modules the threshold *rises* with the percentage:
         *
         *     1.0%=32768  1.5%=33966  2.0%=34816  2.5%=35475  3.0%=36014  3.3%=36295
         *
         * Genuine fingers on that hardware measured 36233..45462 and the two modules are not
         * equivalent — the second reads 4–6k lower than the first on the same hand, so a single
         * global threshold is set by the weaker one. STRICT (36295) rejected a real finger that
         * peaked at 36233. Play-Doh fakes measured 4577..24416, so PERMISSIVE sits near the
         * middle of the gap between the worst fake and the weakest genuine finger.
         */
        enum class Strictness { STRICT, BALANCED, PERMISSIVE }

        val ANTISPOOF_STRICTNESS = Strictness.PERMISSIVE

        val ANTISPOOF_PROBE_POINTS = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.3f)

        private const val CONFIGURE_ANTISPOOF = 108
        private const val CONFIGURE_ANTISPOOF_THRESHOLD = 109
        private const val ENABLE_ANTISPOOF = 1

        /**
         * NB_DEVICE_PARAMETER_SUBTRACT_BACKGROUND — the anti-latent defence. The device
         * subtracts a background reference so a print revived from residue on the platen
         * cannot be re-read as a live finger.
         */
        private const val CONFIGURE_SUBTRACT_BACKGROUND = 105
        private const val ENABLE_ANTI_LATENT = 1
        private const val DISABLE_ANTI_LATENT = 0

        /** Modules supporting one-time background capture, per the vendor sample. */
        private val ONE_TIME_BG_TYPES = setOf(
            NBDeviceType.NB2020U, NBDeviceType.NB2023U, NBDeviceType.NB2033U,
            NBDeviceType.NB65200U, NBDeviceType.NB65210S,
        )

        /** Preview fires far faster than a screen can redraw; throttle to ~12 fps. */
        private const val PREVIEW_MIN_INTERVAL_MS = 80L

        /** How long "lift your finger" can persist before a soiled pad is the likelier cause. */
        private const val DIRTY_SENSOR_AFTER_MS = 6_000L

        /** Resting finger-detect above this with no finger present means a soiled pad. */
        private const val DIRTY_SENSOR_DETECT = 60

        /** Detect at or above this means the pad was fully covered. */
        private const val FULL_CONTACT_DETECT = 255

        /**
         * Subdirectory of `filesDir` holding per-transaction verification captures.
         *
         * Deliberately excluded from the registration gallery: it sits alongside the
         * per-uniqueId template directories, so anything walking `filesDir` for registered
         * identities must skip it or it would be read as a unique ID of its own.
         */
        const val VERIFICATIONS_DIR = "verifications"

        /** Suffix every saved ISO template file carries. Shared with the Firebase layer. */
        const val TEMPLATE_SUFFIX = Constant.TEMPLATE_FILE_SUFFIX
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public setup API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Assigns the hardware device object to this wrapper.
     * Must be called before [init]. The caller (ScannerSessionManager) is responsible for
     * obtaining a valid [NBDevice] from [com.nextbiometrics.devices.NBDevices.getDevices].
     *
     * @param nbDevice  A device obtained from the NBDevices SDK.
     */
    fun setDevice(nbDevice: NBDevice) {
        device = nbDevice
    }

    /**
     * Returns the device state, or null if no device has been set or the SDK call threw.
     *
     * A null result is treated by [ScannerSessionManager] exactly like
     * [NBDeviceState.NOT_CONNECTED] — there is no usable handle either way.
     */
    fun getDeviceState(): NBDeviceState? = runCatching { device?.state }.getOrNull()

    /**
     * Returns true when the device is in low-power (sleep) mode, false when it is awake,
     * or null if no device has been set or the SDK call threw.
     *
     * Backed by the SDK's `NBIsDeviceInLowPowerMode`, so `true` means "asleep".
     */
    fun getDeviceModeStatus(): Boolean? = runCatching { device?.GetDeviceModeStatus() }.getOrNull()

    /** Returns true only when [init] completed successfully. */
    fun isReady(): Boolean = isInitialized && device?.isSessionOpen == true

    // ──────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Opens the security session, applies calibration if required, queries scan formats,
     * and enables anti-spoof detection.
     *
     * Should be called from an IO coroutine (blocks the calling thread).
     * A 30-second hard timeout is enforced via a dedicated executor thread; if the underlying
     * SDK call (openSession) does not return within that window the init is treated as failed
     * so the UI can recover instead of spinning the init dialog indefinitely.
     *
     * @return True on success; false if any step failed or the call timed out.
     */
    fun init(): Boolean {
        Log.d(tag, "init() starting…")
        isInitialized = false
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<Boolean>(::doInit)
            future.get(30, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            logError("$tag init() → timed out after 30 s — device unresponsive")
            false
        } catch (e: Exception) {
            logError("$tag init() → Exception: ${e.message}")
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun doInit(): Boolean {
        return try {
            if (!openSession()) {
                logError("$tag doInit() → openSession FAILED")
                return false
            }
            val dev = device ?: return false
            if (!dev.isSessionOpen) {
                logError("$tag doInit() → session not open after openSession()")
                return false
            }

            if (dev.capabilities?.requiresExternalCalibrationData == true) {
                applyCalibrationData(dev)
            }

            val formats = dev.supportedScanFormats
            if (formats.isNullOrEmpty()) {
                logError("$tag doInit() → no supported scan formats")
                return false
            }
            scanFormatInfo = chooseFormat(formats)

            runCatching { configureAntispoof() }
                .onFailure { Log.w(tag, "Anti-spoof not supported on this device: ${it.message}") }

            // Anti-latent needs the platen empty for its one-time background capture, so it can
            // only run here at init — never between scans, by which point a finger has touched it.
            runCatching { configureAntiLatent(dev) }
                .onFailure { Log.w(tag, "Anti-latent setup failed: ${it.message}") }

            isInitialized = true
            logDebug("$tag doInit() → OK (format=${scanFormatInfo?.formatType})")
            true
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag doInit() → Exception: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scan & extract / identify
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs a full scan-and-extract (registration) or scan-and-identify (verification) pass on
     * this reader, emitting [ScannerEvent]s as the operation progresses.
     *
     * The returned [Flow] should be collected on a coroutine that is a child of a shared
     * [kotlinx.coroutines.coroutineScope]. This ensures that if the collecting coroutine is
     * canceled (e.g. because the other reader failed), [cancel] is called automatically via
     * [suspendCancellableCoroutine]'s `invokeOnCancellation` handler, unblocking the SDK call.
     *
     * The flow emits in this order on success:
     * 1. Zero or more [ScannerEvent.PreviewFrame] events.
     * 2. One [ScannerEvent.ExtractionDone] (registration) or [ScannerEvent.IdentificationDone] (verification).
     * 3. Zero or more [ScannerEvent.FileSaved] events.
     * 4. One terminal [ScannerEvent.ScanCompleted].
     *
     * On failure the flow emits a [ScannerEvent.ReaderError] and then closes with an exception
     * that propagates to the collector.
     *
     * @param savePath          Directory path (with trailing `/`) for WSQ and JPEG image files.
     * @param scanningType      Whether to run registration or verification logic.
     * @param bvnNumber         Unique user identifier; used as the subdirectory for template files.
     * @param encryptionKey     Application-level encryption key for template files.
     * @param skipFirebaseActions  When true the template is written but not checked against cloud state.
     * @param enableBmpExport   When true a BMP file is also written alongside the JPEG preview.
     * @param allowDuplicateFingerprints  When false, a registration whose finger already matches
     *                          another unique ID's stored templates is refused.
     * @param saveVerificationCaptures  When true, a successful verification's template is kept
     *                          under `verifications/<uniqueId>/`.
     */
    fun scanAndExtractFlow(
        savePath: String,
        scanningType: ScanningType,
        bvnNumber: String,
        encryptionKey: String,
        skipFirebaseActions: Boolean,
        enableBmpExport: Boolean,
        allowDuplicateFingerprints: Boolean = false,
        saveVerificationCaptures: Boolean = false,
    ): Flow<ScannerEvent> = callbackFlow {
        if (!isInitialized || device == null) {
            trySend(ScannerEvent.ReaderError(readerNo, IllegalStateException("Reader $readerNo not initialized")))
            close(IllegalStateException("Reader $readerNo not initialized"))
            return@callbackFlow
        }

        if (device?.GetDeviceModeStatus() == true) {
            trySend(ScannerEvent.DeviceInSleepMode(readerNo))
            close(IllegalStateException("Reader $readerNo is in sleep mode"))
            return@callbackFlow
        }
        val templateDir = "${context.filesDir.path}/$bvnNumber/"
        var biometricsCtx: NBBiometricsContext? = null
        var wsqPath: String?
        var bitmapPath: String? = null
        var templatePath: String?
        var quality = 0

        // Tracks whether this producer exited via CancellationException so the finally block
        // can fire dispose() on a daemon thread instead of blocking the producer coroutine.
        var cancelledByCoroutine = false

        try {
            biometricsCtx = NBBiometricsContext(device)
            // Capture a stable val so lambdas below can reference a non-nullable, non-reassignable
            // reference. Kotlin cannot smart-cast a var that is captured by a changing closure.
            val ctx = biometricsCtx

            when (scanningType) {

                // ── Registration: extract biometric template ───────────────────
                ScanningType.REGISTRATION -> {
                    resetScanSignals()

                    trySend(ScannerEvent.Message(readerNo, "Place your finger on the sensor.", false))

                    // Build preview listener that forwards frames to this flow.
                    val previewListener = buildPreviewListener { event ->
                        trySend(event)
                    }

                    // Block the IO thread, canceled via invokeOnCancellation → cancelScan().
                    val extractResult = runBlockingSdk {
                        clearStaleOperation(ctx)
                        withScanThreadPriority {
                            ctx.extract(
                                NBBiometricsTemplateType.ISO,
                                NBBiometricsFingerPosition.UNKNOWN,
                                scanFormatInfo,
                                previewListener,
                            )
                        }
                    }

                    ensureActive()

                    send(ScannerEvent.ExtractionDone(readerNo, extractResult.status))

                    // Anti-spoof is checked before the generic status branch below: SPOOF_DETECTED
                    // and LATENT_DETECTED are NBBiometricsStatus values like any other, so
                    // "extraction failed" would otherwise swallow them and report a presentation
                    // attack as an ordinary capture error.
                    spoofRejection(extractResult.status)?.let { rejection ->
                        send(rejection)
                        close(ScannerSessionManager.SpoofDetectedException(rejection.detail))
                        return@callbackFlow
                    }

                    assessScanOutcome(extractResult.status)?.let { dirty ->
                        send(dirty)
                        close(ScannerSessionManager.SensorDirtyException(dirty.detail))
                        return@callbackFlow
                    }

                    if (extractResult.status != NBBiometricsStatus.OK) {
                        val msg = describeExtractFailure(extractResult.status)
                        send(ScannerEvent.Message(readerNo, msg, true))
                        close(Exception(msg))
                        return@callbackFlow
                    }

                    val template = extractResult.template

                    // Duplicate registration check — before anything is written to disk or the
                    // cloud, so a rejected enrolment leaves nothing behind.
                    if (!allowDuplicateFingerprints) {
                        findDuplicateRegistration(ctx, template, bvnNumber)?.let { hit ->
                            send(ScannerEvent.DuplicateDetected(readerNo, hit.uniqueId, hit.score))
                            close(
                                ScannerSessionManager.DuplicateEnrolmentException(
                                    "These fingerprints are already registered under ${hit.uniqueId} " +
                                            "(match score ${hit.score})."
                                )
                            )
                            return@callbackFlow
                        }
                    }

                    // Quality comes off the template, NOT NBDevice.GetImageQuality: that static
                    // helper returns plausible NFIQ values but leaves the SDK's global last-error
                    // set, and the next SDK call reads it — which is what made every extract after
                    // the first successful capture fail with "Invalid operation".
                    quality = runCatching { template.quality }.getOrDefault(0)

                    val timestamp = System.currentTimeMillis()

                    // Save WSQ image derived from the extracted template.
                    val wsqBytes = device?.ConvertImage(
                        template.data,
                        scanFormatInfo!!.width,
                        scanFormatInfo!!.height,
                        500,
                        NBDeviceEncodeFormat.WSQ,
                        1.0f,
                        NBDeviceFingerPosition.Unknown,
                        0,
                    )
                    wsqPath = saveRawFile(wsqBytes, dir = savePath, timestamp = timestamp)
                    wsqPath?.let { send(ScannerEvent.FileSaved(readerNo, it, ScannerEvent.FileSaved.FileType.WSQ)) }

                    // Save JPEG preview bitmap. lastImage is only ever set from a frame whose
                    // buffer covered the full format, so a null here means the conversion itself
                    // failed — skip the JPEG rather than aborting a capture that already produced
                    // a valid template.
                    previewListener.lastImage?.let { img ->
                        convertToArgbBitmap(img)?.let { bmp ->
                            bitmapPath = saveBitmapJpeg(bmp, savePath, timestamp)
                        } ?: logError("$tag preview image could not be converted — skipping JPEG")
                        bitmapPath?.let {
                            send(
                                ScannerEvent.FileSaved(
                                    readerNo,
                                    it,
                                    ScannerEvent.FileSaved.FileType.BITMAP
                                )
                            )
                        }

                        if (enableBmpExport) {
                            // Into the per-user template directory, not savePath: the BMP is an
                            // export of this user's enrolled finger, so it belongs with the
                            // template it was captured alongside — which is also where the host
                            // app's artifact export looks. Everything that walks that directory
                            // filters on TEMPLATE_SUFFIX, so the .bmp cannot be mistaken for one.
                            runCatching { saveRawBmpFile(img, templateDir, timestamp) }
                                .onSuccess { path ->
                                    path?.let {
                                        send(
                                            ScannerEvent.FileSaved(
                                                readerNo,
                                                it,
                                                ScannerEvent.FileSaved.FileType.BMP
                                            )
                                        )
                                    }
                                }
                                .onFailure { logError("$tag BMP export failed: ${it.message}") }
                        }
                    }

                    // Encrypt and persist the ISO template.
                    templatePath = saveEncryptedTemplate(
                        ctx, template, templateDir, bvnNumber, encryptionKey, skipFirebaseActions,
                    )
                    templatePath?.let {
                        send(
                            ScannerEvent.FileSaved(
                                readerNo,
                                it,
                                ScannerEvent.FileSaved.FileType.TEMPLATE
                            )
                        )
                    }

                    send(ScannerEvent.Message(readerNo, "Fingerprint captured successfully.", false))
                    send(
                        ScannerEvent.ScanCompleted(
                            ReaderResult(
                                readerNo = readerNo,
                                extractStatus = extractResult.status,
                                identifyResult = null,
                                wsqPath = wsqPath,
                                bitmapPath = bitmapPath,
                                templatePath = templatePath,
                                quality = quality,
                                livenessScore = peakLiveness,
                                livenessThreshold = activeAntispoofThreshold,
                                fingerDetect = peakDetect,
                            )
                        )
                    )
                }

                // ── Verification: identify against stored templates ─────────────
                ScanningType.VERIFICATION -> {
                    resetScanSignals()

                    // Load all stored encrypted templates for this user.
                    val templates = loadStoredTemplates(ctx, templateDir)
                    if (templates.isEmpty()) {
                        val msg = "No stored templates found for reader $readerNo."
                        send(ScannerEvent.Message(readerNo, msg, true))
                        close(Exception(msg))
                        return@callbackFlow
                    }
                    trySend(ScannerEvent.Message(readerNo, "Place your finger on the sensor for verification.", false))

                    val previewListener = buildPreviewListener { event -> trySend(event) }

                    // Recreate context for the identify call; dispose the extraction context first.
                    ctx.dispose()
                    biometricsCtx = NBBiometricsContext(device)
                    // Capture a new stable val after the re-assignment for the lambda below.
                    val verifyCtx = biometricsCtx
                    Log.d(tag, "Starting identify with ${templates.size} stored templates…")
                    val identifyResult = runBlockingSdk {
                        clearStaleOperation(verifyCtx)
                        withScanThreadPriority {
                            verifyCtx.identify(
                                NBBiometricsTemplateType.ISO,
                                NBBiometricsFingerPosition.UNKNOWN,
                                scanFormatInfo,
                                previewListener,
                                templates.iterator(),
                                NBBiometricsSecurityLevel.HIGH,
                            )
                        }
                    }

                    ensureActive()
                    Log.d(
                        tag,
                        "identify result: status=${identifyResult.status}, templateId=${identifyResult.templateId}, score=${identifyResult.score}"
                    )
                    send(ScannerEvent.IdentificationDone(readerNo, identifyResult))

                    // Must run before the status branch below, which has no SPOOF_DETECTED case and
                    // would let the flow complete normally — surfacing a presentation attack to the
                    // user as "No match found" once the activity sees a non-OK identify status.
                    spoofRejection(identifyResult.status)?.let { rejection ->
                        send(rejection)
                        close(ScannerSessionManager.SpoofDetectedException(rejection.detail))
                        return@callbackFlow
                    }

                    assessScanOutcome(identifyResult.status)?.let { dirty ->
                        send(dirty)
                        close(ScannerSessionManager.SensorDirtyException(dirty.detail))
                        return@callbackFlow
                    }

                    val statusMsg = when (identifyResult.status) {
                        NBBiometricsStatus.OK ->
                            "Fingerprint verified (template: ${identifyResult.templateId})."

                        NBBiometricsStatus.MATCH_NOT_FOUND ->
                            "No matching fingerprint found."

                        else -> describeExtractFailure(identifyResult.status)
                    }
                    send(ScannerEvent.Message(readerNo, statusMsg, identifyResult.status != NBBiometricsStatus.OK))

                    // Keep the finger that authorised the transaction, when asked to. Only on a
                    // match: a declined verification is not evidence of anything, and storing it
                    // would collect biometrics from people who failed to authenticate.
                    var verificationPath: String? = null
                    if (saveVerificationCaptures && identifyResult.status == NBBiometricsStatus.OK) {
                        verificationPath = saveVerificationCapture(
                            image = previewListener.lastImage,
                            bvnNumber = bvnNumber,
                            enableBmpExport = enableBmpExport,
                        ) { path, type -> trySend(ScannerEvent.FileSaved(readerNo, path, type)) }
                    }

                    send(
                        ScannerEvent.ScanCompleted(
                            ReaderResult(
                                readerNo = readerNo,
                                extractStatus = null,
                                identifyResult = identifyResult,
                                wsqPath = null,
                                bitmapPath = verificationPath,
                                templatePath = null,
                                quality = quality,
                                livenessScore = peakLiveness,
                                livenessThreshold = activeAntispoofThreshold,
                                fingerDetect = peakDetect,
                            )
                        )
                    )
                }
            }

            close() // Normal completion — closes the callbackFlow.
        } catch (e: CancellationException) {
            // Propagate structured cancellation; do NOT wrap in ReaderError.
            cancelledByCoroutine = true
            device?.cancelScan()
            // cancelOperation() can block if hardware is unresponsive. Fire-and-forget on a
            // daemon thread so this producer coroutine exits immediately. The producer is a
            // child of the downstream deferred (via flowOn), so blocking here would prevent
            // coroutineScope in startScan from ever exiting — freezing the Scanning UI.
            biometricsCtx?.let { ctx ->
                Thread { runCatching { ctx.cancelOperation() } }.also { it.isDaemon = true }.start()
            }
            throw e
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag scanAndExtractFlow exception: ${e.message}")
            trySend(ScannerEvent.ReaderError(readerNo, e))
            close(e)
        } finally {
            biometricsCtx?.let { ctx ->
                if (cancelledByCoroutine) {
                    // Cancellation path: dispose off-thread so the producer exits immediately.
                    Thread { runCatching { ctx.dispose() } }.also { it.isDaemon = true }.start()
                } else {
                    runCatching { ctx.dispose() }
                }
            }
        }

        // awaitClose is required by callbackFlow; keeps the flow open until close() is called above.
        awaitClose { /* nothing extra; close() is called explicitly */ }
    }.flowOn(Dispatchers.IO)

    // ──────────────────────────────────────────────────────────────────────────
    // Cancellation & cleanup
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Signals the SDK to abort any in-progress scan operation.
     * Safe to call from any thread. Does NOT dispose the device — call [close] for full cleanup.
     */
    fun cancel() {
        logDebug("$tag cancel() called")
        runCatching { device?.cancelScan() }
    }

    /**
     * Cancels any in-progress operation and disposes the underlying [NBDevice].
     * After calling this method the wrapper cannot be reused without [setDevice] + [init].
     */
    fun close() {
        logDebug("$tag close() called")
        cancel()
        runCatching {
            device?.dispose()
        }
        device = null
        isInitialized = false
    }

    /**
     * Puts the device into low-power (sleep) mode.
     * Mirrors [com.scanner.utils.readers.FingerprintReader.enableLowPowerMode].
     * Should be called from [android.app.Activity.onDestroy] so the hardware draws minimal
     * current while the activity is not visible.
     */
    fun enableLowPowerMode() {
        val dev = device ?: return
        val ctx = runCatching { NBBiometricsContext(dev) }.getOrNull() ?: return
        try {
            runCatching { ctx.cancelOperation() }
            dev.lowPowerMode()
        } catch (_: NextBiometricsException) {
            logDebug("$tag enableLowPowerMode() → OK")
        } catch (e: Exception) {
            logError("$tag enableLowPowerMode() → ${e.message}")
        } finally {
            runCatching { ctx.dispose() }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: blocking SDK bridge (coroutine-safe)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs a blocking SDK call inside a [suspendCancellableCoroutine] so that when the parent
     * coroutine is canceled, [cancel] is invoked (via `invokeOnCancellation`) to unblock the SDK.
     *
     * The SDK call must already be on the IO dispatcher (caller is responsible for `flowOn`).
     *
     * @param block  The blocking SDK call that returns a result of type [T].
     * @return       The result of [block].
     */
    /**
     * Clears an operation the SDK still thinks is in flight before starting a new one.
     *
     * `extract()` returns ERROR_INVALID_OPERATION when the device believes a previous operation
     * is still running — which happens after a cancelled pass whose native call had not yet
     * unwound. Checking and cancelling costs one call and turns a hard failure into a retry the
     * user never sees.
     */
    private fun clearStaleOperation(ctx: NBBiometricsContext) {
        val running = runCatching { ctx.isOperationRunning }.getOrNull()
        if (running != true) return
        Log.w(tag, "$tag operation still running; cancelling before scan")
        runCatching { ctx.cancelOperation() }
        Thread.sleep(300)
    }

    /**
     * Runs [block] at audio thread priority.
     *
     * SPI/USB image readout starves at normal priority — the vendor sample raises thread
     * priority for exactly this reason. The previous priority is always restored.
     */
    private fun <T> withScanThreadPriority(block: () -> T): T {
        val previous = runCatching {
            android.os.Process.getThreadPriority(android.os.Process.myTid())
        }.getOrNull()
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        }
        return try {
            block()
        } finally {
            previous?.let { runCatching { android.os.Process.setThreadPriority(it) } }
        }
    }

    /**
     * Turns a non-OK extract/identify status into something an operator can act on, rather than
     * leaking the raw enum name into the UI.
     */
    private fun describeExtractFailure(status: NBBiometricsStatus?): String = when (status) {
        NBBiometricsStatus.TIMEOUT ->
            "No finger detected. Place both fingers on the sensors and hold still."

        NBBiometricsStatus.BAD_QUALITY ->
            "Fingerprint image was too poor to use. Clean the sensor, press firmly and try again."

        NBBiometricsStatus.TOO_FEW_MINUTIAE ->
            "Not enough fingerprint detail captured. Cover more of the sensor and try again."

        NBBiometricsStatus.NEED_MORE_SAMPLES ->
            "More samples needed. Keep your finger on the sensor until the scan completes."

        NBBiometricsStatus.CANCELED -> "Scan cancelled."

        else -> "Scan failed (${status ?: "unknown"}). Please try again."
    }

    private suspend fun <T> runBlockingSdk(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                // Called from a different thread when the coroutine is canceled.
                runCatching { device?.cancelScan() }
            }
            try {
                val result = block()
                if (cont.isActive) cont.resume(result)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: preview listener
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds an [NBBiometricsScanPreviewListener] that forwards each preview frame to [emit].
     * Also updates the anti-spoof score for post-scan validation.
     */
    private fun buildPreviewListener(
        previewType: PreviewListenerType = PreviewListenerType.EXTRACTION,
        emit: (ScannerEvent.PreviewFrame) -> Unit,
    ) = object : NBBiometricsScanPreviewListener {
        var lastImage: ByteArray? = null
            private set

        /** When the pad first started insisting a finger was present. 0 = not currently. */
        private var notRemovedSince = 0L
        private var lastEmit = 0L

        override fun preview(event: NBBiometricsScanPreviewEvent) {
            val scanStatus = event.scanStatus
            val fmt = event.format
            val liveness = event.livenessScoreValue
            val detect = event.fingerDetectValue

            lastLiveness = liveness
            lastDetect = detect
            if (liveness > peakLiveness) peakLiveness = liveness
            if (detect > peakDetect) peakDetect = detect

            // NOTE: a SPOOF status here is deliberately NOT treated as a decision. Preview fires
            // continuously while the finger is still settling onto the platen, and those early
            // partial-contact frames routinely read as a spoof on a perfectly real finger.
            // Latching on the first one rejected genuine fingers outright. The authoritative
            // answer is the extract/identify result; this only tints the on-screen panel.
            val frameLooksSpoofed = scanStatus == NBDeviceScanStatus.SPOOF ||
                    scanStatus == NBDeviceScanStatus.SPOOF_DETECTED
            val terminal = frameLooksSpoofed ||
                    scanStatus == NBDeviceScanStatus.OK ||
                    scanStatus == NBDeviceScanStatus.DONE

            // The device will not begin a scan until it has seen the platen go empty. If it keeps
            // insisting a finger is there while none is, the pad is dirty: sweat and oil build up
            // over a run of captures and read as a permanent partial finger.
            if (scanStatus == NBDeviceScanStatus.NOT_REMOVED || scanStatus == NBDeviceScanStatus.LIFT_FINGER) {
                val now = System.currentTimeMillis()
                if (notRemovedSince == 0L) notRemovedSince = now
                if (now - notRemovedSince > DIRTY_SENSOR_AFTER_MS) sensorLooksDirty = true
            } else {
                notRemovedSince = 0L
            }

            // Hold on to the most recent frame that actually carried a full image — this is what
            // the saved JPEG/BMP is written from. A short buffer is rejected rather than stored:
            // it would blow up bitmap conversion later, away from the frame that caused it.
            val fullImage = event.image?.takeIf { px ->
                fmt != null && px.size >= fmt.width * fmt.height
            }
            if (fullImage != null) lastImage = fullImage

            // Preview fires far faster than a screen can usefully redraw. Throttle, but never
            // drop a terminal frame — that is the one the operator most needs to see.
            //
            // The pixel check deliberately does NOT gate this. Status-only frames carry the
            // liveness numbers and the spoof tint, and a terminal SPOOF/DONE frame often has no
            // image at all; dropping it here would leave the panel showing a mid-placement
            // reading. Stamping lastEmit before such a drop would also steal the next real
            // frame's slot, so the throttle clock is only advanced when a frame is emitted.
            val now = System.currentTimeMillis()
            if (!terminal && now - lastEmit < PREVIEW_MIN_INTERVAL_MS) return
            lastEmit = now

            emit(
                ScannerEvent.PreviewFrame(
                    readerNo = readerNo,
                    image = fullImage,
                    bitmap = fullImage?.let { convertToArgbBitmap(it) },
                    status = scanStatus,
                    previewType = previewType,
                    width = fmt?.width ?: 0,
                    height = fmt?.height ?: 0,
                    fingerDetect = detect,
                    liveness = liveness,
                    thresholdLiveness = activeAntispoofThreshold,
                    spoof = frameLooksSpoofed,
                )
            )
        }
    }


// ──────────────────────────────────────────────────────────────────────────
// Private: session management
// ──────────────────────────────────────────────────────────────────────────

    /**
     * Opens the security session on the device according to the device's [NBDeviceSecurityModel].
     * Different security models require different authentication sequences.
     *
     * @return True if the session is open after the call, false on error.
     */
    private fun openSession(): Boolean {
        val dev = device ?: return false
        if (dev.isSessionOpen) return true

        // Key material — identical to the original implementation.
        val cakId = "DefaultCAKKey1\u0000".toByteArray()
        val cak = byteArrayOf(
            0x05, 0x4B, 0x38, 0x3A, 0xCF.toByte(), 0x5B, 0xB8.toByte(), 0x01,
            0xDC.toByte(), 0xBB.toByte(), 0x85.toByte(), 0xB4.toByte(), 0x47, 0xFF.toByte(),
            0xF0.toByte(), 0x79, 0x77, 0x90.toByte(), 0x90.toByte(), 0x81.toByte(), 0x51,
            0x42, 0xC1.toByte(), 0xBF.toByte(), 0xF6.toByte(), 0xD1.toByte(), 0x66, 0x65,
            0x0A, 0x66, 0x34, 0x11,
        )
        val cdkId = "Application Lock\u0000".toByteArray()
        val cdk = byteArrayOf(
            0x6B, 0xC5.toByte(), 0x51, 0xD1.toByte(), 0x12, 0xF7.toByte(), 0xE3.toByte(), 0x42,
            0xBD.toByte(), 0xDC.toByte(), 0xFB.toByte(), 0x5D, 0x79, 0x4E, 0x5A, 0xD6.toByte(),
            0x54, 0xD1.toByte(), 0xC9.toByte(), 0x90.toByte(), 0x28, 0x05, 0xCF.toByte(),
            0x5E, 0x4C, 0x83.toByte(), 0x63, 0xFB.toByte(), 0xC2.toByte(), 0x3C, 0xF6.toByte(),
            0xAB.toByte(),
        )
        val authKey1Id = "AUTH1\u0000".toByteArray()
        val authKey1 = byteArrayOf(
            0xDA.toByte(), 0x2E, 0x35, 0xB6.toByte(), 0xCB.toByte(), 0x96.toByte(), 0x2B,
            0x5F, 0x9F.toByte(), 0x34, 0x1F, 0xD1.toByte(), 0x47, 0x41, 0xA0.toByte(), 0x4D,
            0xA4.toByte(), 0x09, 0xCE.toByte(), 0xE8.toByte(), 0x35, 0x48, 0x3C, 0x60,
            0xFB.toByte(), 0x13, 0x91.toByte(), 0xE0.toByte(), 0x9E.toByte(), 0x95.toByte(),
            0xB2.toByte(), 0x7F,
        )

        return try {
            val model = NBDeviceSecurityModel.get((dev.capabilities?.securityModel ?: 0).toInt())
            when (model) {
                NBDeviceSecurityModel.Model65200CakOnly -> dev.openSession(cakId, cak)
                NBDeviceSecurityModel.Model65200CakCdk -> {
                    runCatching {
                        dev.openSession(cdkId, cdk)
                        dev.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, null)
                        dev.closeSession()
                    }
                    dev.openSession(cakId, cak)
                    dev.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, cdk)
                    dev.closeSession()
                    dev.openSession(cdkId, cdk)
                }

                NBDeviceSecurityModel.Model65100 -> dev.openSession(authKey1Id, authKey1)
                NBDeviceSecurityModel.ModelNone, null -> { /* no auth required */
                }
            }
            logDebug("$tag openSession() → OK")
            true
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag openSession() → Exception: ${e.message}")
            false
        }
    }

    /**
     * Programs anti-spoof (and, where supported, anti-latent) into the device.
     *
     * The threshold comes from the device itself via [chooseThreshold] rather than a hardcoded
     * constant, because the scale differs per module. Once set, the *device* applies it and
     * answers through `NBBiometricsStatus.SPOOF_DETECTED` — nothing in this class compares
     * scores against it.
     *
     * May throw; callers catch and log.
     */
    private fun configureAntispoof() {
        antispoofActive = false
        val dev = device ?: return
        val threshold = chooseThreshold(dev)
        dev.setParameter(CONFIGURE_ANTISPOOF.toLong(), ENABLE_ANTISPOOF)
        dev.setParameter(CONFIGURE_ANTISPOOF_THRESHOLD.toLong(), threshold)
        activeAntispoofThreshold = threshold
        antispoofActive = true
        logDebug("$tag anti-spoof on, threshold=$threshold ($ANTISPOOF_STRICTNESS)")
    }

    /**
     * Background subtraction — the anti-latent defence, which stops a print revived from
     * residue on the platen being read as a live finger.
     *
     * Setting parameter 105 is only half of it: the device then subtracts a background
     * reference that has to be captured with `scanBGImage()` while the platen is EMPTY.
     * Enabling the parameter without that reference lets the first scan of a run succeed and
     * makes every scan after it fail with "Invalid operation" — a finger has touched the sensor
     * by then and there is no background to subtract against. So this runs once at init, and if
     * the background capture fails the parameter is turned back off rather than left enabled
     * against a reference that does not exist.
     */
    private fun configureAntiLatent(dev: NBDevice) {
        val type = runCatching { dev.type }.getOrNull()
        if (type !in ONE_TIME_BG_TYPES) {
            logDebug("$tag anti-latent skipped: $type does not support one-time background capture")
            return
        }
        val format = scanFormatInfo
        if (format == null) {
            Log.w(tag, "$tag anti-latent skipped: no scan format to capture a background with")
            return
        }
        runCatching {
            dev.setParameter(CONFIGURE_SUBTRACT_BACKGROUND.toLong(), ENABLE_ANTI_LATENT)
            val bg = dev.scanBGImage(format)
            logDebug("$tag anti-latent on, background captured (${bg?.status})")
        }.onFailure {
            Log.w(tag, "Anti-latent background capture failed (${it.message}); disabling it")
            runCatching { dev.setParameter(CONFIGURE_SUBTRACT_BACKGROUND.toLong(), DISABLE_ANTI_LATENT) }
        }
    }

    /**
     * Picks the scan format to capture at.
     *
     * The native formats on these modules are 385 dpi and only some are 500; the SDK also
     * publishes an upscaled 500 dpi twin of each. ISO/IEC 19794-4 and ANSI/NIST are written
     * around 500 dpi, so `formats[0]` — whatever the device happened to list first — was a
     * gamble that could enrol at 385. Prefer 500 dpi, then the largest capture area.
     */
    private fun chooseFormat(formats: Array<out NBDeviceScanFormatInfo>): NBDeviceScanFormatInfo {
        logDebug("$tag supported scan formats: " + formats.joinToString {
            "${it.format}/${it.formatType} ${it.width}x${it.height}@${it.horizontalResolution}dpi"
        })
        val chosen = formats.filter { it.horizontalResolution >= 500 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: formats.maxByOrNull { it.width.toLong() * it.height }
            ?: formats[0]
        logDebug(
            "$tag scanning at ${chosen.width}x${chosen.height} @${chosen.horizontalResolution}dpi " +
                    "(${chosen.format}/${chosen.formatType})"
        )
        return chosen
    }

    /**
     * Probe the documented percentage range and rank the thresholds the device actually
     * returns, instead of assuming which end of 1.0f..3.3f is stricter.
     */
    private fun chooseThreshold(device: NBDevice): Int {
        val probes = ANTISPOOF_PROBE_POINTS.mapNotNull { pct ->
            runCatching { pct to device.getLivenessThreshold(pct) }.getOrNull()
        }
        if (probes.isEmpty()) {
            Log.w(tag, "getLivenessThreshold unsupported on this module; using fallback")
            return ANTISPOOF_THRESHOLD_FALLBACK
        }
        Log.i(tag, "liveness threshold curve: " + probes.joinToString { "${it.first}%=${it.second}" })

        val ranked = probes.map { it.second }.distinct().sorted()
        return when (ANTISPOOF_STRICTNESS) {
            Strictness.STRICT -> ranked.last()
            Strictness.PERMISSIVE -> ranked.first()
            Strictness.BALANCED -> ranked[ranked.size / 2]
        }
    }

    /**
     * Builds the anti-spoof rejection for a finished pass, or null if the presentation passed.
     *
     * The verdict is the *result* status and nothing else. Spoof surfaces at two layers with
     * different enums — `NBDeviceScanStatus.SPOOF` at device level during preview,
     * `NBBiometricsStatus.SPOOF_DETECTED` at biometrics level in the result — and only the
     * latter is the device's considered answer. Preview frames are mid-placement samples and
     * are used for the on-screen tint alone.
     *
     * @param status  Status returned by the `extract` or `identify` call that just completed.
     */
    private fun spoofRejection(status: NBBiometricsStatus?): ScannerEvent.SpoofDetected? = when (status) {
        NBBiometricsStatus.LATENT_DETECTED -> ScannerEvent.SpoofDetected(
            readerNo = readerNo,
            kind = ScannerEvent.SpoofDetected.SpoofKind.LATENT_PRINT,
            detail = "Latent print detected — that is residue left on the sensor, not a live " +
                    "finger. Wipe the pad with a dry cloth and scan again.",
        )

        NBBiometricsStatus.SPOOF_DETECTED -> ScannerEvent.SpoofDetected(
            readerNo = readerNo,
            kind = ScannerEvent.SpoofDetected.SpoofKind.FAKE_FINGER,
            detail = buildString {
                append("Fake finger detected. Liveness $peakLiveness, needed $activeAntispoofThreshold")
                if (activeAntispoofThreshold > peakLiveness) {
                    append(" (short by ${activeAntispoofThreshold - peakLiveness})")
                }
                append('.')
                // Liveness on a thermal sensor tracks heat transfer, so a real finger resting
                // lightly scores like a poor conductor. Say so, because the operator otherwise
                // has no way to tell a light press from a genuine rejection.
                if (peakDetect >= FULL_CONTACT_DETECT) {
                    append(" Contact area was full, so if this was a real finger press down firmly and hold still.")
                }
            },
        )

        else -> null
    }

    /**
     * Clears the per-scan signals and re-applies the anti-spoof parameters.
     *
     * Both NextBiometrics Android samples call their `enableSpoof()` immediately before every
     * `extract()` / `identify()` rather than once at init, so the same is done here — configuring
     * only in [doInit] is not enough to guarantee the setting is live for a given scan.
     */
    private fun resetScanSignals() {
        peakLiveness = 0
        peakDetect = 0
        lastLiveness = 0
        lastDetect = 0
        sensorLooksDirty = false
        runCatching { configureAntispoof() }
            .onFailure { Log.w(tag, "Anti-spoof re-apply failed: ${it.message}") }
    }

    /**
     * Logs the anti-spoof signals for the pass that just ran, and flags a soiled pad.
     *
     * A TIMEOUT paired with a high resting finger-detect means the platen never looked empty,
     * so the device never started a scan — a dirty pad rather than an absent user.
     *
     * @return A [ScannerEvent.SensorDirty] when the pad looks soiled, otherwise null.
     */
    private fun assessScanOutcome(status: NBBiometricsStatus?): ScannerEvent.SensorDirty? {
        logDebug(
            "$tag scan outcome: status=$status antispoof=$antispoofActive " +
                    "liveness(last=$lastLiveness peak=$peakLiveness threshold=$activeAntispoofThreshold) " +
                    "detect(last=$lastDetect peak=$peakDetect)"
        )
        val dirtyByTimeout = status == NBBiometricsStatus.TIMEOUT && lastDetect > DIRTY_SENSOR_DETECT
        if (!dirtyByTimeout && !sensorLooksDirty) return null

        Log.w(tag, "$tag sensor looks dirty: resting detect=$lastDetect (clean reads under $DIRTY_SENSOR_DETECT)")
        return ScannerEvent.SensorDirty(
            readerNo = readerNo,
            detail = "Reader ${readerNo + 1} never saw an empty sensor — wipe both pads with a " +
                    "dry cloth, then try again.",
        )
    }

    /**
     * Loads or generates calibration data for devices that require it.
     * Calibration blobs are stored under the external Downloads/NBData/ directory.
     *
     * @param dev  The device that requires calibration data.
     */
    private fun applyCalibrationData(dev: NBDevice) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NBData",
        ).also { it.mkdirs() }
        val blobFile = File(dir, "${dev.serialNumber}_calblob.bin")

        if (!blobFile.exists()) {
            runCatching {
                val data = dev.GenerateCalibrationData()
                blobFile.writeBytes(data)
                logDebug("$tag applyCalibrationData() → generated and saved calibration blob")
            }.onFailure { logError("$tag applyCalibrationData() → could not generate blob: ${it.message}") }
        }

        if (blobFile.exists()) {
            val bytes = blobFile.readBytes()
            dev.SetBlobParameter(NBDevice.BLOB_PARAMETER_CALIBRATION_DATA, bytes)
            logDebug("$tag applyCalibrationData() → applied ${bytes.size} bytes")
        } else {
            logError("$tag applyCalibrationData() → calibration blob missing, proceeding without it")
        }
    }

// ──────────────────────────────────────────────────────────────────────────
// Private: template persistence
// ──────────────────────────────────────────────────────────────────────────

    /**
     * Loads all encrypted ISO-template files from [templateDir], decrypts them, and returns
     * a list of entries suitable for passing to the SDK's identify() call.
     *
     * @param ctx          NBBiometricsContext used to deserialize raw template bytes.
     * @param templateDir  Absolute path of the directory containing .dat template files.
     * @return             A list of key-value pairs (label → template) for the identify() call.
     */
    private fun loadStoredTemplates(
        ctx: NBBiometricsContext,
        templateDir: String,
    ): List<AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>> {
        val dir = File(templateDir)
        if (!dir.exists() || !dir.isDirectory) {
            logError("$tag loadStoredTemplates() → directory not found: $templateDir")
            return emptyList()
        }

        val encKey = ScannerApp.getInstance().key ?: ""
        val bvn = File(templateDir).name  // bvnNumber is the dir name

        // Only template files: this directory can also hold artifacts written by other flows,
        // and handing a JPEG to loadTemplate() would fail the whole verification.
        return dir.listFiles { f -> f.isFile && f.name.endsWith(TEMPLATE_SUFFIX) }
            ?.mapIndexedNotNull { index, file ->
                runCatching {
//                    val decrypted = KeyStorePortable.decryptData(file.path, bvn, encKey)
                    val decrypted = File(file.path).readBytes()
                    decrypted?.let { bytes ->
                        val template = ctx.loadTemplate(NBBiometricsTemplateType.ISO, IsoTemplate.sdk(bytes))
                        AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>("Template$index", template)
                    }
                }.getOrNull()
            }
            ?: emptyList()
    }

    /** A registration that matched an already-registered identity. */
    private data class DuplicateHit(val uniqueId: String, val score: Int)

    /**
     * Checks a freshly extracted template against every *other* unique ID's stored templates.
     *
     * Only templates held on this device are compared, so this catches a repeat registration on
     * the same terminal — not one performed on another. That is the same scope the reference
     * implementation works at, and it is what a demo or certification run actually exercises.
     *
     * Runs before anything is written, so a rejected registration leaves no files behind. Any
     * template that fails to decrypt is skipped rather than failing the whole check: a single
     * unreadable file from an older key must not silently disable duplicate detection for the
     * rest of the gallery.
     *
     * @return The first match found, or null when these fingers are new to this device.
     */
    private fun findDuplicateRegistration(
        ctx: NBBiometricsContext,
        template: NBBiometricsTemplate,
        bvnNumber: String,
    ): DuplicateHit? {
        val encKey = ScannerApp.getInstance().key ?: ""
        val others = context.filesDir.listFiles { f ->
            f.isDirectory && f.name != bvnNumber && f.name != VERIFICATIONS_DIR
        } ?: return null

        for (dir in others) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(TEMPLATE_SUFFIX) }
                ?: continue
            for (file in files) {
                val stored = runCatching {
                    KeyStorePortable.decryptData(file.path, dir.name, encKey)
                        ?.let { ctx.loadTemplate(NBBiometricsTemplateType.ISO, it) }
                }.getOrNull() ?: continue

                val result = runCatching {
                    ctx.verify(template, stored, NBBiometricsSecurityLevel.HIGH)
                }.getOrNull() ?: continue

                if (result.status == NBBiometricsStatus.OK) {
                    logError("$tag duplicate: already registered under ${dir.name} (score ${result.score})")
                    return DuplicateHit(dir.name, result.score)
                }
            }
        }
        logDebug("$tag no duplicate across ${others.size} other registration(s)")
        return null
    }

    /**
     * Writes the finger that authorised a verification under `verifications/<uniqueId>/`.
     *
     * Kept separate from the registration directory so the two never mix: registration
     * templates are the identity, these are per-transaction evidence, and
     * [findDuplicateRegistration] and [loadStoredTemplates] both skip this directory.
     *
     * @param onSaved  Called for each artifact written, so the caller can emit a FileSaved event.
     * @return The BMP path when one was written, or null.
     */
    private fun saveVerificationCapture(
        image: ByteArray?,
        bvnNumber: String,
        enableBmpExport: Boolean,
        onSaved: (String, ScannerEvent.FileSaved.FileType) -> Unit,
    ): String? {
        val px = image ?: run {
            logError("$tag verification capture requested but no image was available")
            return null
        }
        val dir = File(File(context.filesDir, VERIFICATIONS_DIR), bvnNumber)
            .also { it.mkdirs() }
        val timestamp = System.currentTimeMillis()

        var bmpPath: String? = null
        runCatching {
            convertToArgbBitmap(px)?.let { bmp ->
                saveBitmapJpeg(bmp, "${dir.path}/", timestamp)?.let { path ->
                    bmpPath = path
                    onSaved(path, ScannerEvent.FileSaved.FileType.BITMAP)
                }
            }
            if (enableBmpExport) {
                saveRawBmpFile(px, "${dir.path}/", timestamp)?.let { path ->
                    onSaved(path, ScannerEvent.FileSaved.FileType.BMP)
                }
            }
        }.onFailure { logError("$tag verification capture save failed: ${it.message}") }

        logDebug("$tag verification capture saved under ${dir.path}")
        return bmpPath
    }

    /**
     * Serializes, optionally encrypts, and writes an ISO biometric template to disk.
     *
     * When [skipFirebaseActions] is true the template is written in plain binary (useful for
     * offline / developer mode). Otherwise, the bytes are AES-encrypted with [KeyStorePortable].
     *
     * The saved filename follows the pattern:
     * `<timestamp><readerNo>-ISO-Template.dat`
     *
     * @return The absolute path of the saved file, or null if saving failed.
     */
    private fun saveEncryptedTemplate(
        ctx: NBBiometricsContext,
        template: NBBiometricsTemplate,
        templateDir: String,
        bvnNumber: String,
        encryptionKey: String,
        skipFirebaseActions: Boolean,
    ): String? {
        return try {
            File(templateDir).mkdirs()

            val isoTemplate = IsoTemplate.conformant(ctx.saveTemplate(template))
//            val toWrite = KeyStorePortable.encryptData(isoTemplate, bvnNumber, encryptionKey)
            /*val toWrite = if (skipFirebaseActions) {
                rawBytes
            } else {
                KeyStorePortable.encryptData(rawBytes, bvnNumber, encryptionKey)
            }*/

            val fileName = buildTimestampedFileName() + "$readerNo-ISO-Template.dat"
            val filePath = "$templateDir$fileName"
            FileOutputStream(filePath).use { it.write(isoTemplate) }
            logDebug("$tag saveEncryptedTemplate() → saved to $filePath")
            filePath
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag saveEncryptedTemplate() → Exception: ${e.message}")
            null
        }
    }

// ──────────────────────────────────────────────────────────────────────────
// Private: image saving helpers
// ──────────────────────────────────────────────────────────────────────────

    /**
     * Writes raw image bytes (e.g. WSQ) to a file.
     *
     * @param data       Raw bytes to write.
     * @param extension  File extension (without dot), e.g. "wsq".
     * @param dir        Target directory (with trailing `/`).
     * @param timestamp  Millisecond timestamp used to form a unique filename.
     * @return           Absolute path of the saved file, or null on failure.
     */
    private fun saveRawFile(data: ByteArray?, extension: String = "wsq", dir: String, timestamp: Long): String? {
        if (data == null) return null
        return try {
            val file = File(prepareDir(dir), "$readerNo$timestamp.$extension")
            FileOutputStream(file).use { it.write(data) }
            logDebug("$tag saveRawFile() → ${file.path}")
            file.path
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag saveRawFile() → Exception: ${e.message}")
            null
        }
    }

    /**
     * Resolves [dir] and makes sure it exists and is writable, throwing with the reason if not.
     *
     * The callers used to rely on `File(dir).mkdirs()` and then hand a concatenated string to
     * [FileOutputStream]. That hid two failures behind one confusing symptom: a relative `dir`
     * resolves against the process working directory — `/` on Android, read-only — and an
     * unchecked `mkdirs()` returning false left the write to fail later. Both surfaced as
     * `open failed: ENOENT` from the stream, pointing at the file rather than the directory.
     *
     * @param dir  Absolute directory path, with or without a trailing separator.
     * @return     The directory, guaranteed to exist.
     */
    private fun prepareDir(dir: String): File {
        val target = File(dir)
        require(target.isAbsolute) {
            "capture directory must be absolute, got '$dir' — a relative path resolves against '/'"
        }
        if (!target.exists() && !target.mkdirs() && !target.exists()) {
            throw IOException("could not create capture directory ${target.path}")
        }
        if (!target.isDirectory) throw IOException("capture path is not a directory: ${target.path}")
        return target
    }

    /**
     * Compresses a bitmap to JPEG (quality 90) and writes it to disk.
     *
     * @param bitmap     Source bitmap.
     * @param dir        Target directory (with trailing `/`).
     * @param timestamp  Millisecond timestamp for a unique filename.
     * @return           Absolute path of the saved JPEG, or null on failure.
     */
    private fun saveBitmapJpeg(bitmap: Bitmap, dir: String, timestamp: Long): String? {
        return try {
            val file = File(prepareDir(dir), "$readerNo$timestamp.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            logDebug("$tag saveBitmapJpeg() → ${file.path}")
            file.path
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag saveBitmapJpeg() → Exception: ${e.message}")
            null
        }
    }

    /**
     * Encodes a raw grayscale image as an 8-bit BMP file (bottom-up, with a 256-entry
     * grayscale color table) and writes it to [dir].
     *
     * BMP format chosen for maximum compatibility with third-party AFIS tools.
     *
     * @return  Absolute path of the saved BMP, or null on failure.
     */
    private fun saveRawBmpFile(image: ByteArray, dir: String, timestamp: Long): String? {
        val fmt = scanFormatInfo ?: return null
        val w = fmt.width
        val h = fmt.height
        val rowStride = (w + 3) and 3.inv()  // round up to 4-byte boundary
        val pixelDataSize = rowStride * h
        val fileSize = 14 + 40 + 1024 + pixelDataSize

        val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

        // BMP file header (14 bytes)
        buf.put(0x42); buf.put(0x4D)  // "BM"
        buf.putInt(fileSize)
        buf.putShort(0); buf.putShort(0)  // reserved
        buf.putInt(1078)                   // pixel data offset = 14 + 40 + 1024

        // DIB header – BITMAP INFO HEADER (40 bytes)
        buf.putInt(40); buf.putInt(w); buf.putInt(h)
        buf.putShort(1); buf.putShort(8)   // planes=1, bpp=8
        buf.putInt(0); buf.putInt(pixelDataSize)
        buf.putInt(0); buf.putInt(0)       // X/Y pixels per metre
        buf.putInt(256); buf.putInt(0)     // colors in table, important colors

        // Grayscale colour table: 256 × (B, G, R, 0x00)
        for (i in 0..255) {
            buf.put(i.toByte()); buf.put(i.toByte()); buf.put(i.toByte()); buf.put(0)
        }

        // Pixel data — BMP rows are stored bottom-up
        val padding = ByteArray(rowStride - w)
        for (y in h - 1 downTo 0) {
            buf.put(image, y * w, w)
            buf.put(padding)
        }

        return try {
            val file = File(prepareDir(dir), "$readerNo$timestamp.bmp")
            FileOutputStream(file).use { it.write(buf.array()) }
            logDebug("$tag saveRawBmpFile() → ${file.path}")
            file.path
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag saveRawBmpFile() → Exception: ${e.message}")
            null
        }
    }

// ──────────────────────────────────────────────────────────────────────────
// Private: image conversion
// ──────────────────────────────────────────────────────────────────────────

    /**
     * Converts a raw grayscale byte array from the sensor into an ARGB_8888 [Bitmap].
     * Each byte value (0–255) is mapped to an equal R/G/B gray pixel with full alpha.
     *
     * @param image  Grayscale image bytes with dimensions from [scanFormatInfo].
     */
    private fun convertToArgbBitmap(image: ByteArray): Bitmap? {
        val fmt = scanFormatInfo ?: return null
        val pixelCount = fmt.width * fmt.height
        // The buffer is allocated from image.size but read back at width × height, so a short
        // frame would index past the end inside createBitmap — on the SDK's callback thread,
        // where the crash has no useful stack. A long one is fine to trim.
        if (fmt.width <= 0 || fmt.height <= 0 || image.size < pixelCount) return null

        val pixels = IntBuffer.allocate(pixelCount)
        for (i in 0 until pixelCount) {
            val grey = image[i].toInt() and 0xFF
            pixels.put(Color.argb(255, grey, grey, grey))
        }
        return Bitmap.createBitmap(pixels.array(), fmt.width, fmt.height, Bitmap.Config.ARGB_8888)
    }

// ──────────────────────────────────────────────────────────────────────────
// Private: utilities
// ──────────────────────────────────────────────────────────────────────────

    /** Returns a filename-safe timestamp string like "2024-05-17-14-32-01". */
    private fun buildTimestampedFileName(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ENGLISH)
        return fmt.format(Date())
    }
}
