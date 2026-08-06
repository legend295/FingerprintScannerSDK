package com.scanner.updated.reader

import android.content.Context
import android.util.Log
import com.common.apiutil.powercontrol.PowerControl
import com.newrelic.agent.android.NewRelic
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDevices
import com.scanner.updated.model.ReaderResult
import com.scanner.updated.model.ScannerEvent
import com.scanner.updated.model.ScannerState
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.enums.ScanningType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the complete lifecycle of a dual-reader fingerprint session.
 *
 * ### Dual-reader parity guarantee
 * Both readers are launched as sibling [kotlinx.coroutines.async] coroutines inside a shared
 * [kotlinx.coroutines.coroutineScope]. If **either** reader throws an exception (hardware fault,
 * spoof detection, SDK error), [coroutineScope] immediately cancels the other coroutine, which
 * propagates cancellation into [FingerprintReaderWrapper.runBlockingSdk]'s
 * `invokeOnCancellation` → `device.cancelScan()`. The scan is therefore always all-or-nothing:
 * both succeed or both stop.
 *
 * ### Observable surfaces
 * - [state]  — current [ScannerState]; collect in the UI to drive layout changes.
 * - [events] — stream of [ScannerEvent] from both readers; useful for live previews and messages.
 *
 * ### Typical usage
 * ```kotlin
 * val manager = ScannerSessionManager(context)
 * manager.configure(...)
 * lifecycleScope.launch { manager.state.collect { render(it) } }
 * lifecycleScope.launch { manager.events.collect { handle(it) } }
 * manager.initialize()          // step 1: USB power cycle + SDK init
 * manager.startScan(savePath)   // step 2: called after state == Ready
 * // ...
 * manager.release()             // always call from onDestroy
 * ```
 *
 * @param context  Application context; must not be an Activity context to avoid leaks.
 */
