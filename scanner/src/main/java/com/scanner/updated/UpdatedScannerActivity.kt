package com.scanner.updated

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.github.legend295.fingerprintscanner.R
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.newrelic.agent.android.NewRelic
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.scanner.app.ScannerApp
import com.scanner.model.User
import com.scanner.updated.model.ReaderResult
import com.scanner.updated.model.ScannerEvent
import com.scanner.updated.model.ScannerState
import com.scanner.updated.reader.ScannerSessionManager
import com.scanner.updated.repository.UserRepository
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.builder.BuilderOptions
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
import com.scanner.utils.fetchingUserDB
import com.scanner.utils.location.LocationWrapper
import com.scanner.utils.readersInitializationDialog
import com.scanner.utils.templatesDownloadDialog
import com.scanner.utils.verificationDialog
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.view.isVisible

/**
 * Refactored scanner activity.
 *
 * This activity orchestrates the complete fingerprint scanning session.
 * All hardware interaction is delegated to [ScannerSessionManager] and all Firebase I/O
 * to [UserRepository] — the activity itself only drives the UI and navigates between states.
 *
 * ### Architecture overview
 * ```
 * UpdatedScannerActivity
 *   │
 *   ├── ScannerSessionManager  ← coroutine-based dual-reader coordinator
 *   │     ├── FingerprintReaderWrapper (reader 0)
 *   │     └── FingerprintReaderWrapper (reader 1)
 *   │
 *   └── UserRepository         ← suspend-based Firebase data layer
 * ```
 *
 * ### Lifecycle
 * - `onCreate`  → parse options, init manager, start location + user lookup, call [initializeHardware].
 * - `onResume`  → check session health; re-initialise if a reader session dropped.
 * - `onDestroy` → release hardware via [ScannerSessionManager.release].
 *
 * ### State machine
 * The activity observes [ScannerSessionManager.state] which transitions through
 * [ScannerState.Idle] → [ScannerState.Initializing] → [ScannerState.Ready] → [ScannerState.Scanning]
 * → [ScannerState.Success] | [ScannerState.Failed] | [ScannerState.Cancelled].
 */
internal class UpdatedScannerActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private var tvStatus: AppCompatTextView? = null
    private var tvStatusLeft: AppCompatTextView? = null
    private var tvStatusRight: AppCompatTextView? = null
    private var tvLeftQuality: AppCompatTextView? = null
    private var tvRightQuality: AppCompatTextView? = null
    private var tvLeftLiveness: AppCompatTextView? = null
    private var tvRightLiveness: AppCompatTextView? = null

    /**
     * Set when a spoof or dirty-pad outcome has labelled a reader's panel, so the
     * [ScannerState.Failed] render leaves that label alone. Cleared at the start of each pass.
     */
    private var readerNoticePinned = false
    private var btnStart: AppCompatButton? = null
    private var btnCancel: AppCompatButton? = null
    private var ivScannerLeft: AppCompatImageView? = null
    private var ivScannerRight: AppCompatImageView? = null
    private var ivLeftFingerGif: AppCompatImageView? = null
    private var ivRightFingerGif: AppCompatImageView? = null

    /**
     * The running wake-prompt animations, held so their looping callbacks can be unregistered.
     * Null whenever the prompt is hidden.
     */
    private var leftFingerAvd: AnimatedVectorDrawableCompat? = null
    private var rightFingerAvd: AnimatedVectorDrawableCompat? = null
    private var tvScanFingerprints: AppCompatTextView? = null
    private var tvScanMessage: AppCompatTextView? = null
    private var messagesHolder: LinearLayout? = null
    private var lastMessageView: TextView? = null

    // ── Dialogs ───────────────────────────────────────────────────────────────
    private var initDialog: Dialog? = null
    private var downloadDialog: Dialog? = null
    private var verificationResultDialog: Dialog? = null
    private var readerNoResponseDialog: Dialog? = null

    // ── Configuration ─────────────────────────────────────────────────────────
    private var scanningOptions: BuilderOptions? = null
    private var skipLocation = false
    private var skipFirebaseActions = false
    private var uploadBmpToFirebase = false

    // ── Domain objects ────────────────────────────────────────────────────────
    private lateinit var sessionManager: ScannerSessionManager
    private val userRepository by lazy { UserRepository(scanningOptions?.storagePath ?: DEFAULT_STORAGE_PATH) }
    private val locationWrapper by lazy { LocationWrapper(this) }

    // ── Location ──────────────────────────────────────────────────────────────
    private val locationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var locationTimeoutRunnable: Runnable? = null

    /**
     * Set once the Firestore user record exists, so a GPS fix arriving after that write knows
     * there is something to patch. Registration only — verification writes no record.
     */
    private var userRecordSaved = false

    // ── Screen on/off tracking ────────────────────────────────────────────────
    // The POS hardware drops the USB fingerprint reader sessions whenever the screen turns
    // off, so a screen-off → screen-on cycle needs a full hardware re-init — the readers won't
    // recover on their own the way they do from low-power sleep mode.
    //
    // The actual reinit (and its dialog) is NOT triggered directly from ACTION_SCREEN_ON:
    // that broadcast fires the instant the display powers on, before the window manager has
    // necessarily redrawn/refocused this activity's window. Showing a Dialog at that instant
    // races the window attach and renders as a bare dim scrim with no content. Waiting for
    // onWindowFocusChanged(true) instead guarantees the window is actually visible and safe
    // to draw dialogs into.
    private var screenTurnedOff = false
    private var screenReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    logDebug("UpdatedScannerActivity :: screen off — USB reader sessions will drop")
