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
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceState
import com.nextbiometrics.system.NextBiometricsException
import com.scanner.app.ScannerApp
import com.scanner.updated.model.ReaderResult
import com.scanner.updated.model.ScannerEvent
import com.scanner.utils.KeyStorePortable
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
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

    // ── Anti-spoof configuration ──────────────────────────────────────────────
    private val isSpoofEnabled = true
    private var spoofScore = MAX_ANTISPOOF_THRESHOLD
    private var isValidSpoofScore = false

    companion object {
        const val MAX_ANTISPOOF_THRESHOLD = 1000
        private const val DEFAULT_ANTISPOOF_THRESHOLD = 363
        private const val CONFIGURE_ANTISPOOF = 108
        private const val CONFIGURE_ANTISPOOF_THRESHOLD = 109
        private const val ENABLE_ANTISPOOF = 1
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
            scanFormatInfo = formats[0]

            if (isSpoofEnabled) {
                runCatching { configureAntispoof() }
                    .onFailure { Log.w(tag, "Anti-spoof not supported on this device: ${it.message}") }
            }

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
     */
    fun scanAndExtractFlow(
        savePath: String,
        scanningType: ScanningType,
        bvnNumber: String,
        encryptionKey: String,
        skipFirebaseActions: Boolean,
        enableBmpExport: Boolean,
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
                    spoofScore = MAX_ANTISPOOF_THRESHOLD
                    isValidSpoofScore = false

                    trySend(ScannerEvent.Message(readerNo, "Place your finger on the sensor.", false))

                    // Build preview listener that forwards frames to this flow.
                    val previewListener = buildPreviewListener { event ->
                        trySend(event)
                    }

                    // Block the IO thread, canceled via invokeOnCancellation → cancelScan().
                    val extractResult = runBlockingSdk {
                        ctx.extract(
                            NBBiometricsTemplateType.ISO,
                            NBBiometricsFingerPosition.UNKNOWN,
                            scanFormatInfo,
                            previewListener,
                        )
                    }

                    ensureActive()

                    send(ScannerEvent.ExtractionDone(readerNo, extractResult.status))

                    if (extractResult.status != NBBiometricsStatus.OK) {
                        val msg = "Extraction failed: ${extractResult.status}"
                        send(ScannerEvent.Message(readerNo, msg, true))
                        close(Exception(msg))
                        return@callbackFlow
                    }

                    // Anti-spoof check after extraction.
                    if (isSpoofEnabled && isValidSpoofScore && spoofScore <= DEFAULT_ANTISPOOF_THRESHOLD) {
                        send(ScannerEvent.SpoofDetected(readerNo))
                        close(Exception("Spoof detected on reader $readerNo"))
                        return@callbackFlow
                    }

                    val template = extractResult.template

                    // Compute NFIQ quality from the last preview image.
                    previewListener.lastImage?.let { img ->
                        quality = NBDevice.GetImageQuality(
                            img, scanFormatInfo!!.width, scanFormatInfo!!.height,
                            500, NBDeviceImageQualityAlgorithm.NFIQ,
                        )
                    }

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

                    // Save JPEG preview bitmap.
                    previewListener.lastImage?.let { img ->
                        val bmp = convertToArgbBitmap(img)
                        bitmapPath = saveBitmapJpeg(bmp, savePath, timestamp)
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
                            runCatching { saveRawBmpFile(img, savePath, timestamp) }
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
                            )
                        )
                    )
                }

                // ── Verification: identify against stored templates ─────────────
                ScanningType.VERIFICATION -> {
                    spoofScore = MAX_ANTISPOOF_THRESHOLD
                    isValidSpoofScore = false

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
                        verifyCtx.identify(
                            NBBiometricsTemplateType.ISO,
                            NBBiometricsFingerPosition.UNKNOWN,
                            scanFormatInfo,
                            previewListener,
                            templates.iterator(),
                            NBBiometricsSecurityLevel.HIGH,
                        )
                    }

                    ensureActive()
                    Log.d(
                        tag,
                        "identify result: status=${identifyResult.status}, templateId=${identifyResult.templateId}, score=${identifyResult.score}"
                    )
                    send(ScannerEvent.IdentificationDone(readerNo, identifyResult))

                    if (isSpoofEnabled && isValidSpoofScore && spoofScore <= DEFAULT_ANTISPOOF_THRESHOLD) {
                        send(ScannerEvent.SpoofDetected(readerNo))
                        close(Exception("Spoof detected on reader $readerNo"))
                        return@callbackFlow
                    }

                    val statusMsg = when (identifyResult.status) {
                        NBBiometricsStatus.OK ->
                            "Fingerprint verified (template: ${identifyResult.templateId})."

                        NBBiometricsStatus.MATCH_NOT_FOUND ->
                            "No matching fingerprint found."

                        else -> "Identification status: ${identifyResult.status}"
                    }
                    send(ScannerEvent.Message(readerNo, statusMsg, identifyResult.status != NBBiometricsStatus.OK))

                    send(
                        ScannerEvent.ScanCompleted(
                            ReaderResult(
                                readerNo = readerNo,
                                extractStatus = null,
                                identifyResult = identifyResult,
                                wsqPath = null,
                                bitmapPath = null,
                                templatePath = null,
                                quality = quality,
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

        override fun preview(event: NBBiometricsScanPreviewEvent) {
            spoofScore = event.livenessScoreValue
            isValidSpoofScore = spoofScore in 1..MAX_ANTISPOOF_THRESHOLD
            if (!isValidSpoofScore) spoofScore = 0

            event.image?.let { img ->
                lastImage = img
                val bmp = convertToArgbBitmap(img)
                emit(
                    ScannerEvent.PreviewFrame(
                        readerNo = readerNo,
                        image = img,
                        bitmap = bmp,
                        status = event.scanStatus,
                        previewType = previewType,
                    )
                )
            }
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
     * Configures anti-spoofing on the device with the default threshold.
     * Throws if the device does not support anti-spoof; callers should catch and log.
     */
    private fun configureAntispoof() {
        device?.setParameter(CONFIGURE_ANTISPOOF.toLong(), ENABLE_ANTISPOOF)
        device?.setParameter(CONFIGURE_ANTISPOOF_THRESHOLD.toLong(), DEFAULT_ANTISPOOF_THRESHOLD)
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

        return dir.listFiles()
            ?.mapIndexedNotNull { index, file ->
                runCatching {
                    val decrypted = KeyStorePortable.decryptData(file.path, bvn, encKey)
                    decrypted?.let { bytes ->
                        val template = ctx.loadTemplate(NBBiometricsTemplateType.ISO, bytes)
                        AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>("Template$index", template)
                    }
                }.getOrNull()
            }
            ?: emptyList()
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

            val rawBytes = ctx.saveTemplate(template)
            val toWrite = KeyStorePortable.encryptData(rawBytes, bvnNumber, encryptionKey)
            /*val toWrite = if (skipFirebaseActions) {
                rawBytes
            } else {
                KeyStorePortable.encryptData(rawBytes, bvnNumber, encryptionKey)
            }*/

            val fileName = buildTimestampedFileName() + "$readerNo-ISO-Template.dat"
            val filePath = "$templateDir$fileName"
            FileOutputStream(filePath).use { it.write(toWrite) }
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
            File(dir).mkdirs()
            val path = "$dir${readerNo}$timestamp.$extension"
            FileOutputStream(path).use { it.write(data) }
            logDebug("$tag saveRawFile() → $path")
            path
        } catch (e: Exception) {
            NewRelic.recordHandledException(e)
            logError("$tag saveRawFile() → Exception: ${e.message}")
            null
        }
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
            File(dir).mkdirs()
            val path = "$dir${readerNo}$timestamp.jpg"
            FileOutputStream(path).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            logDebug("$tag saveBitmapJpeg() → $path")
            path
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
            File(dir).mkdirs()
            val path = "$dir${readerNo}${timestamp}.bmp"
            FileOutputStream(path).use { it.write(buf.array()) }
            logDebug("$tag saveRawBmpFile() → $path")
            path
        } catch (e: Exception) {
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
    private fun convertToArgbBitmap(image: ByteArray): Bitmap {
        val fmt = scanFormatInfo
        val pixels = IntBuffer.allocate(image.size)
        for (byte in image) {
            val grey = byte.toInt() and 0xFF
            pixels.put(Color.argb(255, grey, grey, grey))
        }
        return Bitmap.createBitmap(
            pixels.array(),
            fmt?.width ?: 0,
            fmt?.height ?: 0,
            Bitmap.Config.ARGB_8888,
        )
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
