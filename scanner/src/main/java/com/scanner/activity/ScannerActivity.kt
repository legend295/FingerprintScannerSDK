package com.scanner.activity

import android.Manifest
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.legend295.fingerprintscanner.R
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import com.google.gson.Gson
import com.google.maps.android.SphericalUtil
import com.newrelic.agent.android.NewRelic
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.scanner.app.ScannerApp
import com.scanner.model.Transaction
import com.scanner.model.User
import com.scanner.utils.NewRelicWrapper.logDebug
import com.scanner.utils.NewRelicWrapper.logError
import com.scanner.utils.builder.BuilderOptions
import com.scanner.utils.ReaderStatus
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.Constant.FINGER_PRINT_READ_INFO
import com.scanner.utils.constants.Keys.USER_COLLECTION_PATH
import com.scanner.utils.constants.Keys.FINGER_PRINT_SYNCED_ON_CLOUD
import com.scanner.utils.constants.Keys.TRANSACTION_COLLECTION_PATH
import com.scanner.utils.constants.Keys.UNIQUE_ID
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
import com.scanner.utils.fetchingLocationDialog
import com.scanner.utils.fetchingUserDB
import com.scanner.utils.helper.FingerprintListener
import com.scanner.utils.helper.OnFileSavedListener
import com.scanner.utils.helper.ReaderSessionHelper
import com.scanner.utils.location.LocationWrapper
import com.scanner.utils.readers.FingerprintHelper
import com.scanner.utils.readersInitializationDialog
import com.scanner.utils.templatesDownloadDialog
import com.scanner.utils.verificationDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

internal class ScannerActivity : AppCompatActivity() {


    private var tvStatus: AppCompatTextView? = null
    private var tvLeftQuality: AppCompatTextView? = null
    private var tvRightQuality: AppCompatTextView? = null
    private var btnStart: AppCompatButton? = null
    private var btnCancel: AppCompatButton? = null
    private var ivScannerLeft: AppCompatImageView? = null
    private var ivScannerRight: AppCompatImageView? = null
    private var tvScanFingerprints: AppCompatTextView? = null
    private var tvScanMessage: AppCompatTextView? = null
    private var messagesHolder: LinearLayout? = null
    private var scrollView: ScrollView? = null

    private var leftQuality: String? = null
    private var rightQuality: String? = null
    private var scanningOptions: BuilderOptions? = null

    private var list: ArrayList<File> =
        ArrayList() // this will store the response of the saved files
    private var templateList: ArrayList<File> =
        ArrayList() // this will store the response of the saved Template files
    private val listOfTemplate = ArrayList<NBBiometricsTemplate>()
    private var fingerprintHelper: FingerprintHelper? = null
    private var readerStatus: ReaderStatus = ReaderStatus.NONE
    private val tag = ScannerActivity::class.java.simpleName
    private val difFingerprintReadInfo = FINGER_PRINT_READ_INFO
    private var dialog: Dialog? = null
    private var templateDownloadDialog: Dialog? = null

    //for display message
    private var lastMessage: TextView? = null
    private val db = Firebase.firestore

    // Create a storage reference from our app
    private val storage = Firebase.storage
    private val storageRef = storage.reference
    private val uploadedFileRefs = ArrayList<String>()
    private val uploadedFileRefsCache = ArrayList<String>()
    private val localFileRefs = ArrayList<String>()
    private val localFileRefsCache = ArrayList<String>()

    //    var textResults: TextView? = null
    private var doWeNeedToReinitialize = true
    private var verificationDialog: Dialog? = null

    private val identificationResult = HashMap<Int, NBBiometricsIdentifyResult?>()
    private val locationWrapper: LocationWrapper = LocationWrapper(this)
    private var timer: CountDownTimer? = null
    private var alertDialog: AlertDialog? = null
    private var currentUser: User? = null
    private var skipLocation = false
    private var skipFirebaseActions = false

    // if sleepModeTrack is equal to 3 then reinitialize the fingerprints
    // increment this when every sleep model trigger
    private var sleepModeTrack = 0

    // variable handled to check if both fingers are scanned successfully
    // sometimes only one fingerprint get scanned and reader show successfully scanned. So, to solve this issue we are using this variable
    private var areBothFingerprintScannedSuccessfully = HashMap<Int, Boolean>()
    private var fingerprintFiles = HashMap<Int, File>()
    private var isFingerprintScanningInProgress: Boolean = false

    private var storagePath = STORAGE_PATH