//                    screenTurnedOff = true
//                    clearOnScreenOff()
                    sessionManager.cancelScan()
                    dismissInitDialog()
                    cancelSession()
                    ScannerApp.getInstance().sessionManager = null
                }

                Intent.ACTION_SCREEN_ON -> {
                    logDebug("UpdatedScannerActivity :: screen on — waiting for window focus before reinitializing")
                }
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────
    private var currentUser: User? = null

    /** Accumulated file paths produced by the scan session (WSQ, JPEG). */
    private val scannedFilePaths = mutableListOf<File>()

    /** Template file paths produced by the registration scan. */
    private val templateFilePaths = mutableListOf<File>()

    /** Uploaded remote storage paths for the current session. */
    private val uploadedCloudPaths = mutableListOf<String>()

    /** Local template paths pending cloud upload. */
    private val localTemplatePaths = mutableListOf<String>()

    /** Tracks per-reader extraction success for the two-finger parity check. */
    private val extractionSuccess = mutableMapOf<Int, Boolean>()

    /**
     * True while the Start button reads "Scan again" because [handleLowQuality] rejected the
     * last pass. The session stays in [ScannerState.Success] through a low-quality retry, so
     * [handleStartClick] needs this to tell a rescan apart from a result delivery.
     * Cleared by [clearSessionData].
     */
    private var retryAfterLowQuality = false

    /** Tracks per-reader identification results for the two-finger verification check. */
    private val identificationResults = mutableMapOf<Int, com.nextbiometrics.biometrics.NBBiometricsIdentifyResult?>()

    /**
     * Tracks, per reader, whether a genuine PUT_FINGER_ON_SENSOR / KEEP_FINGER_ON_SENSOR status
     * has been observed in the current scan pass. Reset alongside [clearSessionData] at the
     * start of every fresh scan attempt.
     *
     * Needed because the NextBiometrics hardware can report a LIFT_FINGER status as the very
     * first event of a pass — a stale finger-presence latch left over from before the reader
     * went to sleep (see FingerprintScanner_SleepMode_Issue.docx) — with no finger ever having
     * touched the sensor. A LIFT_FINGER can only be genuine if a PUT/KEEP was seen first.
     */
    private val fingerSeenOnSensor = mutableMapOf<Int, Boolean>()

    // ── Low power mode tracking ───────────────────────────────────────────────
    // Matches ScannerActivity: incremented once per reader per low-power event.
    // Re-initialise hardware when this exceeds 6 (i.e. >= 3 events across both readers).
    // isLowPowerEnabled is the single source of truth — read from sessionManager directly.
    private var sleepModeTrack = 0

    /** Template upload tracking: maps readerNo → remote path. */
    private val templateUploadPaths = mutableMapOf<Int, String>()

    // ── Coroutine jobs ────────────────────────────────────────────────────────
    private var syncJob: Job? = null

    /** Polls [ScannerSessionManager.areBothReadersAwake] while readers are asleep. */
    private var sleepPollJob: Job? = null

    companion object {
        private const val DEFAULT_STORAGE_PATH = "biometrics/"

        /** How often [startSleepModePolling] checks [ScannerSessionManager.areBothReadersAwake]. */
        private const val SLEEP_POLL_INTERVAL_MS = 2000L

        /** Total time [startSleepModePolling] waits for the readers to wake before prompting the user. */
        private const val SLEEP_POLL_TIMEOUT_MS = 30_000L

        /** Last captured GPS coordinates for this scanning session. */
        var location: LatLng? = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        bindViews()
        parseOptions()
        applyTheme(scanningOptions?.themeOptions)
        initNewRelic()

        // Reuse the session manager across activity instances so hardware stays
        // initialized and the user doesn't see the init dialog on every launch.
        if (ScannerApp.getInstance().sessionManager == null) {
            sessionManager = ScannerSessionManager(applicationContext)
            ScannerApp.getInstance().sessionManager = sessionManager
        } else {
            sessionManager = ScannerApp.getInstance().sessionManager!!
            // Clear stale terminal state (Success/Failed/Canceled) BEFORE the StateFlow
            // collector is registered below, so the first emission is Ready — not the
            // previous session's result, which would re-show the Registration Successful dialog.
            // Hardware correctness is verified separately by initializeHardware().
            sessionManager.clearStaleState()
        }

        // Background sync of any locally stored but not yet uploaded templates.
        if (!skipFirebaseActions) {
            syncJob = lifecycleScope.launch(Dispatchers.IO) {
                userRepository.syncPendingUploads(filesDir)
            }
        }

        // Configure the session manager before any hardware access.
        sessionManager.configure(
            scanningType = scanningOptions?.scanningType ?: ScanningType.REGISTRATION,
            bvnNumber = scanningOptions?.uniqueId ?: "common",
            encryptionKey = scanningOptions?.key ?: "",
            skipFirebaseActions = skipFirebaseActions,
            enableBmpExport = scanningOptions?.enableBmpExport ?: false,
        )
        ScannerApp.getInstance().key = scanningOptions?.key

        // Observe state machine changes on the Main dispatcher.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionManager.state.collect { state -> renderState(state) }
            }
        }

        // Observe reader events (preview frames, messages, results).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionManager.events.collect { event -> handleEvent(event) }
            }
        }

        setupClickListeners()
        registerScreenStateReceiver()
        startLocationThenInitialize()
    }

    /** Registers [screenStateReceiver] for the activity's full lifetime — see field comment. */
    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // SCREEN_ON/OFF are system-only broadcasts — never sent by other apps — so
        // RECEIVER_NOT_EXPORTED satisfies the API 33+ requirement without opening the receiver up.
        ContextCompat.registerReceiver(this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        // If the session was Ready/Scanning but a reader dropped, re-initialise.
        val current = sessionManager.state.value
        if (current is ScannerState.Ready || current is ScannerState.Scanning) {
            if (!sessionManager.areBothReadersAwake()) {
                handleFailedSleepMode(checkSessionAfterWake = true)
                return
            }
            if (!sessionManager.checkSessionHealth()) {
                logDebug("UpdatedScannerActivity :: onResume → session unhealthy, reinitializing")
                initializeHardware()
            }
        }
    }

    /**
     * Fires whenever this window gains or loses focus — including after a screen-off/on cycle,
     * since the display turning off strips window focus and turning it back on restores it once
     * the window manager has actually redrawn the window. This is the safe point to reinitialise
     * hardware and show the init dialog; see the [screenStateReceiver] field comment for why the
     * raw ACTION_SCREEN_ON broadcast is too early to do that safely.
     */
    /*  override fun onWindowFocusChanged(hasFocus: Boolean) {
          super.onWindowFocusChanged(hasFocus)
          if (hasFocus && screenTurnedOff) {
              screenTurnedOff = false
              // Guard against a redundant reinit if onResume's own recovery check
              // (areBothReadersAwake/checkSessionHealth) already kicked one off.
              if (!isFinishing && sessionManager.state.value !is ScannerState.Initializing) {
                  logDebug("UpdatedScannerActivity :: window focus regained after screen on — reinitializing hardware")
                  initializeHardware()
              }
          }
      }*/

    override fun onDestroy() {
        super.onDestroy()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenReceiverRegistered = false
        }
        sleepPollJob?.cancel()
        locationTimeoutRunnable?.let { locationHandler.removeCallbacks(it) }
        locationWrapper.stopUpdates()
        // Put readers to sleep so they draw minimal current while the activity is gone.
        // Do NOT release or null out the singleton — ScannerApp holds the session alive so
        // the next launch skips the slow hardware init and wakes the readers instead.
        sessionManager.enableLowPowerMode()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // View binding & UI helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Finds all views from the existing XML layout by their IDs. */
    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusLeft = findViewById(R.id.tvStatusLeft)
        tvStatusRight = findViewById(R.id.tvStatusRight)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        ivScannerLeft = findViewById(R.id.ivScannerLeft)
        ivScannerRight = findViewById(R.id.ivScannerRight)
        ivLeftFingerGif = findViewById(R.id.ivLeftFingerGif)
        ivRightFingerGif = findViewById(R.id.ivRightFingerGif)
        tvLeftQuality = findViewById(R.id.tvLeftQuality)
        tvRightQuality = findViewById(R.id.tvRightQuality)
        tvLeftLiveness = findViewById(R.id.tvLeftLiveness)
        tvRightLiveness = findViewById(R.id.tvRightLiveness)
        messagesHolder = findViewById(R.id.messagesHolder)
        tvScanFingerprints = findViewById(R.id.tvScanFingerprints)
        tvScanMessage = findViewById(R.id.tvScanMessage)
    }

    /** Parses [BuilderOptions] from the launching intent. */
    private fun parseOptions() {
        val json = intent.extras?.getString(Constant.SCANNING_OPTIONS)
        scanningOptions = Gson().fromJson(json, BuilderOptions::class.java)
        skipLocation = scanningOptions?.skipLocation ?: false
        skipFirebaseActions = scanningOptions?.skipFirebaseActions ?: false
        uploadBmpToFirebase = scanningOptions?.uploadBmpToFirebase ?: false
    }

    /** Applies custom or default colour/drawable theme to all interactive views. */
    private fun applyTheme(theme: ThemeOptions?) {
        if (theme == null) {
            applyDefaultTheme()
            return
        }
        runCatching {
            btnStart?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, theme.buttonColor))
            btnStart?.setTextColor(ContextCompat.getColor(this, theme.buttonTextColor))
            btnCancel?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, theme.buttonColor))
            btnCancel?.setTextColor(ContextCompat.getColor(this, theme.buttonTextColor))
            tvStatus?.setTextColor(ContextCompat.getColor(this, theme.messageColor))
            tvStatusLeft?.setTextColor(ContextCompat.getColor(this, theme.messageColor))
            tvStatusRight?.setTextColor(ContextCompat.getColor(this, theme.messageColor))
            tvScanFingerprints?.setTextColor(ContextCompat.getColor(this, theme.titleTextColor))
            tvScanMessage?.setTextColor(ContextCompat.getColor(this, theme.contentTextColor))
            btnStart?.background = ContextCompat.getDrawable(this, theme.buttonBackground)
            btnCancel?.background = ContextCompat.getDrawable(this, theme.buttonBackground)
        }.onFailure { applyDefaultTheme() }
    }

    /** Applies the built-in default colour scheme when no custom theme is provided. */
    private fun applyDefaultTheme() {
        btnStart?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.infraRed))
        btnStart?.setTextColor(ContextCompat.getColor(this, R.color.white))
        btnCancel?.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.infraRed))
        btnCancel?.setTextColor(ContextCompat.getColor(this, R.color.white))
        tvStatus?.setTextColor(ContextCompat.getColor(this, R.color.robinEggBlue))
        tvStatusLeft?.setTextColor(ContextCompat.getColor(this, R.color.robinEggBlue))
        tvStatusRight?.setTextColor(ContextCompat.getColor(this, R.color.robinEggBlue))
        tvScanFingerprints?.setTextColor(ContextCompat.getColor(this, R.color.black))
        tvScanMessage?.setTextColor(ContextCompat.getColor(this, R.color.black))
        btnStart?.background = ContextCompat.getDrawable(this, R.drawable.bg_round_white)
        btnCancel?.background = ContextCompat.getDrawable(this, R.drawable.bg_round_white)
    }

    /** Starts or stops the New Relic agent if a token was provided in the scan options. */
    private fun initNewRelic() {
        scanningOptions?.newRelicToken?.let { token ->
            runCatching {
                NewRelic.withApplicationToken(token)
                    .withLoggingEnabled(true)
                    .withCrashReportingEnabled(true)
                    .start(this)
            }
        }
    }

    /**
     * Wires up click listeners for the Start and Cancel buttons.
     * Start button behaviour is delegated to [handleStartClick] which reads the current state.
     */
    private fun setupClickListeners() {
        btnStart?.setOnClickListener {
            if (checkStoragePermissions()) {
                handleStartClick()
            } else {
                requestStoragePermissions()
            }
        }

        btnCancel?.setOnClickListener { cancelSession() }
    }

    /**
     * Cancels the current scan session and closes the activity with [RESULT_CANCELED].
     * Shared by the Cancel button and the "No" choice on the reader-no-response dialog.
     */
    private fun cancelSession() {
        sleepPollJob?.cancel()
        btnCancel?.isEnabled = false
        sessionManager.cancelScan("Cancelled by user")
        setResult(RESULT_CANCELED)
        finish()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State rendering
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The single entry point for all UI changes driven by state transitions.
     * Called every time [ScannerSessionManager.state] emits a new value.
     *
     * @param state  The new [ScannerState] from the session manager.
     */
    private fun renderState(state: ScannerState) {
        logDebug("UpdatedScannerActivity :: renderState → $state")
        // Any state change other than a fresh sleep-mode Failed supersedes an in-flight poll.
        if (!(state is ScannerState.Failed && sessionManager.isLowPowerEnabled)) {
            sleepPollJob?.cancel()
        }
        // The finger animations belong to the wake prompt only — both states below route to it.
        // Handled here so every other state hides (and stops) them without repeating the call.
        setFingerAnimationsVisible(
            state is ScannerState.AwaitingWake ||
                    (state is ScannerState.Failed && sessionManager.isLowPowerEnabled)
        )
        when (state) {
            is ScannerState.Idle -> {
                setMessage(getString(R.string.initializing))
            }

            is ScannerState.Initializing -> {
                setMessage(getString(R.string.initializing))
                showInitDialog()
                setStartButtonVisible(false)
                setCancelButtonVisible(false)
            }

            is ScannerState.AwaitingWake -> {
                // Initialization is parked until the user touches both sensors. Driven by state
                // rather than an event because this can begin before the activity is STARTED,
                // and only a retained state survives that.
                dismissInitDialog()
                resetFingerImages()
                setStartButtonVisible(false)
                setCancelButtonVisible(false)
                setMessage("Device is in sleep mode. Please touch both finger sensors to wake them up.")
                // Recover the prompt if the wait timed out while this activity wasn't collecting.
                if (sessionManager.isAwaitingWakeDecision) showInitWakeNoResponseDialog()
            }

            is ScannerState.Ready -> {
                dismissInitDialog()
                setMessage(getString(R.string.scan))
                setStartButton("Start Scan", visible = true)
                setCancelButtonVisible(false)
            }

            is ScannerState.Scanning -> {
                setMessage("Please wait starting scan")
                setStartButtonVisible(false)
                setCancelButtonVisible(true)
            }

            is ScannerState.Success -> {
                dismissInitDialog()
                setStartButton("Done", visible = true)
                setCancelButtonVisible(false)
                setMessage(getString(R.string.read_success))
                handleScanSuccess(state.reader0Result, state.reader1Result)
            }

            is ScannerState.Failed -> {
                dismissInitDialog()
                if (sessionManager.isLowPowerEnabled) {
                    // Only DeviceInSleepMode sets isLowPowerEnabled — always show sleep message.
                    sleepModeTrack++
                    handleFailedSleepMode()
                } else {
                    sleepModeTrack = 0
                    val displayMessage = if (state.reason.contains("Invalid operation", ignoreCase = true)) {
                        "Invalid Operation"
                    } else {
                        state.reason
                    }
                    // A spoof or dirty-pad failure already labelled the offending reader's panel
                    // and tinted it red; clearing here would erase the only per-reader indication
                    // of which sensor rejected the presentation.
                    if (!readerNoticePinned) {
                        setReaderMessage(0, "")
                        setReaderMessage(1, "")
                    }
                    setMessage(displayMessage)
                    setStartButton("Retry", visible = true)
                    setCancelButtonVisible(false)
                }
            }

            is ScannerState.Cancelled -> {
                dismissInitDialog()
                setMessage(getString(R.string.scan))
                setStartButton("Start Scan", visible = true)
                setCancelButtonVisible(false)
            }
        }
    }

    /**
     * @param checkSessionAfterWake  When true, [ScannerSessionManager.checkSessionHealth] is run
     *   once the readers wake up, and the hardware is re-initialised if the session dropped while
     *   asleep. Set from [onResume] only — the normal [renderState] sleep-mode path just waits for
     *   the readers and shows the Start Scan button again.
     */
    private fun handleFailedSleepMode(checkSessionAfterWake: Boolean = false) {
        resetFingerImages()
        // renderState already covers the Failed path; this call is for onResume, which reaches
        // here directly without a state change.
        setFingerAnimationsVisible(true)
        setMessage("Device is in sleep mode. Please touch both finger sensors to wake them up.")
        setCancelButtonVisible(false)
        startSleepModePolling(checkSessionAfterWake)
    }

    /**
     * Polls [ScannerSessionManager.areBothReadersAwake] every 2 seconds for up to 30 seconds
     * while the readers are asleep ([ScannerState.Failed] with `isLowPowerEnabled`).
     *
     * The Start Scan button stays hidden for the whole window. If the readers wake up within
     * the window, the button is re-shown with an "awake" message. If they never respond, the
     * user is asked via [showReaderNoResponseDialog] whether to keep waiting or end the session.
     *
     * @param checkSessionAfterWake  See [handleFailedSleepMode].
     */
    private fun startSleepModePolling(checkSessionAfterWake: Boolean = false) {
        sleepPollJob?.cancel()
        setStartButtonVisible(false)
        sleepPollJob = lifecycleScope.launch {
            var elapsedMs = 0L
            while (elapsedMs < SLEEP_POLL_TIMEOUT_MS) {
                delay(SLEEP_POLL_INTERVAL_MS.milliseconds)
                elapsedMs += SLEEP_POLL_INTERVAL_MS
                if (sessionManager.areBothReadersAwake()) {
                    if (checkSessionAfterWake && !sessionManager.checkSessionHealth()) {
                        logDebug("UpdatedScannerActivity :: onResume → readers awake but session unhealthy, reinitializing")
                        initializeHardware()
                        return@launch
                    }
                    // The readers woke without a state change, so renderState won't fire —
                    // stop the animations here or they would keep animating behind "Start Scan".
                    setFingerAnimationsVisible(false)
                    setMessage("Readers are ready to scan.")
                    setStartButton("Start Scan", visible = true)
                    return@launch
                }
            }
            showReaderNoResponseDialog(checkSessionAfterWake)
        }
    }

    /**
     * Shown when the readers fail to wake within [SLEEP_POLL_TIMEOUT_MS]. "Yes" restarts the
     * 30-second wake check; "No" ends the session the same way the Cancel button does.
     *
     * @param checkSessionAfterWake  See [handleFailedSleepMode]; preserved across the restart.
     */
    private fun showReaderNoResponseDialog(checkSessionAfterWake: Boolean = false) {
        runOnUiThread {
            readerNoResponseDialog = AlertDialog.Builder(this)
                .setMessage("There is no response from the readers. Do you want to continue this session")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    startSleepModePolling(checkSessionAfterWake)
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    cancelSession()
                }
                .show()
        }
    }

    /**
     * The initialization-time counterpart of [showReaderNoResponseDialog], shown when
     * [ScannerEvent.ReadersNotResponding] arrives because the readers stayed asleep for
     * [ScannerSessionManager.WAKE_TIMEOUT_MS].
     *
     * Unlike the scan-time dialog, the wait itself lives inside
     * [ScannerSessionManager.awaitReadersAwake], which is suspended until the answer is passed
     * back through [ScannerSessionManager.onWakeWaitDecision] — so both buttons must report a
     * decision, otherwise initialization stays parked forever.
     */
    private fun showInitWakeNoResponseDialog() {
        runOnUiThread {
            readerNoResponseDialog = AlertDialog.Builder(this)
                .setMessage("There is no response from the readers. Do you want to continue this session")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    sessionManager.onWakeWaitDecision(keepWaiting = true)
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    sessionManager.onWakeWaitDecision(keepWaiting = false)
                    cancelSession()
                }
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Event handling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Routes each [ScannerEvent] to the appropriate UI or business logic handler.
     * Called for every event emitted by both readers.
     *
     * @param event  The event from [ScannerSessionManager.events].
     */
    private fun handleEvent(event: ScannerEvent) {
        when (event) {

            is ScannerEvent.PreviewFrame -> {
                renderPreviewBitmap(event.readerNo, event.bitmap, event.status)
                handlePreviewStatus(event.status, event.previewType, event.readerNo)
                renderLiveness(event.readerNo, event.liveness, event.thresholdLiveness, event.spoof)
            }

            is ScannerEvent.ExtractionDone -> {
                handleExtractionResult(event.readerNo, event.status)
            }

            is ScannerEvent.IdentificationDone -> {
                handleIdentificationResult(event.readerNo, event.result)
            }

            is ScannerEvent.FileSaved -> {
                when (event.fileType) {
                    ScannerEvent.FileSaved.FileType.WSQ ->
                        scannedFilePaths.add(File(event.path))

                    ScannerEvent.FileSaved.FileType.BITMAP ->
                        scannedFilePaths.add(File(event.path))

                    ScannerEvent.FileSaved.FileType.TEMPLATE -> {
                        templateFilePaths.add(File(event.path))
                        onTemplateSaved(event.readerNo, event.path)
                    }

                    ScannerEvent.FileSaved.FileType.BMP -> {
                        if (uploadBmpToFirebase && !skipFirebaseActions) {
                            val uid = scanningOptions?.uniqueId ?: return
                            lifecycleScope.launch(Dispatchers.IO) {
                                userRepository.uploadBmpFile(uid, Uri.fromFile(File(event.path)))
                            }
                        }
                    }
                }
            }

            is ScannerEvent.Message -> {
                appendMessage(event.text, event.isError)
            }

            is ScannerEvent.DeviceInSleepMode -> {
                logError("UpdatedScannerActivity :: Reader ${event.readerNo} is in sleep mode")
                appendMessage("Reader ${event.readerNo + 1} is in sleep mode. Please reinitialise.", true)
            }

            is ScannerEvent.ReadersNotResponding -> {
                showInitWakeNoResponseDialog()
            }

            is ScannerEvent.SpoofDetected -> {
                logError("UpdatedScannerActivity :: ${event.kind} on reader ${event.readerNo}: ${event.detail}")
                // Pin the offending reader's panel red and name the reason there; the shared
                // status line and Retry button come from renderState(Failed) right after.
                readerNoticePinned = true
                setReaderMessage(event.readerNo, spoofHeadline(event.kind))
                tintLiveness(event.readerNo, R.color.liveness_error)
                appendMessage(event.detail, isError = true)
            }

            is ScannerEvent.SensorDirty -> {
                logError("UpdatedScannerActivity :: dirty sensor on reader ${event.readerNo}")
                readerNoticePinned = true
                setReaderMessage(event.readerNo, "Sensor may be dirty")
                tintLiveness(event.readerNo, R.color.liveness_warn)
                appendMessage(event.detail, isError = true)
            }

            is ScannerEvent.ReaderError -> {
                logError("UpdatedScannerActivity :: Reader ${event.readerNo} error: ${event.cause.message}")
            }

            is ScannerEvent.ScanCompleted -> {
                // Individual reader done — state machine moves to Success once both are done.
                logDebug("UpdatedScannerActivity :: Reader ${event.result.readerNo} scan completed")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start button flow
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Handles Start button clicks. The appropriate action depends on the current [ScannerState]:
     * - [ScannerState.Ready] → start a new scan pass.
     * - [ScannerState.Failed] / [ScannerState.Cancelled] → re-initialise hardware.
     * - [ScannerState.Success] → deliver the result, unless [retryAfterLowQuality] is set, in
     *   which case start another scan pass instead.
     * - Other states → no-op (prevents double-tapping during transitions).
     */
    private fun handleStartClick() {
        val storagePath = scanningOptions?.storagePath ?: DEFAULT_STORAGE_PATH
        when (val current = sessionManager.state.value) {
            is ScannerState.Ready -> {
                sleepModeTrack = 0
                beginScanPass(storagePath)
            }

            is ScannerState.Failed -> {
                clearSessionData()
                // Retry after a failure — including a spoof or dirty-pad rejection, whose
                // explanation is in the log and must not linger behind the next attempt.
                clearMessages()
                when {
                    sessionManager.isLowPowerEnabled && sleepModeTrack <= 3 -> {
                        // Readers are sleeping but sessions are still open.
                        // Retry the scan — the hardware wakes on finger contact or the first
                        // extract() call.  startScan() clears isLowPowerEnabled automatically.
                        // Wait 2 seconds to give the device time to wake up before scanning.
                        setMessage("Initializing sensor, please wait…")
                        setCancelButtonVisible(true)
                        setStartButtonVisible(false)
                        lifecycleScope.launch {
                            delay(2000.milliseconds)
                            delay(2000.milliseconds)
                            sessionManager.startScan(storagePath)
                        }
                    }

                    sessionManager.isLowPowerEnabled -> {
                        // Too many consecutive sleep-mode failures (sleepModeTrack > 6).
                        // Bypass resetToReady() and force a full USB power-cycle + reinit,
                        // because the device is stuck and won't recover from a scan retry alone.
                        sleepModeTrack = 0
                        showInitDialogUnlessSleeping()
                        sessionManager.initialize()
                    }

                    current.reason.contains("Invalid operation", ignoreCase = true) -> {
                        // "Invalid operation" means the SDK session itself is wedged — it can
                        // still report isSessionOpen == true, so initializeHardware()'s
                        // resetToReady() fast path would wrongly call it healthy and hand back
                        // a session that immediately fails again. Force a full USB power-cycle
                        // + reinit instead of trusting that shortcut.
                        sleepModeTrack = 0
                        resetFingerImages()
                        showInitDialogUnlessSleeping()
                        sessionManager.initialize()
                    }

                    else -> {
                        // Genuine hardware failure (not sleep mode).
                        // Use initializeHardware() so resetToReady() can skip the power cycle
                        // if the session is still open (e.g. transient scan error).
                        sleepModeTrack = 0
                        resetFingerImages()
                        initializeHardware()
                    }
                }
            }

            is ScannerState.Cancelled -> {
                clearSessionData()
                clearMessages()
                sleepModeTrack = 0
                initializeHardware()
            }

            is ScannerState.Success -> {
                // handleLowQuality() leaves the session in Success — it only repaints the button
                // as "Scan again" — so this branch has to distinguish a rescan from a delivery.
                // Without the flag the click would fall through to deliverRegistrationResult()
                // and report "Fingerprint not found", because handleLowQuality() already cleared
                // the file lists that method delivers.
                if (retryAfterLowQuality) {
                    retryAfterLowQuality = false
                    beginScanPass(storagePath)
                    return
                }
                // Do NOT clear session data here — templateFilePaths must still be populated.
                // For VERIFICATION the dialog shown in handleScanSuccess() handles delivery;
                // tapping Done in that state is a no-op to avoid a double-finish.
                if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                    deliverRegistrationResult()
                }
            }

            else -> logDebug("UpdatedScannerActivity :: handleStartClick ignored in state: $current")
        }
    }

    /**
     * Resets the per-scan UI and data, then starts a fresh scan pass.
     *
     * Shared by the [ScannerState.Ready] and low-quality-retry paths of [handleStartClick].
     * [ScannerSessionManager.startScan] accepts Ready, Success and Failed, so a retry from
     * Success needs no intermediate state transition.
     *
     * @param storagePath  Directory for the WSQ/JPEG output of this pass.
     */
    private fun beginScanPass(storagePath: String) {
        clearSessionData()
        clearMessages()
        setMessage("Initializing sensor, please wait…")
        tvStatusLeft?.text = ""
        tvStatusRight?.text = ""
        resetLivenessDisplay()
        setCancelButtonVisible(true)
        setStartButtonVisible(false)
        sessionManager.startScan(storagePath)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scan result handling
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called when [ScannerState.Success] is received — both readers finished without error.
     * For registration: records quality, triggers cloud upload.
     * For verification: if both identification results are OK, calls [finishWithVerificationResult].
     *
     * @param r0  Result from reader 0.
     * @param r1  Result from reader 1.
     */
    private fun handleScanSuccess(r0: ReaderResult, r1: ReaderResult) {
        // The panels keep showing liveness — the number certification actually tests — so the
        // final peak replaces the last live frame rather than being overwritten by quality.
        renderFinalLiveness(r0)
        renderFinalLiveness(r1)

        if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
            reportQuality(r0)
            reportQuality(r1)

            if (r0.quality > 0 && r1.quality > 0) {
                val leftPct = qualityToPercent(r0.quality)
                val rightPct = qualityToPercent(r1.quality)
                if (leftPct < 50 || rightPct < 50) {
                    handleLowQuality()
                    return
                }
            }

            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Registration Successful")
                    .setMessage("Your account has been successfully created using the provided BVN and fingerprint details. You can now proceed with transactions and fingerprint verification.")
                    .setPositiveButton("OK") { d, _ -> d.dismiss() }
                    .show()
            }
        } else {
            // Verification: both identification results must be OK.
            val bothOk = identificationResults.size >= 2 &&
                    identificationResults.values.all { it?.status == NBBiometricsStatus.OK }
            if (bothOk) {
                setMessage("Fingerprint verified successfully.")
                showVerificationResultDialog(success = true) {
                    finishWithVerificationResult(true)
                }
                saveVerificationTransaction()
            } else {
                setMessage("No match found.")
                showVerificationResultDialog(success = false) {
                    finishWithVerificationResult(false)
                }
            }
        }
    }

    /**
     * Called when a per-reader extraction event is received.
     * Tracks successful extractions; when both readers succeed the registration flow is complete.
     *
     * @param readerNo  Which reader finished.
     * @param status    Extraction result status from the SDK.
     */
    private fun handleExtractionResult(readerNo: Int, status: NBBiometricsStatus?) {
        when (status) {
            NBBiometricsStatus.OK -> {
                extractionSuccess[readerNo] = true
                logDebug("UpdatedScannerActivity :: extraction OK for reader $readerNo")
            }

            NBBiometricsStatus.BAD_QUALITY -> {
                runOnUiThread {
                    setMessage("Poor scan quality. Please try again.")
                    setStartButton("Scan again", visible = true)
                    setCancelButtonVisible(false)
                }
            }

            else -> logDebug("UpdatedScannerActivity :: extraction status for reader $readerNo: $status")
        }
    }

    /**
     * Called when a per-reader identification event is received.
     * Accumulates identification results; when both readers respond the verification outcome is
     * determined in [handleScanSuccess].
     *
     * @param readerNo  Which reader finished.
     * @param result    Full identification result; null on error.
     */
    private fun handleIdentificationResult(
        readerNo: Int,
        result: com.nextbiometrics.biometrics.NBBiometricsIdentifyResult?,
    ) {
        if (result == null) {
            appendMessage("Verification failed. No valid fingerprint found. Please try again.", isError = true)
            return
        }
        identificationResults[readerNo] = result
        when (result.status) {
            NBBiometricsStatus.OK ->
                logDebug("UpdatedScannerActivity :: identification OK for reader $readerNo (template: ${result.templateId})")

            NBBiometricsStatus.MATCH_NOT_FOUND ->
                appendMessage("No matching fingerprint found for reader $readerNo.", isError = true)

            else ->
                appendMessage("Identification status for reader $readerNo: ${result.status}", isError = false)
        }
    }

    /**
     * Handles a saved template file: adds it to the local path list and triggers cloud upload
     * when not in [skipFirebaseActions] mode.
     *
     * @param readerNo   The reader that produced this template.
     * @param localPath  Absolute path of the saved .dat file.
     */
    private fun onTemplateSaved(readerNo: Int, localPath: String) {
        localTemplatePaths.add(localPath)
        if (skipFirebaseActions) return

        val uid = scanningOptions?.uniqueId ?: return

        // Update local path list in Firestore immediately.
        lifecycleScope.launch(Dispatchers.IO) {
            userRepository.updateUserFields(uid, mapOf("fingerPrintLocalPath" to localTemplatePaths))
        }

        // Upload this reader's template to cloud storage.
        lifecycleScope.launch(Dispatchers.IO) {
            userRepository.uploadTemplateFile(uid, Uri.fromFile(File(localPath)))
                .onSuccess { remotePath ->
                    templateUploadPaths[readerNo] = remotePath
                    // When both templates are uploaded, mark the user as cloud-synced.
                    if (templateUploadPaths.size >= 2) {
                        val cloudPaths = templateUploadPaths.values.toList()
                        userRepository.markUserAsSynced(uid, cloudPaths, localTemplatePaths.size)
                        logDebug("UpdatedScannerActivity :: user $uid marked as cloud-synced")
                    }
                }
                .onFailure { logError("UpdatedScannerActivity :: template upload failed for reader $readerNo: ${it.message}") }
        }
    }

    /**
     * Handles device scan status previews, updating the UI status text when the status changes
     * to a user-relevant state (e.g. "put finger on sensor", "keep finger on sensor").
     *
     * @param status       The scan status from the preview event.
     * @param previewType  Whether this is an extraction or identification preview.
     * @param readerNo     Which reader generated this status.
     */
    private fun handlePreviewStatus(
        status: NBDeviceScanStatus,
        previewType: PreviewListenerType,
        readerNo: Int,
    ) {
        if (status == NBDeviceScanStatus.PUT_FINGER_ON_SENSOR || status == NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR) {
            fingerSeenOnSensor[readerNo] = true
        }

        val message = when (status) {
            NBDeviceScanStatus.PUT_FINGER_ON_SENSOR -> "Place your finger on the sensor."
            NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR -> "Please keep your finger on the sensor."

            NBDeviceScanStatus.LIFT_FINGER ->
                if (fingerSeenOnSensor[readerNo] == true) {
                    "Please lift your finger."
                } else {
                    // A LIFT_FINGER with no prior PUT/KEEP on this reader in this pass is a
                    // stale finger-presence latch from the hardware, not a real user action —
                    // nothing was ever placed to lift. Show the correct instruction instead.
                    logDebug("UpdatedScannerActivity :: reader $readerNo reported LIFT_FINGER with no prior finger-on-sensor — treating as stale hardware state")
                    "Place your finger on the sensor."
                }

            NBDeviceScanStatus.WAIT_FOR_SENSOR_INITIALIZATION -> "Initializing sensor, please wait…"
            NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING -> "Processing…"
            else -> null  // no visible status change needed
        }
        message?.let {
            setReaderMessage(readerNo, it)
            setMessage("")
        }
    }

    /** Shows the low-quality retry prompt and schedules a new scan pass. */
    private fun handleLowQuality() {
        clearSessionData()
        // Must be set AFTER clearSessionData(), which clears it.
        retryAfterLowQuality = true
        setMessage("Scan quality below 50%. Please try again.")
        setStartButton("Scan again", visible = true)
        setCancelButtonVisible(false)
        resetFingerImages()

        // Optionally delete existing cloud files for this user before re-scanning.
        if (!skipFirebaseActions) {
            scanningOptions?.uniqueId?.let { uid ->
                lifecycleScope.launch(Dispatchers.IO) {
                    userRepository.deleteCloudTemplatesForUser(uid)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Initialization flow
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts the GPS fetch and the pre-scan checks **at the same time**.
     *
     * Location is best-effort metadata — nothing in the scan or verification decision depends
     * on it — so making the operator watch a dialog for up to 30 seconds before the hardware
     * even begins initialising cost real time on every launch. The fetch now runs in the
     * background and [startLocationFetch] patches the coordinates into Firestore if they land
     * after the user record was written.
     */
    private fun startLocationThenInitialize() {
        if (!skipLocation && hasLocationPermission()) {
            startLocationFetch()
            performPreScanChecks()
        } else if (!skipLocation) {
            // Permission dialog is modal, so there is nothing to run alongside it. The launcher
            // callback starts both halves once the user answers.
            requestLocationPermissions()
        } else {
            // skipLocation: drop any fix left behind by a previous session rather than
            // attributing it to this user — `location` is static and outlives the activity.
            location = null
            performPreScanChecks()
        }
    }

    /**
     * Fetches the device's GPS position in the background, with a 30-second timeout.
     *
     * Never blocks initialisation and shows no dialog. Because it now races the Firestore write
     * in [saveUserToFirestore], a fix that arrives after that write is patched onto the record
     * by [patchStoredLocation] — otherwise a slow fix would silently store `[null, null]`.
     */
    private fun startLocationFetch() {
        // Clear the previous session's fix first: `location` is a companion-object field that
        // outlives the activity, so a stale value would otherwise be attributed to this user.
        location = null
        var settled = false

        fun settle() {
            if (settled) return
            settled = true
            locationTimeoutRunnable?.let { locationHandler.removeCallbacks(it) }
            locationTimeoutRunnable = null
            locationWrapper.stopUpdates()
        }

        locationTimeoutRunnable = Runnable {
            logDebug("UpdatedScannerActivity :: location fetch timed out after 30s")
            settle()
        }
        locationHandler.postDelayed(locationTimeoutRunnable!!, 30_000L)

        locationWrapper.getLocation { latLng ->
            location = latLng
            settle()
            if (latLng != null) patchStoredLocation(latLng)
        }
    }

    /**
     * Writes coordinates onto an already-saved user record.
     *
     * Only fires when the fix arrived after [saveUserToFirestore] had run — detected via
     * [userRecordSaved], which that method sets. A no-op for verification (no record is
     * written) and when Firebase is skipped entirely.
     */
    private fun patchStoredLocation(latLng: LatLng) {
        if (skipFirebaseActions || !userRecordSaved) return
        val uid = scanningOptions?.uniqueId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            userRepository.updateUserFields(
                uid,
                mapOf("gpsCoordinates" to arrayListOf(latLng.latitude, latLng.longitude)),
            ).onSuccess {
                logDebug("UpdatedScannerActivity :: late GPS fix patched onto user record")
            }
        }
    }

    /**
     * Checks user existence and registration state in Firestore, then decides whether to
     * download templates (verification) or skip (registration) before calling [initializeHardware].
     *
     * When [skipFirebaseActions] is true all Firestore checks are bypassed.
     */
    private fun performPreScanChecks() {
        if (skipFirebaseActions) {
            initializeHardware()
            return
        }

        val uid = scanningOptions?.uniqueId
        if (uid.isNullOrBlank()) {
            logError("UpdatedScannerActivity :: uniqueId is null — cannot proceed")
            return
        }

        val loadingDialog = fetchingUserDB(scanningOptions?.themeOptions) {}

        lifecycleScope.launch {
            val userResult = userRepository.getUser(uid)
            loadingDialog.dismiss()

            val user = userResult.getOrNull()
            currentUser = user

            when (scanningOptions?.scanningType) {
                ScanningType.REGISTRATION -> {
                    // Block re-registration if the user is already fully synced.
                    val cloudComplete = userRepository.isCloudStorageComplete(uid).getOrDefault(false)
                    if (user?.fingerPrintSyncedOnCloud == true && cloudComplete) {
                        showMessageAndFinish(
                            "Already Registered",
                            "This BVN is already registered. Please use a new unique ID.",
                        )
                        return@launch
                    }
                    // Save a placeholder user record, then initialise hardware.
                    saveUserToFirestore()
                    initializeHardware()
                }

                ScanningType.VERIFICATION -> {
                    if (user == null) {
                        showMessageAndFinish(
                            "Account not found",
                            "No account was found for the BVN entered. Please verify your details or register first.",
                        )
                        return@launch
                    }
                    ensureLocalTemplatesExist(uid)
                }

                null -> {
                    logError("UpdatedScannerActivity :: scanningType is null")
                }
            }
        }
    }

    /**
     * Checks if local template files already exist for [uid].
     * If they do, calls [initializeHardware] directly.
     * If not, downloads them from Firebase Storage first.
     *
     * @param uid  The unique user identifier.
     */
    private suspend fun ensureLocalTemplatesExist(uid: String) {
        val templateDir = File(filesDir, uid)
        val hasLocalFiles = templateDir.exists() && (templateDir.listFiles()?.isNotEmpty() == true)

        if (hasLocalFiles) {
            initializeHardware()
            return
        }

        showDownloadDialog()
        val result = userRepository.downloadTemplatesForUser(uid, templateDir)
        hideDownloadDialog()

        if (result.isSuccess && result.getOrDefault(0) > 0) {
            initializeHardware()
        } else {
            showMessageAndFinish("Download failed", "Could not download fingerprint templates. Please try again.")
        }
    }

    /**
     * Triggers hardware initialisation via [ScannerSessionManager.initialize].
     * Shows the initialisation dialog; the dialog is dismissed when [ScannerState.Ready] is received.
     */
    private fun initializeHardware() {
        // If the readers are still alive from a previous session, just reset the state to
        // Ready so the UI shows "Start Scan" without re-running the slow hardware init.
        // resetToReady() returns false and sets Failed if either session has dropped, in
        // which case we fall through to a full reinitialise.
        if (sessionManager.resetToReady()) return
        showInitDialogUnlessSleeping()
        sessionManager.initialize()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Result delivery
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Packages scanned file paths and template file paths into a result [Intent] and
     * finishes the activity with [RESULT_OK].
     */
    private fun deliverRegistrationResult() {
        if (scannedFilePaths.isEmpty() && templateFilePaths.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Fingerprint not found.")
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
            return
        }
        val intent = Intent().apply {
            putExtra(ScannerConstants.DATA, scannedFilePaths as java.io.Serializable)
            putExtra(ScannerConstants.TEMPLATE_DATA, templateFilePaths as java.io.Serializable)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    /**
     * Finishes the activity with the boolean verification outcome.
     *
     * @param success  True if fingerprints matched, false otherwise.
     */
    private fun finishWithVerificationResult(success: Boolean) {
        val intent = Intent().apply {
            putExtra(ScannerConstants.VERIFICATION_RESULT, success)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Firebase helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates or overwrites the user record in Firestore, pre-populating it with all available
     * metadata from [scanningOptions] (device ID, GPS coordinates, custom fields, etc.).
     */
    private fun saveUserToFirestore() {
        val options = scanningOptions ?: return
        val uid = options.uniqueId ?: return

        val extraFields = mutableMapOf<String, Any>()
        options.customObject?.let { jsonObj ->
            for (key in jsonObj.keys()) {
                extraFields[key] = jsonObj.get(key)
            }
        }

        // Snapshot the fix as it stands now: the background GPS fetch may complete between
        // building this record and the write landing, and only a record written *without*
        // coordinates needs patching afterwards.
        val fixAtWrite = location

        val user = User(
            uniqueId = uid,
            deviceId = getAndroidDeviceId(),
            userId = options.userId,
            phoneNumber = options.phoneNumber ?: "",
            bankProvider = options.bankProvider,
            loginType = options.loginType,
            type = if (options.scanningType == ScanningType.REGISTRATION) Constant.REGISTRATION else Constant.TRANSACTION,
            fingerprintVerificationStatus = false,
            fingerPrintCount = 0,
            fingerPrintLocalPath = arrayListOf(),
            fingerPrintCloudPath = arrayListOf(),
            fingerPrintSyncedOnCloud = false,
            timestamp = Date(),
            gpsCoordinates = arrayListOf(fixAtWrite?.latitude, fixAtWrite?.longitude),
            customObject = extraFields,
        )

        lifecycleScope.launch(Dispatchers.IO) {
            userRepository.saveUser(user)
                .onSuccess {
                    currentUser = user
                    userRecordSaved = true
                    logDebug("UpdatedScannerActivity :: user saved to Firestore")
                    // The GPS fetch runs alongside this write, so a fix can land while it is in
                    // flight — in which case the record above went out with nulls and needs the
                    // coordinates adding. Only when it was written without them.
                    if (fixAtWrite == null) location?.let { patchStoredLocation(it) }
                }
                .onFailure { logError("UpdatedScannerActivity :: user save failed: ${it.message}") }
        }
    }

    /**
     * Writes a new transaction record to Firestore after a successful verification.
     * Only called when [skipFirebaseActions] is false and verification succeeded.
     */
    private fun saveVerificationTransaction() {
        if (skipFirebaseActions) return
        val uid = scanningOptions?.uniqueId ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            userRepository.updateUserFields(
                uid,
                mapOf(
                    "fingerprintVerificationStatus" to true,
                    "lastVerifiedAt" to Date().time,
                ),
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Updates the status text on the main thread. */
    private fun setMessage(message: String) {
        runOnUiThread { tvStatus?.text = message }
    }

    /**
     * Updates the live status text for a single reader (0 = left, 1 = right) on the main thread.
     * Kept separate from [setMessage] since both readers run concurrently and independently —
     * routing their preview status through one shared label makes them appear to contradict
     * each other when in fact they're just two different readers at different points in their
     * own scan cycle.
     */
    private fun setReaderMessage(readerNo: Int, message: String) {
        runOnUiThread {
            if (readerNo == 0) tvStatusLeft?.text = message else tvStatusRight?.text = message
        }
    }

    /** Appends a message row to the scrollable message log. */
    private fun appendMessage(text: String, isError: Boolean) {
        /*runOnUiThread {
            val textView = TextView(applicationContext).apply {
                if (isError && context != null) setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.error_message_color
                    )
                )
                append(text)
            }
            messagesHolder?.addView(textView)
            lastMessageView = textView
        }*/
    }

    /** Sets the Start button text and visibility. */
    private fun setStartButton(text: String, visible: Boolean) {
        runOnUiThread {
            btnStart?.text = text
            btnStart?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Shows or hides the Start button without changing its text. */
    private fun setStartButtonVisible(visible: Boolean) {
        runOnUiThread {
            btnStart?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Shows or hides the Cancel button. */
    private fun setCancelButtonVisible(visible: Boolean) {
        runOnUiThread {
            btnCancel?.visibility = if (visible) View.VISIBLE else View.GONE
            btnCancel?.isEnabled = visible
        }
    }

    /**
     * Renders a new preview [Bitmap] from the specified reader into the corresponding
     * left/right ImageView.
     *
     * @param readerNo  0 = left, 1 = right.
     * @param bitmap    The preview bitmap to display.
     */
    private fun renderPreviewBitmap(readerNo: Int, bitmap: Bitmap?, status: NBDeviceScanStatus) {
        runOnUiThread {
            val imageView = if (readerNo == 0) ivScannerLeft else ivScannerRight
            if (status == NBDeviceScanStatus.PUT_FINGER_ON_SENSOR || status == NBDeviceScanStatus.WAIT_FOR_SENSOR_INITIALIZATION) {
                imageView?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_android_fingerprint_grey))
                return@runOnUiThread
            }
            // A status-only frame carries no pixels. Leave whatever was last drawn in place —
            // blanking here would make the panel flicker every time the sensor reports progress
            // without an image, and the frame's liveness numbers are rendered separately.
            bitmap?.let { imageView?.setImageBitmap(it) }
        }
    }

    /**
     * Updates the quality percentage label for [readerNo].
     * NFIQ scores are in the range 1–5 where 1 is the best; we convert them to a 0–100%
     * scale (higher = better) for display purposes.
     *
     * @param readerNo  0 = left, 1 = right.
     * @param nfiqScore Raw NFIQ quality score.
     */
    /**
     * Reports a finished reader's template quality into the message log.
     *
     * Quality no longer takes the headline label — that shows liveness now — but it still
     * gates the low-quality retry, so the operator needs to see the number that caused it.
     */
    private fun reportQuality(result: ReaderResult) {
        if (result.quality <= 0) return
        appendMessage(
            "Reader ${result.readerNo + 1} template quality: ${qualityToPercent(result.quality)}%",
            isError = false,
        )
    }

    /** Pins a finished reader's panel to the peak liveness measured during the pass. */
    private fun renderFinalLiveness(result: ReaderResult) {
        renderLiveness(
            readerNo = result.readerNo,
            liveness = result.livenessScore,
            threshold = result.livenessThreshold,
            spoof = false,
        )
    }

    /**
     * Renders one reader's live anti-spoof readout: the headline percentage on the quality
     * label, and the raw score against the threshold underneath the status line.
     *
     * The percentage is **of the pass threshold**, not of an assumed full scale. The liveness
     * score has no known ceiling — genuine fingers have measured above 44000 against a 32768
     * threshold — so inventing a maximum would misreport every reading. 100% here means "at or
     * above the bar the device will actually accept".
     *
     * @param readerNo   0 = left, 1 = right.
     * @param liveness   Raw liveness score from this preview frame.
     * @param threshold  Cutoff programmed into that reader; 0 when anti-spoof is inactive.
     * @param spoof      Whether this individual frame read as a spoof (tint only — the verdict
     *                   comes from the scan result, not from preview frames).
     */
    private fun renderLiveness(readerNo: Int, liveness: Int, threshold: Int, spoof: Boolean) {
        // Anti-spoof unsupported or not yet programmed: leave the labels to the quality flow
        // rather than showing a meaningless 0%.
        if (threshold <= 0) return

        val pct = ((liveness.toLong() * 100) / threshold).toInt().coerceIn(0, 100)
        val colour = when {
            spoof -> R.color.liveness_error
            liveness >= threshold -> R.color.liveness_ok
            else -> R.color.liveness_warn
        }
        runOnUiThread {
            val quality = if (readerNo == 0) tvLeftQuality else tvRightQuality
            val detail = if (readerNo == 0) tvLeftLiveness else tvRightLiveness
            quality?.text = getString(R.string.liveness_percent, pct)
            quality?.setTextColor(ContextCompat.getColor(this, colour))
            detail?.text = getString(R.string.liveness_detail, liveness, threshold)
            detail?.setTextColor(ContextCompat.getColor(this, colour))
        }
    }

    /** Forces one reader's liveness labels to [colourRes], for a terminal outcome. */
    private fun tintLiveness(readerNo: Int, colourRes: Int) {
        runOnUiThread {
            val colour = ContextCompat.getColor(this, colourRes)
            if (readerNo == 0) {
                tvLeftQuality?.setTextColor(colour)
                tvLeftLiveness?.setTextColor(colour)
            } else {
                tvRightQuality?.setTextColor(colour)
                tvRightLiveness?.setTextColor(colour)
            }
        }
    }

    /** Clears both readers' liveness labels back to their neutral state. */
    private fun resetLivenessDisplay() {
        readerNoticePinned = false
        runOnUiThread {
            val neutral = ContextCompat.getColor(this, R.color.liveness_neutral)
            listOf(tvLeftLiveness, tvRightLiveness).forEach {
                it?.text = ""
                it?.setTextColor(neutral)
            }
            listOf(tvLeftQuality, tvRightQuality).forEach {
                it?.text = ""
                it?.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
        }
    }

    /** Short headline for the offending reader's panel. */
    private fun spoofHeadline(kind: ScannerEvent.SpoofDetected.SpoofKind): String = when (kind) {
        ScannerEvent.SpoofDetected.SpoofKind.FAKE_FINGER -> "Fake finger detected"
        ScannerEvent.SpoofDetected.SpoofKind.LATENT_PRINT -> "Latent print detected"
    }

    /**
     * Shows or hides the two pointing-hand animations that accompany the low-power wake prompt.
     *
     * The [AnimatedVectorDrawableCompat] only runs while the views are visible: hiding clears the
     * looping callback, stops the drawable and detaches it, so nothing animates off-screen.
     *
     * Calls that match the current visibility are ignored, so repeated renders of the same state
     * don't restart the animation mid-cycle.
     */
    private fun setFingerAnimationsVisible(visible: Boolean) {
        runOnUiThread {
            val left = ivLeftFingerGif ?: return@runOnUiThread
            val right = ivRightFingerGif ?: return@runOnUiThread
            if (visible == (left.isVisible)) return@runOnUiThread

            if (visible) {
                left.visibility = View.VISIBLE
                right.visibility = View.VISIBLE
                leftFingerAvd = startFingerAnimation(left)
                rightFingerAvd = startFingerAnimation(right)
            } else {
                stopFingerAnimation(left, leftFingerAvd)
                stopFingerAnimation(right, rightFingerAvd)
                leftFingerAvd = null
                rightFingerAvd = null
                left.visibility = View.GONE
                right.visibility = View.GONE
            }
        }
    }

    /**
     * Loads [R.drawable.avd_fingerpoint] into [view] and starts it on a loop.
     *
     * `avd_fingerpoint` declares no `repeatCount`, so it plays once and stops. The loop is driven
     * by [Animatable2Compat.AnimationCallback.onAnimationEnd] restarting it — posted rather than
     * called inline, because restarting an [AnimatedVectorDrawableCompat] from inside its own end
     * callback is not supported and silently no-ops. The visibility check in the callback stops
     * the loop dead if the view was hidden between the last frame and the post.
     *
     * @return The running drawable, kept so [stopFingerAnimation] can unregister its callback.
     */
    private fun startFingerAnimation(view: AppCompatImageView): AnimatedVectorDrawableCompat? =
        runCatching {
            val avd = AnimatedVectorDrawableCompat.create(this, R.drawable.avd_fingerpoint)
                ?: return@runCatching null
            view.setImageDrawable(avd)
            avd.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    if (view.visibility != View.VISIBLE) return
                    view.post { if (view.isVisible) avd.start() }
                }
            })
            avd.start()
            avd
        }.onFailure {
            logError("UpdatedScannerActivity :: finger animation failed: ${it.message}")
        }.getOrNull()

    /**
     * Stops [avd] and detaches it from [view].
     *
     * [AnimatedVectorDrawableCompat.clearAnimationCallbacks] must come first — otherwise
     * [stop] fires `onAnimationEnd`, which would immediately restart the loop.
     */
    private fun stopFingerAnimation(view: AppCompatImageView, avd: AnimatedVectorDrawableCompat?) {
        runCatching {
            avd?.clearAnimationCallbacks()
            avd?.stop()
            view.setImageDrawable(null)
        }
    }

    /** Resets both fingerprint preview images to the default placeholder drawable. */
    private fun resetFingerImages() {
        resetLivenessDisplay()
        runOnUiThread {
            tvStatusLeft?.text = ""
            tvStatusRight?.text = ""
            ivScannerLeft?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_android_fingerprint_grey))
            ivScannerRight?.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_android_fingerprint_grey))
        }
    }

    /**
     * Stops any in-flight scan/init work and wipes session state the instant the screen turns
     * off — the USB reader sessions are about to drop anyway, so there is nothing worth
     * preserving. Skips [ScannerState.Success]/[ScannerState.Cancelled]: a result is either
     * already being delivered to the caller or the session is already clear, and forcing a
     * cancel there would stomp a registration result the user hasn't acknowledged yet.
     */
    private fun clearOnScreenOff() {
        val current = sessionManager.state.value
        if (current is ScannerState.Success || current is ScannerState.Cancelled) return

        sleepPollJob?.cancel()
        sessionManager.cancelScan("Screen turned off")
        clearSessionData()
        sleepModeTrack = 0
        dismissInitDialog()
        hideDownloadDialog()
        runOnUiThread { runCatching { readerNoResponseDialog?.dismiss() } }
        readerNoResponseDialog = null
        resetFingerImages()
        setReaderMessage(0, "")
        setReaderMessage(1, "")
        clearMessages()
    }

    /**
     * Empties the scrollable message log.
     *
     * Called when the user starts a fresh attempt — Retry after a spoof or dirty-sensor
     * rejection included. Those outcomes append an explanation naming the offending reader,
     * and leaving it on screen behind the next attempt reads as though the new scan was
     * rejected too.
     *
     * Deliberately NOT part of [clearSessionData]: [handleLowQuality] calls that after
     * [reportQuality] has logged the figures that explain the retry, and wiping them there
     * would delete the reason while asking the user to scan again.
     */
    private fun clearMessages() {
        runOnUiThread {
            messagesHolder?.removeAllViews()
            lastMessageView = null
        }
    }

    /** Clears all per-scan accumulated data lists ready for a fresh scan pass. */
    private fun clearSessionData() {
        scannedFilePaths.clear()
        templateFilePaths.clear()
        uploadedCloudPaths.clear()
        localTemplatePaths.clear()
        extractionSuccess.clear()
        identificationResults.clear()
        templateUploadPaths.clear()
        fingerSeenOnSensor.clear()
        retryAfterLowQuality = false
        // sleepModeTrack is intentionally NOT reset here — it must accumulate across
        // retries so the > 6 threshold is reachable. Reset it explicitly at each
        // fresh scan start or after a full reinit.
    }

    /**
     * Returns the template quality as a 0–100% figure.
     *
     * This is the quality the extraction itself reports (higher is better), not NFIQ — the
     * reader stopped calling `NBDevice.GetImageQuality` because that helper leaves the SDK's
     * global last-error set, which made the *next* extract fail with "Invalid operation".
     * The scale is already percentage-like, so this only clamps it.
     */
    private fun qualityToPercent(templateQuality: Int): Int = templateQuality.coerceIn(0, 100)

    // ──────────────────────────────────────────────────────────────────────────
    // Dialog management
    // ──────────────────────────────────────────────────────────────────────────

    /** Shows the hardware initialization dialog (created lazily). */
    private fun showInitDialog() {
        runOnUiThread {
            runCatching {
                if (initDialog == null) initDialog = readersInitializationDialog(scanningOptions?.themeOptions)
                initDialog?.show()
            }
        }
    }

    /**
     * Shows the init dialog only when [ScannerSessionManager.initialize] is actually going to
     * initialise hardware.
     *
     * When the readers are asleep, `initialize()` publishes [ScannerState.AwaitingWake]
     * immediately and the UI shows the wake prompt instead — showing the dialog here first would
     * flash it on screen for a frame before [renderState] dismisses it.
     */
    private fun showInitDialogUnlessSleeping() {
        if (!sessionManager.isEitherReaderInLowPower()) showInitDialog()
    }

    /** Dismisses the hardware initialization dialog if it is showing. */
    private fun dismissInitDialog() {
        runOnUiThread { runCatching { initDialog?.dismiss() } }
    }

    /** Shows the template download dialog (created lazily). */
    private fun showDownloadDialog() {
        runOnUiThread {
            runCatching {
                if (downloadDialog == null) downloadDialog = templatesDownloadDialog(scanningOptions?.themeOptions)
                downloadDialog?.show()
            }
        }
    }

    /** Dismisses the template download dialog if it is showing. */
    private fun hideDownloadDialog() {
        runOnUiThread { runCatching { downloadDialog?.dismiss() } }
    }

    /**
     * Shows a verification result dialog and invokes [onDismiss] when the user taps OK.
     *
     * @param success    Whether to show the success or failure variant.
     * @param onDismiss  Lambda called after the user acknowledges the dialog.
     */
    private fun showVerificationResultDialog(success: Boolean, onDismiss: () -> Unit) {
        runOnUiThread {
            if (verificationResultDialog == null) {
                verificationResultDialog = verificationDialog(scanningOptions?.themeOptions, success) {
                    onDismiss()
                }
            }
        }
    }

    /**
     * Displays a modal [AlertDialog] then calls [finish] when the user dismisses it.
     *
     * @param title  Dialog title.
     * @param msg    Dialog body text.
     */
    private fun showMessageAndFinish(title: String, msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); finish() }
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Permissions
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns true if both ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION are granted. */
    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks if the storage (or media-images) permission needed for file writes is granted.
     * The required permission differs between Android API levels.
     */
    private fun checkStoragePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        }

    /** Launches the storage permission request dialog. */
    private fun requestStoragePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        else
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        storagePermissionLauncher.launch(perms)
    }

    /** Launches the location permission request dialog. */
    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                handleStartClick()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Storage permission required")
                    .setMessage("Storage access is needed to save fingerprint images. Please grant it in app settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).also {
                            it.data = Uri.fromParts("package", packageName, null)
                            startActivity(it)
                        }
                    }
                    .show()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startLocationFetch()
                performPreScanChecks()
            } else {
                // Location denied — still initialise (location is best-effort). Clear any fix
                // left by a previous session so it is not attributed to this user.
                location = null
                performPreScanChecks()
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns the Android Settings ANDROID_ID for the device, or empty string on failure. */
    @SuppressLint("HardwareIds")
    private fun getAndroidDeviceId(): String =
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: ""
        }.getOrDefault("")

    /**
     * Checks whether the distance between the device's current GPS position and the stored
     * user location is within the allowed transaction radius.
     *
     * @param userCoords  GPS coordinates stored for the user in Firestore.
     * @return True if within [Constant.TRANSACTION_DISTANCE] metres (or if location is unavailable).
     */
    private fun isWithinTransactionArea(userCoords: List<Double?>): Boolean {
        val userLat = userCoords.getOrNull(0) ?: return true
        val userLng = userCoords.getOrNull(1) ?: return true
        val currentLoc = location ?: return true
        val userLatLng = LatLng(userLat, userLng)
        val distanceMetres = SphericalUtil.computeDistanceBetween(currentLoc, userLatLng)
        return distanceMetres <= Constant.TRANSACTION_DISTANCE
    }
}