internal class ScannerSessionManager(
    private val context: Context,
) {
    private val tag = "ScannerSessionManager"

    // ── Low-power flag ────────────────────────────────────────────────────────
    // True when the hardware is sleeping, by either:
    //   (a) an explicit enableLowPowerMode() call from onDestroy, or
    //   (b) a ScannerEvent.DeviceInSleepMode event detected during a scan.
    // "Invalid operation" ReaderErrors are regular SDK errors and do NOT set this flag.
    // Cleared when startScan() is called (device is being woken for a new attempt).
    // resetToReady() checks this flag and forces a full initialize() when true.
    // Exposed as read-only so UpdatedScannerActivity can read it without a separate flag.
    var isLowPowerEnabled = false
        private set

    // ── Observable state ──────────────────────────────────────────────────────

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)

    /** Single source of truth for the session state. Collect on the main dispatcher in the UI. */
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ScannerEvent>(extraBufferCapacity = 128)

    /**
     * Stream of all events from both readers.
     * Multiple collectors are allowed (each receives every event independently).
     */
    val events: SharedFlow<ScannerEvent> = _events.asSharedFlow()

    // ── Session-scoped coroutine machinery ────────────────────────────────────

    /**
     * Long-lived scope for the manager itself. Uses [SupervisorJob] so that individual
     * session jobs can fail without tearing down the whole manager.
     */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Handle to the currently running init or scan job — canceled on [release] or restart. */
    private var activeJob: Job? = null

    /**
     * Independent watchdog for [initialize]. Launched on [managerScope] as a sibling (not a child)
     * of [activeJob], so it fires even when [activeJob] is blocked inside a non-suspending SDK
     * call like [NBDevices.initialize] or [PowerControl.usbPower]. Canceled as soon as the init
     * job resolves normally.
     */
    private var watchdogJob: Job? = null

    /**
     * Independent watchdog for [startScan]. Fires after [SCAN_WATCHDOG_TIMEOUT_MS] if the scan
     * has not completed. When one reader throws and the other reader's blocking SDK call doesn't
     * respond to [FingerprintReaderWrapper.cancel], [activeJob] can be stuck inside
     * [kotlinx.coroutines.coroutineScope] indefinitely. This watchdog runs on [managerScope]
     * independently and forces [ScannerState.Failed] to unfreeze the UI.
     */
    private var scanWatchdogJob: Job? = null

    // ── Hardware wrappers ─────────────────────────────────────────────────────

    private val reader0 = FingerprintReaderWrapper(context, 0)
    private val reader1 = FingerprintReaderWrapper(context, 1)

    // ── Session configuration ─────────────────────────────────────────────────

    private var scanningType: ScanningType = ScanningType.REGISTRATION
    private var bvnNumber: String = "common"
    private var encryptionKey: String = ""
    private var skipFirebaseActions: Boolean = false
    private var enableBmpExport: Boolean = false

    // ──────────────────────────────────────────────────────────────────────────
    // Configuration
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sets all operational parameters for the next scan session.
     * Must be called before [initialize] and before every [startScan] if parameters change.
     *
     * @param scanningType         Whether to run a registration or verification scan.
     * @param bvnNumber            Unique user identifier; used as the template subdirectory.
     * @param encryptionKey        App-level key used by [com.scanner.utils.KeyStorePortable].
     * @param skipFirebaseActions  When true, templates are stored in plaintext (dev/offline mode).
     * @param enableBmpExport      When true, a BMP copy is written alongside each JPEG preview.
     */
    fun configure(
        scanningType: ScanningType,
        bvnNumber: String,
        encryptionKey: String,
        skipFirebaseActions: Boolean = false,
        enableBmpExport: Boolean = false,
    ) {
        this.scanningType = scanningType
        this.bvnNumber = bvnNumber
        this.encryptionKey = encryptionKey
        this.skipFirebaseActions = skipFirebaseActions
        this.enableBmpExport = enableBmpExport
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts hardware initialization asynchronously.
     *
     * Steps:
     * 1. USB power cycle (off → 1 s delay → on → 1 s delay).
     * 2. [NBDevices.initialize] with up to 25 s polling.
     * 3. Wait for two [com.nextbiometrics.devices.NBDevice] instances to appear.
     * 4. [FingerprintReaderWrapper.init] on each reader concurrently.
     *
     * Emits [ScannerState.Initializing] immediately, then [ScannerState.Ready] on success or
     * [ScannerState.Failed] with a reason string on any failure.
     *
     * Calling [initialize] while an init job is already running cancels the previous one.
     */
    fun initialize() {
        cancelActiveJob()
        _state.value = ScannerState.Initializing

        // Watchdog: fires on its own thread after INIT_TIMEOUT_MS even when activeJob is
        // blocked inside a non-suspending SDK call (PowerControl.usbPower, NBDevices.initialize).
        // withTimeout alone cannot interrupt a blocked thread — it only delivers at suspension
        // points — so this sibling coroutine is the only reliable fallback.
        watchdogJob = managerScope.launch {
            delay(INIT_TIMEOUT_MS.milliseconds)
            if (_state.value is ScannerState.Initializing) {
                logError("$tag initialize() → Watchdog timed out after ${INIT_TIMEOUT_MS / 1000}s")
                _state.value = ScannerState.Failed("Fingerprint reader did not respond. Please retry.")
                activeJob?.cancel()
            }
        }

        activeJob = managerScope.launch {
            try {
                withTimeout(INIT_TIMEOUT_MS.milliseconds) {
                    performInitialization()
                }
                watchdogJob?.cancel()
                // Guard: watchdog may have already set Failed while we were blocked in native code.
                if (_state.value is ScannerState.Initializing) {
                    _state.value = ScannerState.Ready
                    logDebug("$tag initialize() → Ready")
                }
            } catch (_: TimeoutCancellationException) {
                watchdogJob?.cancel()
                logError("$tag initialize() → Timed out after ${INIT_TIMEOUT_MS / 1000}s")
                if (_state.value is ScannerState.Initializing) {
                    _state.value = ScannerState.Failed("Fingerprint reader did not respond. Please retry.")
                }
            } catch (e: CancellationException) {
                watchdogJob?.cancel()
                // Do not change state on cooperative cancellation (e.g. user pressed back).
                throw e
            } catch (e: Exception) {
                watchdogJob?.cancel()
                NewRelic.recordHandledException(e)
                val reason = e.message ?: "Unknown initialization error"
                logError("$tag initialize() → Failed: $reason")
                if (_state.value is ScannerState.Initializing) {
                    _state.value = ScannerState.Failed(reason)
                }
            }
        }
    }

    /**
     * Performs the blocking initialization work.
     * Must be called from an IO coroutine. Throws on any unrecoverable failure.
     */
    private suspend fun performInitialization() = withContext(Dispatchers.IO) {
        val overallStart = System.currentTimeMillis()
        logTiming("performInitialization() ▶ start")

        // Dispose stale SDK sessions before power-cycling. Without this, setDevice() below
        // replaces the old NBDevice handles without calling dispose(), and the SDK's internal
        // session tracking then returns "Invalid operation" on the next openSession() call.
        // Must happen before USB power-OFF so dispose() can still communicate with the firmware.
        reader0.close()
        reader1.close()

        // USB power OFF
        var stepStart = System.currentTimeMillis()
        logDebug("$tag performInitialization() → USB power cycle")
        logTiming("performInitialization() → usbPower(0) start")
        PowerControl(context).usbPower(0)
        logTiming("performInitialization() → usbPower(0) done in ${elapsedSec(stepStart)}s")

        delay(1000.milliseconds)

        // USB power ON
        stepStart = System.currentTimeMillis()
        logTiming("performInitialization() → usbPower(1) start")
        PowerControl(context).usbPower(1)
        logTiming("performInitialization() → usbPower(1) done in ${elapsedSec(stepStart)}s")

        delay(1000.milliseconds)

        // Initialize the NBDevices SDK (idempotent if already initialized).
        stepStart = System.currentTimeMillis()
        logTiming("performInitialization() → NBDevices.initialize start")
        NBDevices.initialize(context)
        logTiming("performInitialization() → NBDevices.initialize done in ${elapsedSec(stepStart)}s")

        // Poll until the SDK signals it is ready, up to 25 seconds.
        stepStart = System.currentTimeMillis()
        logTiming("performInitialization() → polling NBDevices.isInitialized start")
        val sdkReady = pollUntil(maxAttempts = 50, delayMs = 500) {
            NBDevices.isInitialized()
        }
        logTiming("performInitialization() → NBDevices.isInitialized ready=$sdkReady in ${elapsedSec(stepStart)}s")
        if (!sdkReady) {
            throw IllegalStateException("NBDevices SDK did not initialize within the timeout.")
        }

        // Wait until at least two device handles are available.
        var devices = emptyArray<NBDevice>()
        stepStart = System.currentTimeMillis()
        logTiming("performInitialization() → polling NBDevices.getDevices start")
        val devicesFound = pollUntil(maxAttempts = 50, delayMs = 500) {
            devices = NBDevices.getDevices()
            devices.size >= 2
        }
        logTiming("performInitialization() → NBDevices.getDevices found=${devices.size} in ${elapsedSec(stepStart)}s")
        if (!devicesFound || devices.size < 2) {
            throw IllegalStateException("Expected 2 readers, found ${devices.size}.")
        }

        logDebug("$tag performInitialization() → found ${devices.size} devices, initializing readers")

        reader0.setDevice(devices[0])
        reader1.setDevice(devices[1])

        // Initialize both readers concurrently. Each init() has its own 30 s hard timeout via
        // a thread-pool Future.get(). Running them sequentially would risk the init watchdog
        // firing mid-way through the second reader even when both are just slow.
        stepStart = System.currentTimeMillis()
        logTiming("performInitialization() → reader0.init + reader1.init start")
        val (init0, init1) = coroutineScope {
            listOf(
                async { reader0.init() },
                async { reader1.init() },
            ).awaitAll()
        }
        logTiming("performInitialization() → readers init done in ${elapsedSec(stepStart)}s (reader0=$init0, reader1=$init1)")

        if (!init0 || !init1) {
            throw IllegalStateException(
                "Reader initialization failed — reader0=$init0, reader1=$init1"
            )
        }

        logTiming("performInitialization() ■ TOTAL ${elapsedSec(overallStart)}s")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scanning
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts a concurrent scan on both readers.
     *
     * **Both readers must be at the same pace at all times.** This is enforced by launching them
     * as sibling [async] coroutines inside a single [coroutineScope]. If either reader:
     * - emits a [ScannerEvent.SpoofDetected] event, or
     * - emits a [ScannerEvent.ReaderError] event, or
     * - throws any exception,
     *
     * the [coroutineScope] immediately cancels the other reader's coroutine. The canceled
     * reader's [FingerprintReaderWrapper.runBlockingSdk] invokes `invokeOnCancellation →
     * device.cancelScan()` to unblock the blocking SDK call.
     *
     * Transitions to [ScannerState.Scanning] immediately. On completion:
     * - [ScannerState.Success] — both readers finished without error.
     * - [ScannerState.Failed] — one or both readers encountered a non-recoverable error.
     * - [ScannerState.Cancelled] — the user or a spoof-detection canceled the operation.
     *
     * Calling [startScan] before the manager is in [ScannerState.Ready] is a no-op with a log.
     *
     * @param savePath  Directory path (with trailing `/`) for WSQ and JPEG files.
     */
    fun startScan(savePath: String) {
        val cur = _state.value
        if (cur !is ScannerState.Ready && cur !is ScannerState.Success && cur !is ScannerState.Failed) {
            logError("$tag startScan() called in invalid state: $cur")
            return
        }
        // Starting a scan means the device is being woken — clear the sleep flag so a
        // successful scan doesn't force an unnecessary reinit on the next launch.
        isLowPowerEnabled = false
        cancelActiveJob()
        _state.value = ScannerState.Scanning

        // earlyFailTrigger: completed immediately by collectReader when DeviceInSleepMode is
        // detected. The watchdog awaits it so it fires at once instead of waiting the full
        // SCAN_WATCHDOG_TIMEOUT_MS. For all other errors, sibling cancel is used directly and
        // the watchdog is canceled before this trigger is ever set.
        val earlyFailTrigger = CompletableDeferred<String>()

        // Watchdog: if a reader's blocking SDK call doesn't respond to cancelScan(), coroutineScope
        // can be stuck waiting for the sibling to exit. This runs on managerScope independently
        // of activeJob to unfreeze the UI. It fires immediately when earlyFailTrigger is
        // completed (sleep-mode path) or after SCAN_WATCHDOG_TIMEOUT_MS (full-stuck path).
        scanWatchdogJob = managerScope.launch {
            val reason = withTimeoutOrNull(SCAN_WATCHDOG_TIMEOUT_MS.milliseconds) { earlyFailTrigger.await() }
                ?: "Scan did not complete. Please retry."
            if (_state.value is ScannerState.Scanning) {
                logError("$tag startScan() → Watchdog fired: $reason")
                reader0.cancel()
                reader1.cancel()
                if (reason.contains("sleep mode", ignoreCase = true)) isLowPowerEnabled = true
                _state.value = ScannerState.Failed(reason)
                activeJob?.cancel()
            }
        }

        activeJob = managerScope.launch {
            try {
                val (result0, result1) = coroutineScope {
                    val deferred0 = async { collectReader(reader0, reader1, savePath, earlyFailTrigger) }
                    val deferred1 = async { collectReader(reader1, reader0, savePath, earlyFailTrigger) }
                    awaitAll(deferred0, deferred1)
                }
                scanWatchdogJob?.cancel()
                _state.value = ScannerState.Success(result0, result1)
                logDebug("$tag startScan() → Success")
            } catch (e: CancellationException) {
                scanWatchdogJob?.cancel()
                // Cooperative cancellation (user pressed cancel, or sibling reader failed).
                // Cancel both readers to ensure hardware is released.
                reader0.cancel()
                reader1.cancel()
                // Guard: scan watchdog may have already transitioned away from Scanning.
                if (_state.value is ScannerState.Scanning) {
                    _state.value = ScannerState.Cancelled("Scan cancelled: ${e.message}")
                }
                logDebug("$tag startScan() → Cancelled")
                // Re-throw so the parent Job sees the cancellation.
                throw e
            } catch (e: Exception) {
                scanWatchdogJob?.cancel()
                NewRelic.recordHandledException(e)
                reader0.cancel()
                reader1.cancel()
                val reason = e.message ?: "Unknown scan error"
                logError("$tag startScan() → Failed: $reason")
                // DeviceInSleepMode is the only source of truth for low-power state.
                // "Invalid operation" from ReaderError is a regular SDK error, not sleep mode.
                if (reason.contains("sleep mode", ignoreCase = true)) {
                    isLowPowerEnabled = true
                }
                // Guard: scan watchdog may have already transitioned away from Scanning.
                if (_state.value is ScannerState.Scanning) {
                    _state.value = ScannerState.Failed(reason)
                }
            }
        }
    }

    /**
     * Collects all events from [reader]'s [FingerprintReaderWrapper.scanAndExtractFlow],
     * forwarding them to the shared [events] flow. Returns the [ReaderResult] carried by the
     * terminal [ScannerEvent.ScanCompleted] event.
     *
     * **Error handling strategy:**
     * - **Spoof / SDK error (including "Invalid operation"):** [sibling] is canceled immediately
     *   so its blocking SDK call gets a `cancelScan()` signal as early as possible, well before
     *   [coroutineScope]'s cooperative cancellation would reach it.
     * - **[ScannerEvent.DeviceInSleepMode]:** [sibling] is NOT canceled. Cancelling when only one
     *   reader is asleep creates mismatched hardware states. Instead, [earlyFailTrigger] is
     *   completed immediately so the scan watchdog fires at once and sets [ScannerState.Failed].
     *
     * @param reader           The reader whose flow to collect.
     * @param sibling          The other reader — canceled immediately on errors/spoof.
     * @param savePath         Directory path passed through to the reader.
     * @param earlyFailTrigger Completed with the error message on [ScannerEvent.DeviceInSleepMode]
     *                         to trigger the scan watchdog immediately rather than waiting its full timeout.
     * @return                 The final [ReaderResult] for this reader.
     * @throws Exception when the reader reports an error or spoof.
     */
    private suspend fun collectReader(
        reader: FingerprintReaderWrapper,
        sibling: FingerprintReaderWrapper,
        savePath: String,
        earlyFailTrigger: CompletableDeferred<String>,
    ): ReaderResult {
        var result: ReaderResult? = null

        reader.scanAndExtractFlow(
            savePath = savePath,
            scanningType = scanningType,
            bvnNumber = bvnNumber,
            encryptionKey = encryptionKey,
            skipFirebaseActions = skipFirebaseActions,
            enableBmpExport = enableBmpExport,
        ).collect { event ->
            // Forward every event to the shared observable stream.
            _events.emit(event)

            when (event) {
                is ScannerEvent.SpoofDetected -> {
                    sibling.cancel()
                    logError("$tag collectReader() → Spoof detected on reader ${event.readerNo}")
                    throw SpoofDetectedException("Spoof detected on reader ${event.readerNo}")
                }

                is ScannerEvent.DeviceInSleepMode -> {
                    val msg = "Reader ${event.readerNo} is in sleep mode"
                    earlyFailTrigger.complete(msg)
                    logError("$tag collectReader() → $msg")
                    throw IllegalStateException(msg)
                }

                is ScannerEvent.ReaderError -> {
                    sibling.cancel()
                    logError("$tag collectReader() → Error on reader ${event.readerNo}: ${event.cause.message}")
                    throw event.cause
                }

                is ScannerEvent.ScanCompleted -> {
                    result = event.result
                }

                else -> { /* Other events already forwarded; no special handling needed. */
                }
            }
        }

        return result
            ?: throw IllegalStateException("Reader ${reader.readerNo} flow ended without a ScanCompleted event")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session health check
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when both reader sessions are confirmed open by the SDK.
     * If either session is closed, transitions state to [ScannerState.Failed] and returns false.
     *
     * Call this from [android.app.Activity.onResume] to detect USB disconnections.
     */
    fun checkSessionHealth(): Boolean {
        if (!reader0.isReady() || !reader1.isReady()) {
            _state.value = ScannerState.Failed("One or both reader sessions are closed.")
            return false
        }
        return true
    }

    /**
     * Immediately sets the state to [ScannerState.Ready] without any hardware check.
     *
     * Use this in [android.app.Activity.onCreate] right after reusing the singleton, before
     * the state-flow observer is registered, so the first collected emission is always
     * Ready ("Start Scan") and never a stale Success/Failed/Canceled from the previous session.
     * Hardware correctness is verified separately by [resetToReady] in [com.scanner.updated.UpdatedScannerActivity.initializeHardware].
     */
    fun clearStaleState() {
        _state.value = ScannerState.Ready
    }

    /**
     * Tries to skip hardware init by verifying the readers are still open.
     *
     * Returns `true` (and sets state to [ScannerState.Ready]) only when both sessions are open
     * AND the device was NOT put into low-power sleep since the last init. If the device was
     * sleeping ([enableLowPowerMode] was called), returns `false` so the caller runs a full
     * [initialize] to properly wake the hardware.
     *
     * @return true if readers are healthy and ready; false if full initialization is needed.
     */
    fun resetToReady(): Boolean {
        // After enableLowPowerMode() the SDK session usually remains open — the device is
        // sleeping, not disconnected. Trust isReady() as the source of truth: if both sessions
        // are open we can skip the USB power cycle and let startScan() wake the hardware.
        // If the device really did disconnect during sleep, isReady() returns false and we
        // fall through to a full initialize() as normal.
        isLowPowerEnabled = false
        if (reader0.isReady() && reader1.isReady()) {
            cancelActiveJob()
            _state.value = ScannerState.Ready
            return true
        }
        _state.value = ScannerState.Failed("One or both reader sessions are closed.")
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cancellation & release
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Cancels any in-progress init or scan and transitions to [ScannerState.Cancelled].
     * Does not release hardware resources — the manager can still call [initialize] again.
     *
     * @param reason  Human-readable reason logged internally.
     */
    fun cancelScan(reason: String = "Cancelled by user") {
        logDebug("$tag cancelScan() → $reason")
        cancelActiveJob()
        reader0.cancel()
        reader1.cancel()
        _state.value = ScannerState.Cancelled(reason)
    }

    /**
     * Puts both readers into low-power (sleep) mode and records that the hardware is now
     * sleeping. The next call to [resetToReady] will return false so that [initialize] is
     * called to properly wake the devices on the next launch.
     *
     * Mirrors [com.scanner.utils.readers.FingerprintHelper.enableLowPowerMode].
     * Call this from [android.app.Activity.onDestroy] so the hardware draws minimal current
     * while no activity is in the foreground.
     */
    fun enableLowPowerMode() {
        logDebug("$tag enableLowPowerMode()")
        isLowPowerEnabled = true
        reader0.enableLowPowerMode()
        reader1.enableLowPowerMode()
    }

    /**
     * Releases all hardware resources and shuts down the manager's coroutine scope.
     * Must be called from [android.app.Activity.onDestroy] to prevent resource leaks.
     * The manager cannot be used after this call.
     */
    fun release() {
        logDebug("$tag release()")
        cancelActiveJob()
        reader0.close()
        reader1.close()
        managerScope.cancel()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns elapsed time from [startMs] formatted as seconds with 3 decimal places. */
    private fun elapsedSec(startMs: Long): String =
        "%.3f".format((System.currentTimeMillis() - startMs) / 1000.0)

    /**
     * Logs a timing message when [timingLogsEnabled] is true.
     * Toggle [timingLogsEnabled] at runtime (or flip the companion default) to enable/disable.
     */
    private fun logTiming(msg: String) {
        if (timingLogsEnabled) Log.d(tag, "[TIMING] $msg")
    }

    /** Cancels [activeJob], [watchdogJob], and [scanWatchdogJob] if running and clears all references. */
    private fun cancelActiveJob() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Polls [condition] at [delayMs]-millisecond intervals up to [maxAttempts] times.
     *
     * @return True if [condition] returned true before [maxAttempts] was exhausted.
     */
    private suspend fun pollUntil(
        maxAttempts: Int,
        delayMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        repeat(maxAttempts) {
            if (condition()) return true
            delay(delayMs.milliseconds)
        }
        return condition()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Domain exceptions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Thrown when a reader detects a spoofed finger. Treated as a regular [Exception] so that
     * [coroutineScope] cancels the sibling reader without special-casing [CancellationException].
     */
    class SpoofDetectedException(message: String) : Exception(message)

    companion object {
        private const val INIT_TIMEOUT_MS = 30_000L

        // 60 s gives even slow users (unusual grip, thick calluses) time to place their fingers.
        // 30 s was too aggressive and could fire on perfectly healthy hardware.
        private const val SCAN_WATCHDOG_TIMEOUT_MS = 60_000L

        /**
         * Set to `true` to print per-step timing logs for [performInitialization].
         * Each log line is prefixed with `TIMING` and shows seconds with millisecond precision.
         * Safe to toggle at runtime; change takes effect on the next [initialize] call.
         *
         * Example:
         * ```kotlin
         * ScannerSessionManager.timingLogsEnabled = true  // enable before init
         * ScannerSessionManager.timingLogsEnabled = false // disable in production
         * ```
         */
        var timingLogsEnabled = true
    }
}