    companion object {
        var location: LatLng? = null
        const val STORAGE_PATH = "biometrics/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        listOfTemplate.clear()
        list = ArrayList()
        templateList = ArrayList()
        identificationResult.clear()
        verificationDialog = null


        //Find views by id
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        ivScannerLeft = findViewById(R.id.ivScannerLeft)
        ivScannerRight = findViewById(R.id.ivScannerRight)
        tvLeftQuality = findViewById(R.id.tvLeftQuality)
        tvRightQuality = findViewById(R.id.tvRightQuality)
        messagesHolder = findViewById(R.id.messagesHolder)
        scrollView = findViewById(R.id.scrollView1)
        tvScanFingerprints = findViewById(R.id.tvScanFingerprints)
        tvScanMessage = findViewById(R.id.tvScanMessage)

        val bundle = intent.extras
        val options = bundle?.getString(Constant.SCANNING_OPTIONS)
        scanningOptions = Gson().fromJson(options, BuilderOptions::class.java)
        skipLocation = scanningOptions?.skipLocation ?: false
        skipFirebaseActions = scanningOptions?.skipFirebaseActions ?: false

        if (!skipFirebaseActions) {
            // get all users whose FINGER_PRINT_SYNCED_ON_CLOUD is false from cache and upload files
            getUserFromCache { it, isSuccess ->
                val userDocuments = ArrayList<DocumentSnapshot>()
                if (isSuccess) {
                    it?.let {
                        userDocuments.addAll(it.documents)
                        userDocuments.uploadFiles()
                    }
                }
            } //65112583554
        }

        setCustomTheme(scanningOptions?.themeOptions)

        // Initialize New Relic
        scanningOptions?.newRelicToken?.let {
            try {
                NewRelic.withApplicationToken(
                    it
                ).withLoggingEnabled(true).withCrashReportingEnabled(true).start(this)
            } catch (_: Exception) {
            }
        }


        /*  doWeNeedToReinitialize = true
          fingerprintHelper = FingerprintHelper(
              this
          )
  */
        if (ScannerApp.getInstance().fingerprintHelper == null) {
            doWeNeedToReinitialize = true
            fingerprintHelper = FingerprintHelper(
                this
            )
            ScannerApp.getInstance().fingerprintHelper = fingerprintHelper
        } else {
            doWeNeedToReinitialize = false
            fingerprintHelper = ScannerApp.getInstance().fingerprintHelper
        }

        areBothFingerprintScannedSuccessfully.clear()
        fingerprintFiles.clear()
        fingerprintHelper?.setSessionHelper(sessionHelper = onSessionChanges)
        fingerprintHelper?.setFingerprintListener(fingerprintListener = fingerprintListener)
        fingerprintHelper?.setListOfTemplate(listOfTemplate = listOfTemplate)
        fingerprintHelper?.setOnFileSaveListener(listener = onFileSavedListener(list, templateList))


        scanningOptions?.uniqueId?.let { fingerprintHelper?.setBvnNumber(it) }
            ?: run { fingerprintHelper?.setBvnNumber("common") }
        scanningOptions?.skipFirebaseActions?.let { fingerprintHelper?.setSkipFirebaseActions(it) }
        scanningOptions?.scanningType?.let { fingerprintHelper?.setScanningType(it) }
        scanningOptions?.key?.let { ScannerApp.getInstance().key = it }
        storagePath = scanningOptions?.storagePath ?: STORAGE_PATH

        // if we are not skipping the location then fetch user's location
        if (!skipLocation)
            if (checkPermissions()) {
                handleLocationEmpty()
            } else {
                requestPermissionLauncher.launch(
                    arrayOf(
                        ACCESS_COARSE_LOCATION,
                        ACCESS_FINE_LOCATION
                    )
                )
            }
        else init()




        btnStart?.setOnClickListener {
            if (checkStorageAndCameraPermission()) {
                handleClick()
            } else {
                requestStorageAndCameraPermission()
            }
        }

        btnCancel?.setOnClickListener {
            btnCancel?.isEnabled = false
            fingerprintHelper?.cancelTap()
            fingerprintHelper?.stop()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun setCustomTheme(themeOptions: ThemeOptions?) {
        try {
            themeOptions?.let {
                // Button Start Theme
                btnStart?.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, it.buttonColor))
                btnStart?.setTextColor(ContextCompat.getColor(this, it.buttonTextColor))

                // Button Cancel Theme
                btnCancel?.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, it.buttonColor))
                btnCancel?.setTextColor(ContextCompat.getColor(this, it.buttonTextColor))

                // Message Text Theme
                tvStatus?.setTextColor(ContextCompat.getColor(this, it.messageColor))

                // Title & Content Text Theme
                tvScanFingerprints?.setTextColor(ContextCompat.getColor(this, it.titleTextColor))
                tvScanMessage?.setTextColor(ContextCompat.getColor(this, it.contentTextColor))

                // Button Background Theme
                btnStart?.background = ContextCompat.getDrawable(this, it.buttonBackground)
                btnCancel?.background = ContextCompat.getDrawable(this, it.buttonBackground)
            } ?: run {
                handleDefaultTheme()
            }
        } catch (e: Exception) {
            handleDefaultTheme()
        }

    }

    private fun handleDefaultTheme() {
        // Default Start Button Theme
        btnStart?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.infraRed))
        btnStart?.setTextColor(ContextCompat.getColor(this, R.color.white))

        // Default Cancel Button Theme
        btnCancel?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.infraRed))
        btnCancel?.setTextColor(ContextCompat.getColor(this, R.color.white))

        // Message Text Theme
        tvStatus?.setTextColor(ContextCompat.getColor(this, R.color.robinEggBlue))

        // Title & Content Text Theme
        tvScanFingerprints?.setTextColor(ContextCompat.getColor(this, R.color.black))
        tvScanMessage?.setTextColor(ContextCompat.getColor(this, R.color.black))

        // Button Background Theme
        btnStart?.background = ContextCompat.getDrawable(this, R.drawable.bg_round_white)
        btnCancel?.background = ContextCompat.getDrawable(this, R.drawable.bg_round_white)
    }

    private fun saveUserToDB() {
        scanningOptions?.uniqueId?.let {
            getUser(it) { userFound, _ ->
                if (!userFound) {
                    saveUserToDb()
                }
            }
        }
    }

    private fun handleLocationEmpty() {
        if (skipLocation) {
            init()
            return
        }
        if (locationWrapper.isLocationEnabled(this)) {
            locationWrapper.getLocation {}
            val dialog = fetchingLocationDialog(scanningOptions?.themeOptions) {}
            timer = object : CountDownTimer(10000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    Log.d(tag, "$location")
                    if (location != null) {
                        cancel()
                        dialog.dismiss()
                        init()
                    }
                }

                override fun onFinish() {
                    dialog.dismiss()
                    if (location == null)
//                        handleMessage("Unable to fetch current location. Please restart the application") {
                        handleMessage("Unable to fetch current location.") {
//                            finish()
                            init()
                        }
                    else {
                        init()
                    }
                }
            }.start()
        } else {
            init()
            /*handleMessage("Please enable location permissions in your settings. User registration requires location access.") {
                handleLocationEmpty()
            }*/
        }
    }

    private fun init() {
        if (skipFirebaseActions) initialize()
        else
            scanningOptions?.uniqueId?.let { bvnNumber ->
                val dialog = fetchingUserDB(scanningOptions?.themeOptions) {}
                getUser(bvnNumber) { userFound, user ->
                    dialog.dismiss()
                    currentUser = user
                    if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                        val storageListRef =
                            storageRef.child("$storagePath${bvnNumber}/").listAll()
                        runBlocking {
                            if (userFound && user?.fingerPrintSyncedOnCloud == true && storageListRef.await().items.size == 2) {
                                handleMessageAndFinish("The user is already registered with entered Unique number. Please try with new BVN.")
                            } else {
//                            doesFileExistsInLocalStorage(this@ScannerActivity, bvnNumber){}
                                saveUserToDB()
                                initialize()
                            }
                        }
                    } else {
                        if (userFound) {
                            user?.let {
                                if (skipLocation) {
                                    handleInitialization()
                                } else {
                                    // When there are no gps co-ordinates over firebase then show toast and finish
                                    if (user.gpsCoordinates.isNullOrEmpty() || user.gpsCoordinates?.get(
                                            0
                                        ) == null || user.gpsCoordinates?.get(
                                            1
                                        ) == null
                                    ) {
//                                    handleMessageAndFinish("User's co-ordinates not found.")
                                        handleInitialization()
                                    } else if (location == null && !skipLocation) {
//                                    handleLocationEmpty()
                                        handleInitialization()
                                    } else {
                                        // Get user's co-ordinates and create LatLng to check distance
                                        val latLng = LatLng(
                                            user.gpsCoordinates?.get(0) ?: 0.0,
                                            user.gpsCoordinates?.get(1) ?: 0.0
                                        )

                                        // Calculate distance
                                        val distanceInMeter =
                                            SphericalUtil.computeDistanceBetween(location, latLng)

                                        // Check distance
                                        /*  if (distanceInMeter > Constant.TRANSACTION_DISTANCE) {
                                              transactionOutOfArea(scanningOptions?.themeOptions) {
                                                  finish()
                                              }
                                          } else {
                                              handleInitialization()

                                          }*/
                                        // todo remove if want to enable area check for transaction
                                        handleInitialization()
                                    }
                                }
                            }
                        } else {
                            hideFingerprintDownloadDialog()
                            handleMessageAndFinish("User not found.")
                            logDebug("ScannerActivity:: --> User not found...")
                        }
                    }

                }
            } ?: run {
                logDebug("ScannerActivity:: --> Unique Id is null...")
            }

    }

    private fun handleInitialization() {
        if (checkUserHasFilesInLocalStorage()) {
            initialize()
        } else {
            showFingerprintDownloadDialog()
            downloadFilesFromFirebaseStorage { isSuccess ->
                hideFingerprintDownloadDialog()
                if (isSuccess) {
                    initialize()
                } else finish()
            }
        }
    }

    private fun handleMessageAndFinish(msg: String) {
        runOnUiThread {
            AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Ok") { dialog, _ ->
                dialog.dismiss()
                finish()
            }.show()
        }
    }

    private fun handleMessage(msg: String, callback: () -> Unit) {
        runOnUiThread {
            alertDialog?.dismiss()
            alertDialog =
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Ok") { dialog, _ ->
                    dialog.dismiss()
                    callback()
                }.show()
        }
    }


    /**
     * Initializes the fingerprint helper within a coroutine.
     *
     * This function launches a coroutine in the [Dispatchers.IO] context aimed at performing
     * IO-bound operations. Inside the coroutine, it attempts to initialize the fingerprint helper
     * using its `init` method. If an exception occurs during the initialization process, it gets
     * caught and printed to the standard error stream, allowing for easier debugging and issue
     * tracking without crashing the application.
     */
    private fun initialize() {
        if (fingerprintHelper?.isInit() != true) {
            showFingerprintInitializationDialog()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    fingerprintHelper?.init()
                } catch (e: Exception) {
                    e.printStackTrace()
                    NewRelic.recordHandledException(e)
                }
            }
        }

    }

    /**
     * Retrieves the current instance of the dialog or initializes it if it's not already created.
     *
     * This function ensures a single instance of the dialog is maintained. If the dialog has not been
     * previously created, it initializes a new dialog instance using the `readersInitializationDialog()`
     * method. If the dialog already exists, the existing instance is returned. This approach prevents
     * the creation of multiple dialog instances, ensuring that only one dialog is active at any given time.
     *
     * @return The current dialog instance, either newly created or previously existing.
     */
    private fun getDialog(): Dialog? {
        if (dialog == null) {
            dialog = readersInitializationDialog(scanningOptions?.themeOptions)
        }
        return dialog
    }

    private fun getTemplateDownloadDialog(): Dialog? {
        if (templateDownloadDialog == null) {
            templateDownloadDialog = templatesDownloadDialog(scanningOptions?.themeOptions)
        }
        return templateDownloadDialog
    }

    private fun showFingerprintInitializationDialog() {
        runOnUiThread {
            try {
                getDialog()?.show()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun showFingerprintDownloadDialog() {
        runOnUiThread {
            getTemplateDownloadDialog()?.show()
        }
    }

    private fun hideFingerprintDownloadDialog() {
        runOnUiThread {
            getTemplateDownloadDialog()?.dismiss()
        }
    }

    /**
     * Handles click events based on the current reader status.
     * This function manages various reader statuses by executing appropriate actions such as
     * initializing fingerprint reading sessions, canceling current operations, and displaying UI feedback.
     * Depending on the reader status, actions include waiting for a finger read, cancelling taps, re-initializing readers,
     * navigating to success outcomes, and handling error or special scenarios like low finger quality.
     *
     * @receiver Context assumed to be an Activity or similar context that can manage UI operations and intents.
     * The function modifies UI elements, manages visibility of buttons, shows messages, and handles navigation.
     *
     * Assumptions:
     *  - `readerStatus` represents the current status of the fingerprint reader and is accessed globally or within scope.
     *  - `setMessage`, `handleCancelButtonsVisibility`, `setStartButtonMessage`, and other UI-related functions
     *    are available for modifying the UI based on the process flow.
     *  - `fingerprintHelper` is an abstraction for the fingerprint reading hardware's interface,
     *    providing methods to initiate reads, cancel operations, and manage readers.
     *  - `initialize()` function is responsible for setting up or resetting the fingerprint readers.
     *  - `list` is a mutable collection that holds the outcome of successful fingerprint reads.
     *  - Uses Intents and Activity results to communicate successful reads.
     *  - `ScannerConstants.DATA` is a key for passing data through intents.
     *
     * The function is structured to handle different states of a fingerprint reading session,
     * showing a practical approach to async hardware interaction within an app.
     */
    private fun handleClick() {
        when (readerStatus) {
            // Handles the case when the service is bound to the application or a session with the reader is already open.
            ReaderStatus.SERVICE_BOUND, ReaderStatus.SESSION_OPEN -> {
                /* if (scanningOptions?.scanningType == ScanningType.REGISTRATION)
                     setMessage("Please put your both fingers on sensor.") // Prompt the user to scan their fingerprints.
                 else setMessage("Please put your both fingers on sensor for verification.")*/

                clearLists()
                setMessage("Initializing sensor, please wait...")
                sleepModeTrack = 0
                handleCancelButtonsVisibility(isVisible = true) // Make the cancel buttons visible.
                setStartButtonMessage(
                    "",
                    isVisible = false
                ) // Hide the start button by making its message empty and its visibility false.
                fingerprintHelper?.scanAndExtract()
            }

            // Handles cases when the session is closed or initializing the reader failed.
            ReaderStatus.SESSION_CLOSED, ReaderStatus.INIT_FAILED -> {
                // Cancel any ongoing tap operations and reinitialize the finger scanner.

                clearLists()
                fingerprintHelper?.cancelTap() // Cancel the finger scanning operation.
                initialize() // Attempt to initialize or reinitialize the reader.
            }

            ReaderStatus.NONE -> {
                // Handle the case when the reader status is not set or known.
            }

            // Handles the successful read of fingerprints or if the fingers were released from the reader.
            ReaderStatus.FINGERS_READ_SUCCESS, ReaderStatus.FINGERS_RELEASED -> {
                if (list.isEmpty()) { // Check if the data list is unexpectedly empty.
                    // Inform the user no data was found.
                    handleMessage("No data found.") {}
                    return
                }
                val intent = Intent()
                intent.putExtra(ScannerConstants.DATA, list) // Add the read data to the intent.
                intent.putExtra(
                    ScannerConstants.TEMPLATE_DATA,
                    templateList
                ) // Add the read data to the intent.
                setResult(RESULT_OK, intent) // Set the result of the scanning operation as OK.
                finish() // Close the current activity.
            }

            ReaderStatus.FINGERS_READ_FAILED -> {
                // Handle scanner read failures here.
            }

            ReaderStatus.FINGERS_DETECTED -> {
                // Handle the case where fingers are detected but not yet read.
            }

            ReaderStatus.TAP_CANCELLED -> {
                fingerprintHelper?.stop() // Stop the fingerprint helper function when a tap is cancelled.
            }

            ReaderStatus.LOW_FINGERS_QUALITY -> {
                setMessage("Please put your both fingers on sensor.") // Ask the user to retry scanning due to low fingerprint quality.
                handleCancelButtonsVisibility(isVisible = true) // Ensure cancel buttons are visible for a possible cancel action.
                setStartButtonMessage("", isVisible = false) // Hide the start button.
                resetImages() // Reset any fingerprint images or related visuals.
                scanningOptions?.uniqueId?.let {
                    deleteFilesFromFirebaseStorage(this, it) { success ->
                        println("File Delete on Low finger quality ------ $success")
                    }
                }
                fingerprintHelper?.scanAndExtract() // scan and extract again
            }

            ReaderStatus.FINGERS_VERIFICATION_SUCCESS -> {
                setFingerprintScanningResult(result = true)
            }

            ReaderStatus.FINGERS_VERIFICATION_FAILED -> {
                setFingerprintScanningResult(result = false)
            }

            ReaderStatus.LOW_POWER_MODE -> {
                // need to add 6 because sleepModeTrack++ get called 2 times due to 2 fingerprint reader so we need to keep check for 6/2 = 3
                if (sleepModeTrack > 6) {
                    sleepModeTrack = 0
                    fingerprintHelper?.setInit(init = false)
                    resetImages()
                    initialize()
                } else {
                    setMessage("Initializing sensor, please wait...")
                    handleCancelButtonsVisibility(isVisible = true) // Make the cancel buttons gone.
                    setStartButtonMessage(
                        "",
                        isVisible = false
                    ) // Hide the start button by making its message empty and its visibility false.
                    fingerprintHelper?.scanAndExtract()
                }
            }
        }
    }

    private fun clearLists() {
        list.clear() // Clear the data list.
        templateList.clear()// Clear the template list.
        areBothFingerprintScannedSuccessfully.clear()
        fingerprintFiles.clear()
        localFileRefs.clear()
        localFileRefsCache.clear()
        uploadedFileRefs.clear()
        uploadedFileRefsCache.clear()
    }

    override fun onResume() {
        super.onResume()
        fingerprintHelper?.isSessionOpen()
    }

    /**
     * Handles the changes in session status for a fingerprint scanning session, updating the UI
     * and invoking various methods depending on the current state of the reader. This function
     * manages the lifecycle of a fingerprint scan, including initiating the scan, handling success
     * or failure results, and managing the UI feedback through dialog interactions and button state updates.
     *
     * @param readerStatus The current status of the fingerprint reader session, indicating the state change.
     * @param data Optional data that might be provided with certain status changes, such as fingerprint data on a successful read.
     */
    private val onSessionChanges = object : ReaderSessionHelper {
        override fun onSessionChanges(readerStatus: ReaderStatus, data: String?) {
            this@ScannerActivity.readerStatus = readerStatus
            when (readerStatus) {
                ReaderStatus.NONE -> {
                    //Initial value of the readers and readers are not initialized in this phase
//                setMessage(getString(R.string.initializing))
                    /* runOnUiThread {
                         getDialog()?.show()
                     }*/
                }

                ReaderStatus.SERVICE_BOUND -> {
                    //Start scanning process readers are initialized
                    setMessage(getString(R.string.scan))
                    // if skip location is false then check for location else skip location check
                    if (location == null && !skipLocation) {
//                        handleLocationEmpty()
                        runOnUiThread {
                            setStartButtonMessage("Start Scan", true)
                            getDialog()?.dismiss()
                        }
                    } else
                        runOnUiThread {
                            setStartButtonMessage("Start Scan", true)
                            getDialog()?.dismiss()
                        }
                    if (fingerprintHelper?.start() == true) {
//                    fingerprintHelper.scanAndExtract()
                        Log.d(tag, "START OK")
                    } else {
                        Log.d(tag, "START FAILED")
                    }
                }

                ReaderStatus.INIT_FAILED -> {
                    runOnUiThread {
                        getDialog()?.dismiss()
                    }
                    setMessage(getString(R.string.initializing_failed))
                    setStartButtonMessage("Retry", true)
                    handleCancelButtonsVisibility(isVisible = false)
                }

                ReaderStatus.FINGERS_RELEASED -> {


//                fingerprintHelper.identifyFingers()
                }

                ReaderStatus.FINGERS_READ_SUCCESS -> {
                    // save prints to storage and return the response through interface to main activity
                    setStartButtonMessage("Done", true)
                    handleCancelButtonsVisibility(isVisible = false)
                    setMessage(getString(R.string.read_success))
//                data?.setFingerQuality()

//                fingerprintHelper.waitFingersRelease()
//                enableLowPowerMode()
                    /* if (list.size > 8) {
                         setMessage("Please check logcat for biometric verification result. Restart the app to scan again.")
                     } else*/
                    /*
                    val intent = Intent()
                    intent.putExtra(ScannerConstants.DATA, list)
                    setResult(RESULT_OK, intent)
                    finish()*/

                }

                ReaderStatus.FINGERS_READ_FAILED -> {}
                ReaderStatus.SESSION_CLOSED -> {
                    runOnUiThread {
                        getDialog()?.dismiss()
                    }
                    setStartButtonMessage("Retry", true)
                    setMessage(getString(R.string.session_closed_retry))
                }

                ReaderStatus.SESSION_OPEN -> {
                    setMessage(getString(R.string.scan))
                    setStartButtonMessage("Start Scan", true)

                    runOnUiThread {
                        getDialog()?.dismiss()
                    }
                    if (fingerprintHelper?.start() == true) {
//                    fingerprintHelper.scanAndExtract()
                        Log.d(tag, "START OK")
                    } else {
                        Log.d(tag, "START FAILED")
                    }
                }

                ReaderStatus.FINGERS_DETECTED -> {
//                setMessage(getString(R.string.fingers_detected))
                }

                ReaderStatus.TAP_CANCELLED -> {}
                ReaderStatus.LOW_FINGERS_QUALITY -> {}
                ReaderStatus.FINGERS_VERIFICATION_SUCCESS -> {}
                ReaderStatus.FINGERS_VERIFICATION_FAILED -> {}
                ReaderStatus.LOW_POWER_MODE -> {
                    resetImages()
                    sleepModeTrack++
                    val msg =
                        "Device is in sleep mode. Please touch the finger sensor to wake it up."
                    setMessage(msg)
                    runOnUiThread {
                        handleCancelButtonsVisibility(isVisible = false)
                        getDialog()?.dismiss()
                    }
                    setStartButtonMessage("Start Scan", isVisible = true)
                }
            }
        }
    }


    /**
     * Updates the text message displayed on a TextView designated for status updates on the UI thread.
     *
     * This function ensures that any updates to the UI, specifically to a TextView for showing status messages,
     * are performed on the UI thread, adhering to Android's requirement that the UI can only be modified from the UI thread.
     * It's safe to call this function from any thread.
     *
     * Parameters:
     * - message: The String value to be displayed in the status TextView. This is the message the user will see.
     *
     * Note: This implementation assumes the existence of a TextView variable `tvStatus` which might be nullable.
     * Make sure `tvStatus` is initialized properly before calling this function to avoid null pointer exceptions.
     */
    private fun setMessage(message: String) {
        runOnUiThread {
            tvStatus?.text = message
        }
    }

    /**
     * Controls the visibility of a cancel button on the UI thread based on the specified boolean flag.
     *
     * This method is designed to modify the visibility of a button, presumed to be used for cancellation actions, within the app's user interface.
     * It ensures any changes to the button's visibility are made on the UI thread, in compliance with Android's guidelines that UI modifications must occur on this thread.
     * This approach allows for safe invocation of visibility changes from any thread without causing thread-related issues.
     *
     * Parameters:
     * - isVisible: A Boolean value indicating the desired visibility state of the cancel button. If `true`, the button will be made visible; if `false`, the button will be hidden.
     *
     * Note: It is assumed that there is a button variable `btnCancel` which might be nullable within this context.
     * Ensure `btnCancel` is adequately initialized before calling this function to prevent potential null pointer exceptions.
     * The function uses `View.VISIBLE` and `View.GONE` from Android's `View` class to toggle visibility status, so ensure these are properly imported to avoid compile-time errors.
     */

    private fun handleCancelButtonsVisibility(isVisible: Boolean) {
        runOnUiThread {
            btnCancel?.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Updates the text displayed on the 'Start' button and controls its visibility.
     * This method ensures the UI changes are performed on the UI thread.
     *
     * @param msg The text message to be displayed on the 'Start' button.
     * @param isVisible Determines whether the 'Start' button should be visible or not.
     *        If true, the button is made visible; if false, the button is hidden.
     */
    private fun setStartButtonMessage(msg: String, isVisible: Boolean) {
        runOnUiThread {
            btnStart?.text = msg
            btnStart?.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        }
    }

    //Enable low power mode when finger scanning success
    // But enabling this will cause issues while termination of NBDevices and this error cause issue while reinitializing the readers
    //
    private fun enableLowPowerMode() = fingerprintHelper?.enableLowPowerMode()

    override fun onStop() {
        super.onStop()
        fingerprintHelper?.stop()
        if (isFingerprintScanningInProgress) fingerprintHelper?.close()
//        setResult(RESULT_CANCELED)
//        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        alertDialog = null
        enableLowPowerMode()
    }

    /**
     * Checks if the necessary storage and camera permissions have been granted.
     *
     * This function determines if the required permissions for accessing media images or external storage
     * have been granted, depending on the Android OS version the device is running on. For devices running
     * on Android version 33 (Android Tiramisu) and above, it checks if the permission to read media images is
     * granted. For devices running on lower versions, it verifies if both read and write external storage
     * permissions have been granted.
     *
     * @return Boolean value indicating whether the necessary permissions are granted. Returns true if all
     * required permissions for the current OS version are granted, false otherwise.
     */
    private fun checkStorageAndCameraPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAndCameraPermission() {
        requestPermissions.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    /**
     * Manages the result of permissions request using the Activity Result API.
     *
     * This property is initialized with the `registerForActivityResult()` function, specifically using
     * the `RequestMultiplePermissions` contract. It facilitates the asynchronous handling of the permissions
     * request dialog results. Upon receiving the results from the permissions request dialog, it checks if
     * all requested permissions have been granted by the user. If so, it proceeds with executing the
     * `handleClick()` function, which is expected to carry out the operation requiring the permissions. If one
     * or more permissions are denied, it calls `showStoragePermissionRequiredDialog()`, prompting the user
     * with a dialog explaining why the storage permission is necessary for the app functionality.
     */
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestPermissions ->
            val granted = requestPermissions.entries.all {
                it.value
            }

            if (granted) {
                handleClick()
            } else {
                showStoragePermissionRequiredDialog()
            }
        }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val list = ArrayList<Boolean>()
            permissions.forEach { actionMap ->
                if (actionMap.value) list.add(actionMap.value)
            }

            if (list.size == 2) {
                locationWrapper.getLocation {
                    Log.d(tag, "${location?.latitude}, ${location?.longitude}")
                }
            }
        }

    /**
     * Displays a dialog informing the user that storage and camera permissions are essential.
     *
     * This function creates and displays an AlertDialog that explains the necessity of storage and camera
     * permissions for the application's functionality. The dialog features a single "Ok" button which, upon
     * being clicked, directs the user to the application's settings page where they can manually grant the
     * required permissions. This method is typically invoked when the user has denied the necessary permissions
     * and needs to understand the importance of granting them for the app to operate correctly.
     */
    private fun showStoragePermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage and Camera permissions are required to access files and camera.")
            .setPositiveButton("Ok") { _, _ ->
                this.openApplicationDetailsSettings()
            }.show()
    }

    /**
     * Opens the application's details settings page.
     *
     * This function is designed to navigate the user directly to the application's specific settings page
     * within the system settings. It constructs an intent that targets the settings page for the current
     * application, based on its package name. This is particularly useful for directing users to enable or
     * modify permissions that the application requires for its operations. The settings page provides access
     * to various settings specific to the app, such as permissions, notifications, and other configurations.
     */
    private fun Context.openApplicationDetailsSettings() {
        val intent = Intent()
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.setData(uri)
        startActivity(intent)
    }

    /**
     * Extension function for String class to set the quality of fingerprints based on given values.
     *
     * The function parses the calling string expecting it to contain two numeric values separated by a comma.
     *
     * These values represent the quality scores for the left and right fingerprints, respectively.
     *
     * The quality score is then converted to a percentage relative to a maximum score of 5, where a lower score indicates higher quality.
     *
     * The calculated percentages are used to update the UI elements displaying the fingerprint quality for both the left and right fingers.
     *
     * This function should be called within an activity context (indicated by `this@ScannerActivity`) that has text views (`tvLeftQuality` and `tvRightQuality`)
     * for displaying the left and right fingerprint quality percentages, respectively.
     * It also performs a check on the quality of both fingerprints and executes UI thread operations to update the quality display,
     * handling any exceptions that occur during execution.
     */
    private fun String.setFingerQuality() {
        try {
            val qualityList = this.split(",")
            if (qualityList.isEmpty() || qualityList.size < 2) return
            val leftQuality = qualityList[0].toInt()
            val rightQuality = qualityList[1].toInt()

            val leftQualityPercentage = (((5.0 - leftQuality.toDouble()) / 4.0) * 100.0)
            val rightQualityPercentage = (((5.0 - rightQuality.toDouble()) / 4.0) * 100.0)
            this@ScannerActivity.leftQuality = "${leftQualityPercentage.toInt()}%"
            this@ScannerActivity.rightQuality = "${rightQualityPercentage.toInt()}%"
            checkQualityOfFingers(leftQualityPercentage.toInt(), rightQualityPercentage.toInt())
            runOnUiThread {
                tvLeftQuality?.text = this@ScannerActivity.leftQuality
                tvRightQuality?.text = this@ScannerActivity.rightQuality
            }
        } catch (e: Exception) {
            e.printStackTrace()
            NewRelic.recordHandledException(e)
        }
    }

    /**
     * Checks the quality of scanned fingers and updates the system state based on the quality.
     *
     * This function assesses the quality of scanned fingerprints from both the left and right hand,
     * based on the provided quality percentage. If the quality percentage of either fingerprint is
     * below 50%, it performs several actions: it clears a specific list (assumed to be related to
     * fingerprint data or results), updates the reader status to indicate low quality of the fingers
     * scanned, prompts the user with a message to rescan their fingers due to the low quality,
     * hides certain buttons (likely related to proceeding with the process), and shows a button
     * to initiate a rescan. This function is likely part of a larger system dealing with biometric
     * authentication or verification where fingerprint quality is critical for accurate processing.
     *
     * @param leftQualityPercentage The quality percentage of the scanned left finger.
     * @param rightQualityPercentage The quality percentage of the scanned right finger.
     */
    private fun checkQualityOfFingers(leftQualityPercentage: Int, rightQualityPercentage: Int) {
        if (leftQualityPercentage < 50 || rightQualityPercentage < 50) {
            clearLists()

            readerStatus = ReaderStatus.LOW_FINGERS_QUALITY
            setMessage("Scanned fingers quality should be more than 50%. Please scan again")
            handleCancelButtonsVisibility(isVisible = false)
            setStartButtonMessage("Scan again", isVisible = true)
        }
    }

    /**
     * Resets the text and images for the fingerprint scanner indicators.
     *
     * This function clears the textual content of the left and right quality TextViews,
     * and sets both the left and right scanner ImageView components to display a default
     * fingerprint image. It ensures these UI updates are performed on the UI thread, making
     * it safe to call from any thread.
     */
    private fun resetImages() {
        runOnUiThread {
            tvLeftQuality?.text = ""
            tvRightQuality?.text = ""
            ivScannerLeft?.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_android_fingerprint_grey
                )
            )
            ivScannerRight?.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    R.drawable.ic_android_fingerprint_grey
                )
            )
        }
    }

    /**
     * Implementation of OnFileSavedListener interface with custom handling for success and failure scenarios in file saving operations.
     *
     * This listener provides three overridden methods:
     * - onSuccess: Called when a file is successfully saved. It logs the file name and adds the file to a list.
     * - onBitmapSaveSuccess: Similar to onSuccess but specifically for bitmap save operations. It updates UI elements (image views) on the main thread based on the reader number.
     * - onFailure: Called when there's an exception during the file saving process, and logs the error.
     *
     * Note: This implementation assumes the presence of 'list', 'ivScannerLeft', and 'ivScannerRight' which must be defined in the outer scope of this listener.
     * 'list' should be a mutable collection capable of adding File objects.
     * 'ivScannerLeft' and 'ivScannerRight' are image view references that should be nullable to handle UI updates gracefully.
     * Proper error handling and UI thread handling (via runOnUiThread) are demonstrated for robustness.
     */

    private fun onFileSavedListener(list: ArrayList<File>, templateList: ArrayList<File>) =
        object : OnFileSavedListener {
            override fun onSuccess(path: String, readerNo: Int) {
                val file = File(path)
                Log.d(ScannerActivity::class.simpleName, file.name)
                /*if (list.size >= 2)
                    list.clear()*/
                list.add(file)
                Log.d(
                    ScannerActivity::class.simpleName,
                    "onFileSavedListener::onSuccess - List size -> ${list.size}"
                )
            }

            override fun onBitmapSaveSuccess(path: String, readerNo: Int) {
                val file = File(path)
                Log.d(ScannerActivity::class.simpleName, file.name)
                runOnUiThread {
                    if (readerNo == 0) {
                        ivScannerLeft?.setImageURI(Uri.fromFile(file))
                    } else ivScannerRight?.setImageURI(Uri.fromFile(file))
                }
            }

            override fun onFailure(e: Exception) {
                e.printStackTrace()
            }

            override fun onTemplateSaveSuccess(path: String, readerNo: Int) {
                println("onTemplateSaveSuccess ---------------- reader no $readerNo------- $path")
                val file = File(path)
                templateList.add(file)
                if (skipFirebaseActions) return

                localFileRefs.add(path)
                // cross check if files size is 2 or greater then 2
                // if yes then clear list so that files will not get upload more than 2
                if (fingerprintFiles.size >= 2) {
                    println("onTemplateSaveSuccess ---------------- reader no $readerNo------- clearing list")
                    fingerprintFiles.clear()
                }
                if (!fingerprintFiles.containsKey(readerNo)) {
                    fingerprintFiles[readerNo] = file
                    updateUserInDb(
                        scanningOptions?.uniqueId!!,
                        hashMapOf("fingerPrintLocalPath" to localFileRefs)
                    ) {}
                    println("uploadTemplates ---------------- File size ${fingerprintFiles.size}")
                    Log.d(ScannerActivity::class.simpleName, file.name)
                    uploadFileFromLocalToFirebaseStorage(
                        scanningOptions?.uniqueId!!,
                        Uri.fromFile(file)
                    ) {}
                    println("onTemplateSaveSuccess ---------------- reader no $readerNo ------- File size ${fingerprintFiles.size}")
                }

            }
        }

    private val fingerprintListener = object : FingerprintListener {
        override fun showResult(
            image: ByteArray?,
            text: String?,
            bitmap: Bitmap,
            readerNo: Int,
            quality: Int
        ) {
            this@ScannerActivity.showResult(image, text, bitmap, readerNo, quality)
        }

        override fun updateMessage(message: String, readerNo: Int) {
            this@ScannerActivity.updateMessage(message)
        }

        override fun showMessage(message: String?, isErrorMessage: Boolean, readerNo: Int) {
            this@ScannerActivity.showMessage(message, isErrorMessage)
        }

        override fun onScanExtractCompleted(readerNo: Int) {
            this@ScannerActivity.onScanExtractCompleted()
        }

        override fun onReaderStatusChange(
            status: NBDeviceScanStatus?,
            readerNo: Int,
            previewListenerType: PreviewListenerType
        ) {
            status?.handle(previewListenerType, readerNo)
        }

        override fun extractionResult(status: NBBiometricsStatus?, readerNo: Int) {
            println("extractionResult ---------------- reader no - $readerNo ------- ${status?.name}")
            /* if (status == NBBiometricsStatus.BAD_QUALITY) {

             }*/
            when (status) {
                NBBiometricsStatus.NONE -> {}
                NBBiometricsStatus.OK -> {
                    if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                        // Store reader number here
                        areBothFingerprintScannedSuccessfully[readerNo] = true
                        if (checkBothFingersSucceed()) {
//                            uploadTemplates()
                            isFingerprintScanningInProgress = false
                            readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                            setStartButtonMessage("Done", true)
                            handleCancelButtonsVisibility(isVisible = false)
                            setMessage(getString(R.string.read_success))
                            handleMessage("User successfully registered") {}
                        }
                    }
                }

                NBBiometricsStatus.TIMEOUT -> {}
                NBBiometricsStatus.CANCELED -> {}
                NBBiometricsStatus.BAD_QUALITY -> {
                    clearLists()
                    runOnUiThread {
                        alertDialog?.hide()
                    }
                    readerStatus = ReaderStatus.LOW_FINGERS_QUALITY
                    setMessage("Poor scan quality. Please try again.")
                    handleCancelButtonsVisibility(isVisible = false)
                    setStartButtonMessage("Scan again", isVisible = true)
                }

                NBBiometricsStatus.TOO_FEW_MINUTIAE -> {}
                NBBiometricsStatus.MATCH_NOT_FOUND -> {}
                NBBiometricsStatus.LATENT_DETECTED -> {}
                NBBiometricsStatus.NEED_MORE_SAMPLES -> {}
                NBBiometricsStatus.SPOOF_DETECTED -> {}
                null -> {}
            }
        }

        override fun identificationStatus(status: NBBiometricsStatus?, readerNo: Int) {
        }

        override fun identificationResult(result: NBBiometricsIdentifyResult?, readerNo: Int) {
            Log.d(ScannerActivity::class.simpleName, "Called times - ${readerNo}")
            if (result?.status != NBBiometricsStatus.OK) {
                if (result?.status == NBBiometricsStatus.MATCH_NOT_FOUND) {
                    readerStatus = ReaderStatus.FINGERS_VERIFICATION_FAILED
                    runOnUiThread {
                        if (verificationDialog == null)
                            verificationDialog = verificationDialog(
                                scanningOptions?.themeOptions,
                                isSuccess = false
                            ) {
                                setFingerprintScanningResult(false)
                            }
                    }
                    setMessage("No match found.")
                } else {
                    runOnUiThread {
                        if (verificationDialog == null)
                            verificationDialog = verificationDialog(
                                scanningOptions?.themeOptions,
                                isSuccess = false
                            ) {
                                setFingerprintScanningResult(false)
                            }
                    }
                    setMessage("Fingerprint verification :- ${result?.status}")
                }
            } else {
                identificationResult[readerNo] = result
            }
            if (identificationResult.size >= 2) {
                readerStatus = ReaderStatus.FINGERS_VERIFICATION_SUCCESS
                runOnUiThread {
                    if (verificationDialog == null)
                        verificationDialog =
                            verificationDialog(scanningOptions?.themeOptions, true) {
                                setFingerprintScanningResult(result = true)
                            }
                }
                setMessage("Fingerprint verified successfully.")
                saveTransactionToDb()
            }
        }
    }

    private fun setFingerprintScanningResult(result: Boolean) {
        val intent = Intent()
        intent.putExtra(
            ScannerConstants.VERIFICATION_RESULT,
            result
        ) // Add the verification data to the intent.
        setResult(RESULT_OK, intent) // Set the result of the scanning operation as OK.
        finish() // Close the current activity.
    }

    private fun NBDeviceScanStatus.handle(previewListenerType: PreviewListenerType, readerNo: Int) {
        println("PreviewListenerType ------------------- reader no - $readerNo ------- ${previewListenerType.name}")
        println("NBDeviceScanStatus ------------------- reader no - $readerNo ------- ${this.name}")

        when (this) {
            NBDeviceScanStatus.NONE -> {}
            NBDeviceScanStatus.OK -> {}
            NBDeviceScanStatus.CANCELED -> {}
            NBDeviceScanStatus.TIMEOUT -> {}
            NBDeviceScanStatus.NO_FINGER -> {}
            NBDeviceScanStatus.NOT_REMOVED -> {}
            NBDeviceScanStatus.BAD_QUALITY -> {}
            NBDeviceScanStatus.BAD_SIZE -> {}
            NBDeviceScanStatus.SPOOF -> {}
            NBDeviceScanStatus.EMPTY -> {}
            NBDeviceScanStatus.DONE -> {
                // add check for both readers

                if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                    // Store reader number here
                    // this login is added in Extraction result
                    /*areBothFingerprintScannedSuccessfully[readerNo] = true
                    if (checkBothFingersSucceed()) {
                        readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                        setStartButtonMessage("Done", true)
                        handleCancelButtonsVisibility(isVisible = false)
                        setMessage(getString(R.string.read_success))
//                        uploadTemplates()
                        handleMessage("User successfully registered") {}
                    }*/
                } else {
                    if (previewListenerType == PreviewListenerType.EXTRACTION) {
                        readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                        setMessage("Finger read successful. Verifying..")
                    } else {
                        readerStatus = ReaderStatus.FINGERS_VERIFICATION_SUCCESS
                        setStartButtonMessage("Done", true)
                        isFingerprintScanningInProgress = false
                        handleCancelButtonsVisibility(isVisible = false)
                        setMessage(getString(R.string.read_success))
                    }
                }
//                fingerprintHelper.waitFingersRelease()
            }

            NBDeviceScanStatus.LIFT_FINGER -> {
                setMessage("Please lift your fingers")
            }

            NBDeviceScanStatus.WAIT_FOR_SENSOR_INITIALIZATION -> {
                setMessage("Initializing sensor, please wait...")
            }

            NBDeviceScanStatus.PUT_FINGER_ON_SENSOR -> {
                isFingerprintScanningInProgress = true
                setMessage("Place your fingers on the sensor.")
            }

            NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR -> {
                setMessage("Please keep your fingers on the sensor.")
            }

            NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING -> {
                setMessage("Both fingerprints are required.")
            }

            NBDeviceScanStatus.SPOOF_DETECTED -> {}
        }
    }

    private fun uploadTemplates() {
        updateUserInDb(
            scanningOptions?.uniqueId!!,
            hashMapOf("fingerPrintLocalPath" to localFileRefs)
        ) {}
        println("uploadTemplates ---------------- File size ${fingerprintFiles.size}")
        fingerprintFiles.forEach { (_, value) ->
            Log.d(ScannerActivity::class.simpleName, value.name)
            uploadFileFromLocalToFirebaseStorage(
                scanningOptions?.uniqueId!!,
                Uri.fromFile(value)
            ) {}
        }

    }

    private fun checkBothFingersSucceed(): Boolean {
        return if (areBothFingerprintScannedSuccessfully.isEmpty()) false
        else if (areBothFingerprintScannedSuccessfully.keys.size == 2 && areBothFingerprintScannedSuccessfully.values.all { it }) true
        else false
    }

    fun onScanExtractCompleted() {
        runOnUiThread {
            btnStart?.isEnabled = true
        }
    }

    private var scope: CoroutineScope? = null
    private fun showMessage(message: String?, isErrorMessage: Boolean) {
        runOnUiThread {
            if (message.equals("ERROR: Invalid operation", ignoreCase = true)) {
                this@ScannerActivity.readerStatus = ReaderStatus.LOW_POWER_MODE
                onSessionChanges.onSessionChanges(ReaderStatus.LOW_POWER_MODE)
                /* runOnUiThread {
                     handleCancelButtonsVisibility(isVisible = false)
                     getDialog()?.dismiss()
                 }
                 scope?.cancel()
                 scope = fingerprintHelper?.waitFingerDetect {
                     setMessage(getString(R.string.scan))
                     setStartButtonMessage("Start Scan", isVisible = true)
                 }*/
            } else {
                if (isErrorMessage) {
                    message?.let { Log.e("MainActivity", it) }
                } else {
                    message?.let { Log.i("MainActivity", it) }
                }

                val singleMessage = TextView(applicationContext)
                if (isErrorMessage) singleMessage.setTextColor(resources.getColor(R.color.error_message_color))
                singleMessage.append(message)
                messagesHolder?.addView(singleMessage)
                lastMessage = singleMessage
            }
        }

        // Scroll to the end. This must be done with a delay, after the last message is drawn
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                runOnUiThread {
//                    val scrollView = findViewById<ScrollView>(R.id.scrollView1)
                    scrollView?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }, 100)
    }

    private fun updateMessage(message: String) {
        runOnUiThread {
            Log.i(
                "MainActivity",
                String.format("%d: %s", System.currentTimeMillis(), message)
            )
            if (lastMessage != null) {
                lastMessage?.text = message
            }
        }
    }

    fun showResult(image: ByteArray?, text: String?, bitmap: Bitmap, readerNo: Int, quality: Int) {
        runOnUiThread {
            if (image != null) {
                if (readerNo == 0) {
                    ivScannerLeft?.setImageBitmap(bitmap)
                } else ivScannerRight?.setImageBitmap(bitmap)
            } else {
                if (readerNo == 0) {
                    ivScannerLeft?.setImageResource(R.drawable.ic_android_fingerprint_grey)
                } else ivScannerRight?.setImageResource(R.drawable.ic_android_fingerprint_grey)
            }
            text?.let { Log.i("MainActivity", it) }
            text?.let { Log.i("MainActivity", "quality[$readerNo] $quality") }
            if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                val leftQualityPercentage: Double
                val rightQualityPercentage: Double
                if (readerNo == 0) {
                    leftQualityPercentage = (((5.0 - quality.toDouble()) / 4.0) * 100.0)
                    this@ScannerActivity.leftQuality = "${leftQualityPercentage.toInt()}%"

                } else {
                    rightQualityPercentage = (((5.0 - quality.toDouble()) / 4.0) * 100.0)
                    this@ScannerActivity.rightQuality = "${rightQualityPercentage.toInt()}%"
                }
                runOnUiThread {
                    tvLeftQuality?.text = this@ScannerActivity.leftQuality
                    tvRightQuality?.text = this@ScannerActivity.rightQuality
                }
            }
//            checkQualityOfFingers(leftQualityPercentage.toInt(), rightQualityPercentage.toInt())
        }
    }

    private fun saveUserToDb() {
        val data = mutableMapOf<String, Any>()
        scanningOptions?.customObject?.let {
            for (key in it.keys()) {
                data[key] = it.get(key)
            }
        }
        val user = User(
            uniqueId = scanningOptions?.uniqueId ?: "", // this is unique id used to identify user
            getAndroidId(), // Android device ID this is also unique
            userId = scanningOptions?.userId,
            phoneNumber = scanningOptions?.phoneNumber ?: "",
            scanningOptions?.bankProvider,
            scanningOptions?.loginType,
            if (scanningOptions?.scanningType == ScanningType.REGISTRATION) Constant.REGISTRATION else Constant.TRANSACTION,
            fingerprintVerificationStatus = readerStatus == ReaderStatus.FINGERS_VERIFICATION_SUCCESS,
            fingerPrintCount = localFileRefs.size,
            localFileRefs,
            uploadedFileRefs,
            fingerPrintSyncedOnCloud = false,
            Date(), // Current date
            arrayListOf(location?.latitude, location?.longitude),
            data
        )
        scanningOptions?.uniqueId?.let { uniqueId ->
            db.collection(Constant.USERS).document(uniqueId).set(user)
                .addOnSuccessListener {
                    currentUser = user
                    Log.d(ScannerActivity::class.simpleName, "Success")
                }
                .addOnFailureListener {
                    Log.e(ScannerActivity::class.simpleName, "Failed - ${it.message}")
                    logError("ScannerActivity:: --> User create failed ${it.message}")
                    it.printStackTrace()
                    NewRelic.recordHandledException(it)
                }
        }
    }

    private fun updateUserInDb(
        uniqueId: String,
        map: HashMap<String, Any>,
        callback: (Boolean) -> Unit
    ) {
        db.collection(Constant.USERS).document(uniqueId).update(map)
            .addOnSuccessListener {
                callback(true)
                Log.d(ScannerActivity::class.simpleName, "User update success")
            }
            .addOnFailureListener {
                callback(false)
                Log.e(ScannerActivity::class.simpleName, "User update failed ${it.message}")
                logError("ScannerActivity:: --> User update failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    fun updateCustomDataInDb(
        uniqueId: String,
        map: HashMap<String, Any>,
        callback: (Boolean) -> Unit
    ) {
        Firebase.firestore.collection(Constant.USERS).document(uniqueId).update(map)
            .addOnSuccessListener {
                callback(true)
                Log.d(ScannerActivity::class.simpleName, "User update success")
            }
            .addOnFailureListener {
                callback(false)
                Log.e(ScannerActivity::class.simpleName, "User update failed ${it.message}")
                logError("ScannerActivity:: --> User update failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    fun getUser(uniqueId: String, callback: (Boolean, User?) -> Unit) {
        Firebase.firestore.collection(USER_COLLECTION_PATH).document(uniqueId).get()
            .addOnSuccessListener {
                Log.d(ScannerActivity::class.simpleName, "User in db - $it")
                try {
                    val user = it.toObject(User::class.java)
                    callback(user != null, user)
                } catch (e: Exception) {
                    callback(false, null)
                }
            }.addOnFailureListener {
                callback(false, null)
                it.printStackTrace()
                Log.e(ScannerActivity::class.simpleName, "User fetch failed ${it.message}")
                logError("ScannerActivity:: --> User fetch failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    fun queryUserByKeyValue(
        queryMap: HashMap<String, Any>,
        source: com.scanner.utils.constants.Source?,
        callback: (Boolean, QuerySnapshot?) -> Unit
    ) {
        // Create a list of filters dynamically
        val filterList = mutableListOf<Filter>()
        for ((key, value) in queryMap) {
            filterList.add(Filter.equalTo(key, value))
        }
        // Combine all filters using Filter.and()
        val combinedFilter = Filter.and(*filterList.toTypedArray())
        val accessSource =
            if (source == null) Source.DEFAULT else if (source == com.scanner.utils.constants.Source.ONLINE) Source.SERVER else Source.CACHE
        Firebase.firestore.collection(USER_COLLECTION_PATH).where(combinedFilter).get(accessSource)
            .addOnSuccessListener {
                /*if (it?.documents.isNullOrEmpty()) {
                    callback(false, null)
                } else {
                    try {
                        callback(true, java.util.ArrayList(it.documents))
                    } catch (e: Exception) {
                        callback(false, null)
                    }
                }*/
                callback(true, it)
            }.addOnFailureListener {
                callback(false, null)
                it.printStackTrace()
                Log.e(ScannerActivity::class.simpleName, "User fetch failed ${it.message}")
                logError("ScannerActivity:: --> User fetch failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    /*fun queryUserByKeyValue(key: String, value: Any, callback: (Boolean, User?) -> Unit) {
        Firebase.firestore.collection(USER_COLLECTION_PATH).whereEqualTo(key, value).get()
            .addOnSuccessListener {
                if (it.isEmpty || it.documents.isEmpty()) {
                    callback(false, null)
                } else {
                    try {
                        val user = it.documents[0].toObject(User::class.java)
                        callback(user != null, user)
                    } catch (e: Exception) {
                        callback(false, null)
                    }
                }
            }.addOnFailureListener {
                callback(false, null)
                it.printStackTrace()
                Log.e(ScannerActivity::class.simpleName, "User fetch failed ${it.message}")
            }
    }*/

    private fun uploadFileFromLocalToFirebaseStorage(
        bvnNumber: String,
        uri: Uri,
        callback: (Boolean) -> Unit
    ) {
        val fileRef = storageRef.child("$storagePath${bvnNumber}/${uri.lastPathSegment}")
        val uploadTask = fileRef.putFile(uri)

        uploadTask.addOnProgressListener {
            val progress = (100.0 * it.bytesTransferred) / it.totalByteCount
            Log.d(ScannerActivity::class.simpleName, "Upload is $progress% done")
        }.addOnSuccessListener {
            Log.d(ScannerActivity::class.simpleName, "File upload success")
            uploadedFileRefs.add(fileRef.path)
            if (uploadedFileRefs.size == 2) {
                updateUserInDb(
                    bvnNumber,
                    hashMapOf(
                        "fingerPrintSyncedOnCloud" to true,
                        "fingerPrintCloudPath" to uploadedFileRefs,
                        "fingerPrintCount" to localFileRefs.size
                    )
                ) {
                    uploadedFileRefs.clear()
                    fingerprintFiles.clear()
                    callback(it)
                }
            }
            Log.d(
                ScannerActivity::class.simpleName,
                "uploadedFileRefs size -> ${uploadedFileRefs.size}"
            )
        }.addOnFailureListener {
            Log.e(ScannerActivity::class.simpleName, "File upload failed")
            callback(false)
            logError("ScannerActivity:: --> File upload failed ${it.message}")
            NewRelic.recordHandledException(it)
        }
    }

    /**
     * @param bvnNumber - user unique id
     * @param uri - Uri of the uploading file
     * @param callback - takes the response back in boolean
     *
     * this function is should only be used to upload files which are not uploaded to server due to no internet or slow internet
     * this is used to sync files when internet connection exists.
     * */
    private fun uploadCacheUserFileFromLocalToFirebaseStorage(
        bvnNumber: String,
        uri: Uri,
        callback: (Boolean) -> Unit
    ) {
        val fileRef = storageRef.child("$storagePath${bvnNumber}/${uri.lastPathSegment}")
        val uploadTask = fileRef.putFile(uri)

        uploadTask.addOnProgressListener {
            val progress = (100.0 * it.bytesTransferred) / it.totalByteCount
            Log.d(ScannerActivity::class.simpleName, "Upload is $progress% done")
        }.addOnSuccessListener {
            Log.d(ScannerActivity::class.simpleName, "File upload success")
            uploadedFileRefsCache.add(fileRef.path)
            if (uploadedFileRefsCache.size == 2) {
                updateUserInDb(
                    bvnNumber,
                    hashMapOf(
                        "fingerPrintSyncedOnCloud" to true,
                        "fingerPrintCloudPath" to uploadedFileRefsCache,
                        "fingerPrintCount" to localFileRefsCache.size
                    )
                ) {
                    uploadedFileRefsCache.clear()
                    fingerprintFiles.clear()
                    callback(it)
                }
            }
            Log.d(
                ScannerActivity::class.simpleName,
                "uploadedFileRefs size -> ${uploadedFileRefs.size}"
            )
        }.addOnFailureListener {
            Log.e(ScannerActivity::class.simpleName, "File upload failed")
            callback(false)
            logError("ScannerActivity:: --> File upload failed ${it.message}")
            NewRelic.recordHandledException(it)
        }
    }

    /**
     * @param uniqueId - user uniqueId which is used to create folder on firebase storage
     * @param callback - takes the response back in boolean
     *
     * - function used to delete the files from firebase storage for the provided unique Id
     * */
    fun deleteFilesFromFirebaseStorage(
        context: Context,
        uniqueId: String,
        callback: (Boolean) -> Unit
    ) {
        val dirPath = context.filesDir.path + "/${uniqueId}/"
        val files = File(dirPath)
        if (files.isDirectory) {
            if (files.listFiles().isNullOrEmpty()) {
                callback(true)
                return
            }
            files.listFiles()?.forEach {
                val uri = Uri.fromFile(it)
                val fileRef = storageRef.child("$storagePath${uniqueId}/${uri.lastPathSegment}")
                fileRef.delete().addOnSuccessListener {
                    callback(true)
                }.addOnFailureListener {
                    callback(false)
                }
            } ?: run {
                callback(true)
            }
        } else {
            callback(true)
        }
    }

    private fun downloadFilesFromFirebaseStorage(callback: (Boolean) -> Unit) {
        val storageList = ArrayList<StorageReference>()
        val storageListRef =
            storageRef.child("$storagePath${scanningOptions?.uniqueId!!}/").listAll()
        storageListRef.addOnSuccessListener {
            if (it.items.isNotEmpty()) {
                it.items.forEach { reference ->
                    storageList.add(reference)
                }
                storageList.save(callback)
            } else {
                // THis will finish the activity so we don't need to send callback here
                handleMessageAndFinish("No files found over local and server database")
//                callback(false)
            }

            Log.d(
                ScannerActivity::class.simpleName,
                "Files over storage size - ${it.items.size}"
            )
            Log.d(ScannerActivity::class.simpleName, "Files over storage - ${it.items}")
        }.addOnFailureListener {
//            callback(false)
            it.printStackTrace()
            Log.e(ScannerActivity::class.simpleName, "Files over storage - ${it.message}")
            // THis will finish the activity so we don't need to send callback here
            handleMessageAndFinish("Not able to fetch files")
            logError("ScannerActivity:: --> File download from firebase storage failed ${it.message}")
            NewRelic.recordHandledException(it)
        }

    }

    private fun ArrayList<StorageReference>.save(callback: (Boolean) -> Unit) {
        val tempFileList = ArrayList<File>()
        forEachIndexed { index, storageReference ->
            val gsReference = storage.getReferenceFromUrl(
                storageReference.toString(),
            )
            val dirPath = filesDir.path + "/${scanningOptions?.uniqueId!!}/"
            val filePath = dirPath + createFileName() + index + "-ISO-Template.bin"
            val files = File(dirPath)
            files.mkdirs()
            gsReference.getFile(File(filePath)).addOnSuccessListener {
                Log.d(
                    ScannerActivity::class.simpleName,
                    "Files downloaded to local storage - ${files.path}"
                )
                tempFileList.add(File(filePath))
                if (tempFileList.size == this.size) {
                    callback(true)
                }
            }.addOnProgressListener {
                val progress = (100.0 * it.bytesTransferred) / it.totalByteCount
                Log.d(ScannerActivity::class.simpleName, "Download progress $progress% done")
            }.addOnFailureListener {
//                callback(false)
                it.printStackTrace()
                Log.e(ScannerActivity::class.simpleName, "Files over storage - ${it.message}")
                handleMessageAndFinish("Not able to download files")
                logError("ScannerActivity:: --> save :: File download failed ${it.message}")
                NewRelic.recordHandledException(it)

            }
        }
    }


    private fun createFileName(): String? {
        val defaultFilePattern = "yyyy-MM-dd-HH-mm-ss"
        val date = Date(System.currentTimeMillis())
        val format = SimpleDateFormat(defaultFilePattern, Locale.ENGLISH)
        return format.format(date)
    }

    private fun checkUserHasFilesInLocalStorage(): Boolean {
        val dirPath = filesDir.path + "/${scanningOptions?.uniqueId!!}/"
        val files = File(dirPath)
        return (files.isDirectory && !files.listFiles().isNullOrEmpty())
    }

    /*#region upload files which are not uploaded*/

    private fun getUserFromCache(querySnapShot: (QuerySnapshot?, Boolean) -> Unit) {
        // set source cache so that user will be fetched from local firebase cache
        val source = Source.CACHE
        db.collection(USER_COLLECTION_PATH).whereEqualTo(FINGER_PRINT_SYNCED_ON_CLOUD, false)
            .get(source)
            .addOnSuccessListener {
                querySnapShot(it, true)
            }.addOnFailureListener {
                it.printStackTrace()
                Log.e(ScannerApp::class.simpleName, "User fetch failed ${it.message}")
                querySnapShot(null, false)
                logError("ScannerActivity:: --> getUserFromCache :: Local user fetch failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    fun getUserFromCacheByUniqueId(
        uniqueId: String,
        querySnapShot: (QuerySnapshot?, Boolean) -> Unit
    ) {
        // set source cache so that user will be fetched from local firebase cache
        db.collection(USER_COLLECTION_PATH)
            .whereEqualTo(UNIQUE_ID, uniqueId)
            .whereEqualTo(FINGER_PRINT_SYNCED_ON_CLOUD, false)
            .get(Source.CACHE)
            .addOnSuccessListener {
                querySnapShot(it, true)
            }.addOnFailureListener {
                it.printStackTrace()
                Log.e(ScannerApp::class.simpleName, "User fetch failed ${it.message}")
                querySnapShot(null, false)
            }
    }

    fun getUserFromCacheByKeyValue(
        key: String,
        value: Any,
        querySnapShot: (QuerySnapshot?, Boolean) -> Unit
    ) {
        // set source cache so that user will be fetched from local firebase cache
        val source = Source.CACHE
        db.collection(USER_COLLECTION_PATH).whereEqualTo(key, value)
            .get(source)
            .addOnSuccessListener {
                querySnapShot(it, true)
            }.addOnFailureListener {
                it.printStackTrace()
                Log.e(ScannerApp::class.simpleName, "User fetch failed ${it.message}")
                querySnapShot(null, false)
            }
    }

    private fun ArrayList<DocumentSnapshot>.uploadFiles() {
        forEachIndexed { _, userSnapshot ->
            try {
                val user = userSnapshot.toObject(User::class.java)
                Log.d(ScannerApp::class.simpleName, "User in db - $user")
                user?.apply {
                    user.uniqueId?.let {
                        deleteFilesFromFirebaseStorage(this@ScannerActivity, it) {
                            upload(user)
                        }
                    } ?: run {
                        upload(user)
                    }

                    /*if (localFileRefs.isNotEmpty()) {
                        localFileRefs.forEach {
                            uploadFileFromLocalToFirebaseStorage(
                                user.uniqueId!!,
                                Uri.fromFile(File(it))
                            ) {}
                        }
                    } else {
                        val dirPath = filesDir.path + "/${user.uniqueId}/"
                        val files = File(dirPath)
                        if (files.isDirectory && !files.listFiles().isNullOrEmpty()) {
                            files.listFiles()?.forEach {
                                localFileRefs.add(it.absolutePath)
                                uploadFileFromLocalToFirebaseStorage(
                                    user.uniqueId!!,
                                    Uri.fromFile(it)
                                ) {}
                            }
                        }
                    }*/
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }
    }

    /**
     * @param user - user object which needs to be uploaded
     *
     * @see uploadFiles there this function is used to upload files
     * */
    private fun upload(user: User) {
        val dirPath = filesDir.path + "/${user.uniqueId}/"
        val files = File(dirPath)
        if (files.isDirectory && !files.listFiles().isNullOrEmpty()) {
            files.listFiles()?.forEach {
                localFileRefsCache.add(it.absolutePath)
                uploadCacheUserFileFromLocalToFirebaseStorage(
                    user.uniqueId!!,
                    Uri.fromFile(it)
                ) {}
            }
        }
    }

    /**
     * @param context - context of the activity
     * @param user - user object which needs to be uploaded
     * @param callback - callback function which will be called after uploading files
     *
     * this is public function used to upload files when user is created over firebase and files does not exist on firebase storage
     * */
    fun uploadFiles(
        context: Context,
        user: User?,
        callback: (Boolean, String) -> Unit
    ) {
        user?.apply {
            val storageListRef =
                storageRef.child("$storagePath${user.uniqueId!!}/").listAll()
            runBlocking {
                val items = storageListRef.await().items
                if (items.isEmpty()) {
                    // if list is empty then upload
                    user.uniqueId?.let { uniqueId ->
                        uploadFiles(context, uniqueId, callback)
                    } ?: run {
                        callback(false, "User not found.")
                    }
                } else if (items.size <= 1 || items.size > 2) {
                    // if file size is smaller then or equal to 1 or items size is greater then 2 then delete the folder files and upload again
                    // because we are only supporting 2 fingerprint scanning right now
                    user.uniqueId?.let { uniqueId ->
                        deleteFilesFromFirebaseStorage(context, uniqueId) {
                            uploadFiles(context, uniqueId, callback)
                        }
                    } ?: run {
                        callback(false, "User not found.")
                    }

                } else {
                    user.uniqueId?.let {
                        updateUserInDb(
                            it,
                            hashMapOf(
                                "fingerPrintSyncedOnCloud" to true
                            )
                        ) { isSuccess ->
                            callback(isSuccess, "Files already uploaded.")
                        }

                    } ?: run {
                        callback(false, "Files already uploaded.")
                    }

                }
            }

        } ?: run {
            callback(false, "User not found.")
        }
    }

    /**
     * @param context - context of the activity
     * @param uniqueId - unique id of the user
     * @param callback - callback function which will be called after uploading files
     *
     * this is common function extracted from uploadFiles() function
     * */
    private fun uploadFiles(
        context: Context,
        uniqueId: String,
        callback: (Boolean, String) -> Unit
    ) {
        val dirPath = context.filesDir.path + "/${uniqueId}/"
        val files = File(dirPath)
        if (files.isDirectory && !files.listFiles().isNullOrEmpty()) {
            localFileRefs.clear()
            files.listFiles()?.forEach {
                localFileRefs.add(it.absolutePath)
                uploadFileFromLocalToFirebaseStorage(
                    uniqueId,
                    Uri.fromFile(it)
                ) { isSuccess ->
                    localFileRefs.clear()
                    callback(
                        isSuccess,
                        if (isSuccess) "Files uploaded successfully" else "Files not uploaded."
                    )
                }
            } ?: run {
                callback(false, "Files on local storage not found.")
            }
        } else callback(false, "Files on local storage not found.")
    }

    private fun saveTransactionToDb() {
        if (skipFirebaseActions) return
        val data = mutableMapOf<String, Any>()
        scanningOptions?.customObject?.let {
            for (key in it.keys()) {
                data[key] = it.get(key)
            }
        }
        val transaction = Transaction(
            scanningOptions?.amount,
            Date(),
            scanningOptions?.uniqueId ?: "",
            arrayListOf(location?.latitude, location?.longitude),
            data
        )
        db.collection(TRANSACTION_COLLECTION_PATH)/*.document(scanningOptions?.bvnNumber!!).collection("${Date()}")*/
            .document().set(transaction)
            .addOnSuccessListener {
                Log.d(ScannerActivity::class.simpleName, "Transaction save Success")
            }
            .addOnFailureListener {
                Log.e(ScannerActivity::class.simpleName, "Transaction save Failed - ${it.message}")
                it.printStackTrace()
                logError("ScannerActivity:: --> saveTransactionToDb :: Save transaction failed ${it.message}")
                NewRelic.recordHandledException(it)
            }
    }

    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    /**
     * @param context - context of the activity.
     * @param uniqueId - unique id of the user.
     * @param callback - takes the response back in boolean.
     *
     * - If the directory has less than or equal to 1 file or more than 2 files,
     * it attempts to delete the directory and its contents.
     * Then, it informs the caller (via the callback) about the directory's state.
     *
     * - If the directory has exactly 2 files, it informs the caller that the directory exists, is not empty, and has 2 files.
     * */
    fun doesFileExistsInLocalStorage(
        context: Context,
        uniqueId: String,
        callback: (Boolean) -> Unit
    ) {
        val dirPath = context.filesDir.path + "/${uniqueId}/"
        val files = File(dirPath)
        if (files.listFiles().isNullOrEmpty()) {
            println("doesFileExistsInLocalStorage ----- files not found locally.")
            callback(false)
            return
        }
        if ((files.listFiles()?.size ?: 0) <= 1 || (files.listFiles()?.size ?: 0) > 2) {
            println("doesFileExistsInLocalStorage ----- deleting files")
            deleteFilesFromFirebaseStorage(context, uniqueId) {
                deleteFolder(files)
                callback(files.isDirectory && !files.listFiles().isNullOrEmpty())
            }
        } else {
            val doesExists = files.isDirectory && !files.listFiles()
                .isNullOrEmpty() && files.listFiles()?.size == 2
            println("doesFileExistsInLocalStorage ----- $doesExists")
            callback(doesExists)
        }
    }

    /**
     * - It tries to list all files within a specific directory in Firebase Cloud Storage.
     * - If successful, it calls a callback function with true (files found) and the number of files.
     * - If there's an error during the listing operation, it calls the callback function with false (no files found) and 0.
     * */
    fun doesFileExistsOnFirebaseStorage(uniqueId: String, callback: (Boolean, Int) -> Unit) {
        val storageListRef =
            storageRef.child("$storagePath${uniqueId}/").listAll()

        storageListRef.addOnSuccessListener {
            callback(it.items.isNotEmpty(), it.items.size)
        }.addOnFailureListener {
            logError("ScannerActivity:: --> doesFileExistsOnFirebaseStorage :: File exist failed ${it.message}")
            NewRelic.recordHandledException(it)
            callback(false, 0)
        }
    }

    /**
     * - Checking if the folder exists.
     * - Listing all files and subfolders inside it.
     * - Recursively deleting subfolders and their contents.
     * - Deleting individual files within the folder.
     * - Finally, deleting the empty folder itself.
     * */
    private fun deleteFolder(folder: File) {
        if (folder.exists()) {
            val files = folder.listFiles()
            if (files != null) { // If the folder contains files
                for (file in files) {
                    if (file.isDirectory) {
                        deleteFolder(file) // Recursively delete subfolders
                    } else {
                        file.delete() // Delete files
                    }
                }
            }
            folder.delete() // Delete the empty folder
        }
    }

    /*#endregion*/
}


