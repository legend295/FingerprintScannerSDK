package com.scanner.activity

import android.Manifest
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.github.legend295.fingerprintscanner.R
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.ktx.storage
import com.google.gson.Gson
import com.nextbiometrics.biometrics.NBBiometricsIdentifyResult
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.scanner.app.ScannerApp
import com.scanner.model.Transaction
import com.scanner.model.User
import com.scanner.utils.BuilderOptions
import com.scanner.utils.ReaderStatus
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.Constant.FINGER_PRINT_READ_INFO
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.enums.PreviewListenerType
import com.scanner.utils.enums.ScanningType
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private var messagesHolder: LinearLayout? = null
    private var scrollView: ScrollView? = null

    private var leftQuality: String? = null
    private var rightQuality: String? = null
    private var scanningOptions: BuilderOptions? = null

    private var list: ArrayList<File> =
        ArrayList() // this will store the response of the saved files
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
    private val localFileRefs = ArrayList<String>()

    //    var textResults: TextView? = null
    private var doWeNeedToReinitialize = true
    private var verificationDialog: Dialog? = null

    private val identificationResult = HashMap<Int, NBBiometricsIdentifyResult?>()
    private val locationWrapper: LocationWrapper = LocationWrapper(this)

    companion object {
        var location: LatLng? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        listOfTemplate.clear()
        list = ArrayList()
        identificationResult.clear()
        verificationDialog = null
        if (checkPermissions()) {
            locationWrapper.getLocation {
                Log.d(tag, "${location?.latitude}, ${location?.longitude}")
            }
        } else {
            requestPermissionLauncher.launch(arrayOf(ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION))
        }
        getUser() //65112583554

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

        fingerprintHelper?.setSessionHelper(sessionHelper = onSessionChanges)
        fingerprintHelper?.setFingerprintListener(fingerprintListener = fingerprintListener)
        fingerprintHelper?.setListOfTemplate(listOfTemplate = listOfTemplate)
        fingerprintHelper?.setOnFileSaveListener(listener = onFileSavedListener(list))


        val bundle = intent.extras
        val options = bundle?.getString(Constant.SCANNING_OPTIONS)
        scanningOptions = Gson().fromJson(options, BuilderOptions::class.java)
        scanningOptions?.bvnNumber?.let { fingerprintHelper?.setBvnNumber(it) }
        scanningOptions?.scanningType?.let { fingerprintHelper?.setScanningType(it) }
        scanningOptions?.bvnNumber?.let {
            getUser(it) { userFound ->
                if (!userFound) {
                    saveUserToDb()
                }
            }
        }

        // Initialize Fingerprint readers on background thread on main thread UI will stuck
        init()

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

    private fun init() {
        if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
            initialize()
        } else {
            if (checkUserHasFilesInLocalStorage()) {
                initialize()
            } else {
                showFingerprintDownloadDialog()
                scanningOptions?.bvnNumber?.let {
                    getUser(it) { userFound ->
                        if (userFound) {
                            downloadFilesFromFirebaseStorage { isSuccess ->
                                hideFingerprintDownloadDialog()
                                if (isSuccess) {
                                    initialize()
                                } else finish()
                            }
                        } else {
                            hideFingerprintDownloadDialog()
                            Toast.makeText(this, "User not found.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
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
            dialog = readersInitializationDialog()
        }
        return dialog
    }

    private fun getTemplateDownloadDialog(): Dialog? {
        if (templateDownloadDialog == null) {
            templateDownloadDialog = templatesDownloadDialog()
        }
        return templateDownloadDialog
    }

    private fun showFingerprintInitializationDialog() {
        runOnUiThread {
            getDialog()?.show()
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
                setMessage("Initializing sensor, please wait...")
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

                list.clear() // Clear the data list.
                fingerprintHelper?.cancelTap() // Cancel the finger scanning operation.
                initialize() // Attempt to initialize or reinitialize the reader.
            }

            ReaderStatus.NONE -> {
                // Handle the case when the reader status is not set or known.
            }

            // Handles the successful read of fingerprints or if the fingers were released from the reader.
            ReaderStatus.FINGERS_READ_SUCCESS, ReaderStatus.FINGERS_RELEASED -> {
                if (list.isEmpty()) { // Check if the data list is unexpectedly empty.
                    runOnUiThread {
                        Toast.makeText(this, "No data found.", Toast.LENGTH_SHORT)
                            .show() // Inform the user no data was found.
                    }
                    return
                }
                val intent = Intent()
                intent.putExtra(ScannerConstants.DATA, list) // Add the read data to the intent.
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
                fingerprintHelper?.scanAndExtract() // scan and extract again
            }

            ReaderStatus.FINGERS_VERIFICATION_SUCCESS -> {
                setFingerprintScanningResult(result = true)
            }

            ReaderStatus.FINGERS_VERIFICATION_FAILED -> {
                setFingerprintScanningResult(result = false)
            }
        }
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
                    runOnUiThread {
                        setStartButtonMessage("Scan", true)
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
                    setStartButtonMessage("Retry", true)
                    setMessage(getString(R.string.session_closed_retry))
                }

                ReaderStatus.SESSION_OPEN -> {
                    setMessage(getString(R.string.scan))
                    setStartButtonMessage("Scan", true)

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
            }
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

    private fun onFileSavedListener(list: ArrayList<File>) = object : OnFileSavedListener {
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
            val file = File(path)
            localFileRefs.add(path)
            updateUserInDb(
                scanningOptions?.bvnNumber!!,
                hashMapOf("fingerPrintLocalPath" to localFileRefs)
            )
            Log.d(ScannerActivity::class.simpleName, file.name)
            uploadFileFromLocalToFirebaseStorage(scanningOptions?.bvnNumber!!, Uri.fromFile(file))
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
//        setResult(RESULT_CANCELED)
//        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
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
            list.clear()
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
            status?.handle(previewListenerType)
        }

        override fun extractionResult(status: NBBiometricsStatus?, readerNo: Int) {
            if (status == NBBiometricsStatus.BAD_QUALITY) {
                list.clear()
                readerStatus = ReaderStatus.LOW_FINGERS_QUALITY
                setMessage("Poor scan quality. Please try again.")
                handleCancelButtonsVisibility(isVisible = false)
                setStartButtonMessage("Scan again", isVisible = true)
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
                            verificationDialog = verificationDialog(isSuccess = false) {
                                setFingerprintScanningResult(false)
                            }
                    }
                    setMessage("No match found.")
                } else {
                    runOnUiThread {
                        if (verificationDialog == null)
                            verificationDialog = verificationDialog(isSuccess = false) {
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
                        verificationDialog = verificationDialog(true) {
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

    private fun NBDeviceScanStatus.handle(previewListenerType: PreviewListenerType) {
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
                if (scanningOptions?.scanningType == ScanningType.REGISTRATION) {
                    readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                    setStartButtonMessage("Done", true)
                    handleCancelButtonsVisibility(isVisible = false)
                    setMessage(getString(R.string.read_success))
                } else {
                    if (previewListenerType == PreviewListenerType.EXTRACTION) {
                        readerStatus = ReaderStatus.FINGERS_READ_SUCCESS
                        setMessage("Finger read successful. Verifying..")
                    } else {
                        readerStatus = ReaderStatus.FINGERS_VERIFICATION_SUCCESS
                        setStartButtonMessage("Done", true)
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
                setMessage("Place your fingers on the sensor.")
            }

            NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR -> {
                setMessage("Please keep your fingers on the sensor")
            }

            NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING -> {
                setMessage("Please wait, processing data...")
            }

            NBDeviceScanStatus.SPOOF_DETECTED -> {}
        }
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
                val msg = "Device is in sleep mode. Please touch the finger sensor to wake it up."
                setMessage(msg)
                this@ScannerActivity.readerStatus = ReaderStatus.SERVICE_BOUND
                runOnUiThread {
                    handleCancelButtonsVisibility(isVisible = false)
                    getDialog()?.dismiss()
                }
//                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                scope?.cancel()
                scope = fingerprintHelper?.waitFingerDetect {
                    setMessage(getString(R.string.scan))
                    setStartButtonMessage("Scan", isVisible = true)
                }
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
        val user = User(
            scanningOptions?.bvnNumber ?: "",
            "alskdfjlaf",
            "nskjds",
            scanningOptions?.phoneNumber ?: "",
            "Coopvest",
            "Agent",
            if (scanningOptions?.scanningType == ScanningType.REGISTRATION) "Registration" else "Transaction",
            fingerprintVerificationStatus = readerStatus == ReaderStatus.FINGERS_VERIFICATION_SUCCESS,
            1,
            localFileRefs,
            uploadedFileRefs,
            fingerPrintSyncedOnCloud = false,
            0,
            Date(),
            arrayListOf(location?.latitude, location?.longitude)
        )
        db.collection("users").document(scanningOptions?.bvnNumber!!).set(user)
            .addOnSuccessListener {
                Log.d(ScannerActivity::class.simpleName, "Success")
            }
            .addOnFailureListener {
                Log.e(ScannerActivity::class.simpleName, "Failed - ${it.message}")
                it.printStackTrace()
            }
    }

    private fun updateUserInDb(bvnNumber: String, map: HashMap<String, Any>) {
        db.collection("users").document(bvnNumber).update(map)
            .addOnSuccessListener {
                Log.d(ScannerActivity::class.simpleName, "User update success")
            }
            .addOnFailureListener {
                Log.e(ScannerActivity::class.simpleName, "User update failed ${it.message}")
            }
    }

    private fun getUser(bvnNumber: String, callback: (Boolean) -> Unit) {
        db.collection("users").document(bvnNumber).get().addOnSuccessListener {
            Log.d(ScannerActivity::class.simpleName, "User in db - $it")
            val user = it.toObject(User::class.java)
            callback(user != null)
            Log.d(ScannerActivity::class.simpleName, user?.amount.toString())
        }.addOnFailureListener {
            callback(false)
            it.printStackTrace()
            Log.e(ScannerActivity::class.simpleName, "User fetch failed ${it.message}")
        }
    }

    private fun uploadFileFromLocalToFirebaseStorage(bvnNumber: String, uri: Uri) {
        val fileRef = storageRef.child("${bvnNumber}/${uri.lastPathSegment}")
        val uploadTask = fileRef.putFile(uri)
        uploadTask.addOnProgressListener {
            val progress = (100.0 * it.bytesTransferred) / it.totalByteCount
            Log.d(ScannerActivity::class.simpleName, "Upload is $progress% done")
        }.addOnSuccessListener {
            Log.d(ScannerActivity::class.simpleName, "File upload success")
            uploadedFileRefs.add(fileRef.path)
            if (uploadedFileRefs.size == 2) {
                uploadedFileRefs.clear()
                updateUserInDb(
                    bvnNumber,
                    hashMapOf(
                        "fingerPrintSyncedOnCloud" to true,
                        "fingerPrintCloudPath" to uploadedFileRefs
                    )
                )
            }
            Log.d(
                ScannerActivity::class.simpleName,
                "uploadedFileRefs size -> ${uploadedFileRefs.size}"
            )
        }.addOnFailureListener {
            Log.e(ScannerActivity::class.simpleName, "File upload failed")
        }
    }

    private fun downloadFilesFromFirebaseStorage(callback: (Boolean) -> Unit) {
        val storageList = ArrayList<StorageReference>()
        val storageListRef = storageRef.child("${scanningOptions?.bvnNumber!!}/").listAll()
        storageListRef.addOnSuccessListener {
            if (it.items.isNotEmpty()) {
                it.items.forEach { reference ->
                    storageList.add(reference)
                }
                storageList.save(callback)
            } else {
                Toast.makeText(
                    this,
                    "No files found over local and server database",
                    Toast.LENGTH_SHORT
                ).show()
                callback(false)
            }

            Log.d(
                ScannerActivity::class.simpleName,
                "Files over storage size - ${it.items.size}"
            )
            Log.d(ScannerActivity::class.simpleName, "Files over storage - ${it.items}")
        }.addOnFailureListener {
            callback(false)
            it.printStackTrace()
            Log.e(ScannerActivity::class.simpleName, "Files over storage - ${it.message}")
            Toast.makeText(this, "Not able to fetch files", Toast.LENGTH_SHORT).show()
        }

    }

    private fun ArrayList<StorageReference>.save(callback: (Boolean) -> Unit) {
        val tempFileList = ArrayList<File>()
        forEachIndexed { index, storageReference ->
            val gsReference = storage.getReferenceFromUrl(
                storageReference.toString(),
            )
            val dirPath = filesDir.path + "/${scanningOptions?.bvnNumber!!}/"
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
                callback(false)
                it.printStackTrace()
                Log.e(ScannerActivity::class.simpleName, "Files over storage - ${it.message}")
                Toast.makeText(
                    this@ScannerActivity,
                    "Not able to download files",
                    Toast.LENGTH_SHORT
                ).show()
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
        val dirPath = filesDir.path + "/${scanningOptions?.bvnNumber!!}/"
        val files = File(dirPath)
        return (files.isDirectory && !files.listFiles().isNullOrEmpty())
    }

    /*#region upload files which are not uploaded*/

    private fun getUser() {
        val source = Source.CACHE
        val userDocuments = ArrayList<DocumentSnapshot>()
        db.collection("users").whereEqualTo("fingerPrintSyncedOnCloud", false).get(source)
            .addOnSuccessListener {
                userDocuments.addAll(it.documents)
                userDocuments.uploadFiles()
            }.addOnFailureListener {
                it.printStackTrace()
                Log.e(ScannerApp::class.simpleName, "User fetch failed ${it.message}")
            }
    }

    private fun ArrayList<DocumentSnapshot>.uploadFiles() {
        forEachIndexed { _, userSnapshot ->
            val user = userSnapshot.toObject(User::class.java)
            Log.d(ScannerApp::class.simpleName, "User in db - $user")
            user?.apply {
                if (localFileRefs.isNotEmpty()) {
                    localFileRefs.forEach {
                        uploadFileFromLocalToFirebaseStorage(bvnNumber!!, Uri.fromFile(File(it)))
                    }
                } else {
                    val dirPath = filesDir.path + "/${bvnNumber}/"
                    val files = File(dirPath)
                    if (files.isDirectory && !files.listFiles().isNullOrEmpty()) {
                        files.listFiles()?.forEach {
                            uploadFileFromLocalToFirebaseStorage(bvnNumber!!, Uri.fromFile(it))
                        }
                    }
                }
            }
        }
    }

    private fun saveTransactionToDb() {
        val transaction = Transaction(
            scanningOptions?.amount,
            Date(),
            scanningOptions?.bvnNumber ?: "",
            arrayListOf(location?.latitude, location?.longitude)
        )
        db.collection("transaction")/*.document(scanningOptions?.bvnNumber!!).collection("${Date()}")*/
            .document().set(transaction)
            .addOnSuccessListener {
                Log.d(ScannerActivity::class.simpleName, "Transaction save Success")
            }
            .addOnFailureListener {
                Log.e(ScannerActivity::class.simpleName, "Transaction save Failed - ${it.message}")
                it.printStackTrace()
            }
    }

    /*#endregion*/
}


