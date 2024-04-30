package com.scanner.activity

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.legend295.fingerprintscanner.R
import com.nextbiometrics.biometrics.NBBiometricsContext
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.nextbiometrics.biometrics.NBBiometricsFingerPosition
import com.nextbiometrics.biometrics.NBBiometricsSecurityLevel
import com.nextbiometrics.biometrics.NBBiometricsStatus
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.nextbiometrics.biometrics.NBBiometricsTemplateType
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewEvent
import com.nextbiometrics.biometrics.event.NBBiometricsScanPreviewListener
import com.nextbiometrics.devices.NBDevice
import com.nextbiometrics.devices.NBDeviceScanFormatInfo
import com.nextbiometrics.devices.NBDeviceScanResult
import com.nextbiometrics.devices.NBDeviceScanStatus
import com.nextbiometrics.devices.NBDeviceSecurityModel
import com.nextbiometrics.devices.NBDeviceType
import com.nextbiometrics.devices.NBDevices
import com.nextbiometrics.system.NextBiometricsException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.AbstractMap
import java.util.Date
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private val DEFAULT_SPI_NAME = "/dev/spidev0.0"

    //Default SYSFS path to access GPIO pins
    private val DEFAULT_SYSFS_PATH = "/sys/class/gpio"
    private val DEFAULT_SPICLK = 8000000
    private var DEFAULT_AWAKE_PIN_NUMBER = 0
    private var DEFAULT_RESET_PIN_NUMBER = 0
    private var DEFAULT_CHIP_SELECT_PIN_NUMBER = 0
    var FLAGS = 0
    private var templateBmp: Bitmap? = null
    private var bufferBytes: ByteArray = byteArrayOf()

    // UI
    var btnScanAndExtract: Button? = null
    var android_low_power_mode_btn: Button? = null
    var main_firmware_upgrade_btn: Button? = null
    var firmware_upgrade_btn: Button? = null
    var upgraded_success_icon: ImageView? = null
    var progress_success_layout: RelativeLayout? = null
    var firmware_current_version: TextView? = null
    var close_icon: ImageView? = null
    var firmware_available_version: TextView? = null
    var imageView: ImageView? = null
    var progress_update_text: TextView? = null
    var progress_bar: ProgressBar? = null

    // Info used by non-UI threads
    var device: NBDevice? = null //device handle

    var isSpi = false
    var scanFormatInfo: NBDeviceScanFormatInfo? = null
    var bgscanResult: NBDeviceScanResult? = null
    var terminate = false // Do we need to call NBDevices.terminate()?

    var timeStart: Long = 0
    var timeStop: Long = 0
    var quality = 0
    var success = false

    // For synchronization with DialogInterface
    var dialogResult = false

    // For displaying messages
    var lastMessage: TextView? = null
    var textResults: TextView? = null
    var previewListener = PreviewListener()

    //Workaround Solution for SPI When IO Command Failed
    var retrySPICause = "Command failed"

    // Enable and Antispoof Support
    private val CONFIGURE_ANTISPOOF = 108
    private val CONFIGURE_ANTISPOOF_THRESHOLD = 109
    private val ENABLE_ANTISPOOF = 1
    private val DISABLE_ANTISPOOF = 0
    private val MIN_ANTISPOOF_THRESHOLD = 0
    private val MAX_ANTISPOOF_THRESHOLD = 1000
    private var spoofScore = MAX_ANTISPOOF_THRESHOLD
    private val DEFAULT_ANTISPOOF_THRESHOLD = "363"
    private var ANTISPOOF_THRESHOLD = DEFAULT_ANTISPOOF_THRESHOLD
    private var isSpoofEnabled = false
    private var isValidSpoofScore = false
    private var isScanAndExtractInProgress = false
    private var isAutoSaveEnabled = false
    private val spoofCause = "Spoof Detected."
    private var spoofThreshold = 0
    var previewStartTime: Long = 0
    var previewEndTime: Long = 0
    var imageFormatSpinnerSelected = 0
    private val ENABLE_BACKGROUND_SUBTRACTION = 1
    private val DISABLE_BACKGROUND_SUBTRACTION = 0
    val PICK_NBF_FILE_REQUEST = 3


    //Storage Permission request code
    private val READ_WRITE_PERMISSION_REQUEST_CODE = 1
    private val PERMISSION_CALLBACK_CODE = 2
    var isPermissionGranted = false
    private var imageFormatName: String? = null
    private var firmwareUpgradeInProgress = false // Add this flag

    //    int fw_update_count =0;
    var customDialog: Dialog? = null
    var firmware_available_version_value = ""
    var availableVersionCheck = false
    var restartFlag = false
    var fw_update_result = -1

    // Timer for closing the app when user pressed back button.
    private var pressedTime: Long = 0

    /**
     * USB permission
     */
    private val ACTION_USB_PERMISSION = "com.example.yourapp.USB_PERMISSION"
    private val usbManager: UsbManager? = null
    private val permissionIntent: PendingIntent? = null

    //
    // Preview Listener
    //
    inner class PreviewListener : NBBiometricsScanPreviewListener {
        private var counter = 0
        private var sequence = 0
        var lastImage: ByteArray? = null
            private set
        var timeFDET: Long = 0
            private set
        var timeScanStart: Long = 0
            private set
        var timeScanEnd: Long = 0
            private set
        var timeOK: Long = 0
            private set
        var fdetScore = 0
            private set

        fun reset() {
            showMessage("") // Placeholder for preview
            counter = 0
            sequence++
            lastImage = null
            timeScanStart = 0
            timeScanEnd = timeScanStart
            timeOK = timeScanEnd
            timeFDET = timeOK
            fdetScore = 0
            spoofScore = MAX_ANTISPOOF_THRESHOLD
        }

        fun getSpoofScore(): Int {
            return spoofScore
        }

        override fun preview(event: NBBiometricsScanPreviewEvent) {
            val image = event.image
            spoofScore = event.spoofScoreValue
            isValidSpoofScore = true
            if (spoofScore <= MIN_ANTISPOOF_THRESHOLD || spoofScore > MAX_ANTISPOOF_THRESHOLD) {
                spoofScore = MIN_ANTISPOOF_THRESHOLD
                isValidSpoofScore = false
            }
            if (isValidSpoofScore) {
                updateMessage(
                    String.format(
                        "PREVIEW #%d: Status: %s, Finger detect score: %d, Spoof Score: %d, image %d bytes",
                        ++counter, event.scanStatus.toString(), event.fingerDetectValue, spoofScore,
                        image?.size ?: 0
                    )
                )
            } else {
                updateMessage(
                    String.format(
                        "PREVIEW #%d: Status: %s, Finger detect score: %d, image %d bytes",
                        ++counter,
                        event.scanStatus.toString(),
                        event.fingerDetectValue,
                        image?.size ?: 0
                    )
                )
            }
            if (image != null) lastImage = image
            // Approx. time when finger was detected = last preview before operation that works with finger image
            if (event.scanStatus != NBDeviceScanStatus.BAD_QUALITY && event.scanStatus != NBDeviceScanStatus.BAD_SIZE && event.scanStatus != NBDeviceScanStatus.DONE && event.scanStatus != NBDeviceScanStatus.OK && event.scanStatus != NBDeviceScanStatus.KEEP_FINGER_ON_SENSOR && event.scanStatus != NBDeviceScanStatus.SPOOF && event.scanStatus != NBDeviceScanStatus.SPOOF_DETECTED && event.scanStatus != NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING && event.scanStatus != NBDeviceScanStatus.CANCELED) {
                timeFDET = System.currentTimeMillis()
            }
            if (event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.WAIT_FOR_DATA_PROCESSING || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                // Approx. time when scan was finished = time of the first event OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                if (timeScanEnd == 0L) timeScanEnd = System.currentTimeMillis()
            } else {
                // Last scan start = time of any event just before OK, DONE, CANCELED or WAIT_FOR_DATA_PROCESSING
                timeScanStart = System.currentTimeMillis()
            }
            // Time when scan was completed
            if (event.scanStatus == NBDeviceScanStatus.OK || event.scanStatus == NBDeviceScanStatus.DONE || event.scanStatus == NBDeviceScanStatus.CANCELED || event.scanStatus == NBDeviceScanStatus.SPOOF_DETECTED) {
                timeOK = System.currentTimeMillis()
                fdetScore = event.fingerDetectValue
            }
            if (previewListener.lastImage != null && (device.toString()
                    .contains("65210") || device.toString().contains("65200"))
            ) {
                if (previewStartTime == 0L) {
                    previewStartTime = System.currentTimeMillis()
                }
                previewEndTime = System.currentTimeMillis()
                showResultOnUiThread(
                    previewListener.lastImage,
                    String.format(
                        "Preview scan time = %d msec,\n Finger detect score = %d",
                        previewEndTime - previewStartTime,
                        event.fingerDetectValue
                    )
                )
                previewStartTime = System.currentTimeMillis()
            }
        }
    }
    ;

    /**
     * get device plugged unplugged status
     * @param savedInstanceState
     */
    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                //When run time disconnected scenario we can call this function for allow the device
                // Start a background thread to perform the operation
                val backgroundThread = Thread { // Perform any necessary operations here
                    askToAllowTheDevice()
                }
                backgroundThread.start()
            }
        }
    }

    //
    // Overriden methods
    //
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textResults = findViewById(R.id.textResults)
        imageView = findViewById(R.id.imageView)
        btnScanAndExtract = findViewById(R.id.scanAndExtract)
        android_low_power_mode_btn = findViewById(R.id.android_low_power_mode_btn)
        main_firmware_upgrade_btn = findViewById(R.id.firmware_upgrade_btn)
        //Use the below implementation while using SYSFS interface
        val PIN_OFFSET = 902
        DEFAULT_AWAKE_PIN_NUMBER = PIN_OFFSET + 69
        DEFAULT_RESET_PIN_NUMBER = PIN_OFFSET + 12
        DEFAULT_CHIP_SELECT_PIN_NUMBER = PIN_OFFSET + 18
        FLAGS = NBDevice.DEVICE_CONNECT_TO_SPI_SKIP_GPIO_INIT_FLAG

        /* Use the below implementation while using /dev/gpiochip interface,
           pass the chip number and gpio number to makePin methode */
        /*DEFAULT_AWAKE_PIN_NUMBER = makePin(0, 69);
        DEFAULT_RESET_PIN_NUMBER = makePin(0, 12);
        DEFAULT_CHIP_SELECT_PIN_NUMBER = makePin(0, 18);
        FLAGS = 0;*/


        //When run time disconnected scenario we can call this function for allow the device
        askToAllowTheDevice()
        btnScanAndExtract?.setOnClickListener {
            val processingThread =
                Thread { // Increase thread priority -- especially SPI fingerprint sensors require this, otherwise the image readout is slow
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    scanAndExtract()
                }
            btnScanAndExtract!!.isEnabled =
                false // Prevent further clicks until the operation is completed
            android_low_power_mode_btn!!.isEnabled =
                false // Prevent further clicks until the operation is completed
            main_firmware_upgrade_btn!!.isEnabled =
                false // Prevent further clicks until the operation is completed
            clearMessages()
//            imageView!!.setImageResource(R.drawable.app_icon)
            textResults!!.text = ""
            processingThread.start()
        }
       /* android_low_power_mode_btn?.setOnClickListener {
            try {
                main_firmware_upgrade_btn!!.isEnabled = false
                android_low_power_mode_btn!!.isEnabled = false
                btnScanAndExtract!!.isEnabled = false
                androidLowPowerMode()
            } finally {
                main_firmware_upgrade_btn!!.isEnabled = true
                android_low_power_mode_btn!!.isEnabled = true
                btnScanAndExtract!!.isEnabled = true
            }
        }*/
       /* main_firmware_upgrade_btn?.setOnClickListener {
            try {
                main_firmware_upgrade_btn!!.isEnabled = false
                android_low_power_mode_btn!!.isEnabled = false
                btnScanAndExtract!!.isEnabled = false
                showFirmwareUpgradeDialog()
            } catch (ex: Throwable) {
                showMessage("ERROR: " + ex.message, true)
                ex.printStackTrace()
            } finally {
                main_firmware_upgrade_btn!!.isEnabled = true
                android_low_power_mode_btn!!.isEnabled = true
                btnScanAndExtract!!.isEnabled = true
            }
        }*/
        val initializationThread =
            Thread { // Increase thread priority -- especially SPI fingerprint sensors require this, otherwise the image readout is slow
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                //Request permission for app storage
                if (!checkPermission()) {
                    requestPermission()
                }
                initializeDevice()
            }
        initializationThread.start()

        //permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        // Register the BroadcastReceiver to listen for USB accessory events
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbReceiver, filter)
    }

    //Android Low Power Mode
    fun androidLowPowerMode() {

        //Check storage permission before scan and extract starts
        while (!checkPermission()) {
            requestPermission()
        }
        try {
            if (device == null || device != null && !device!!.isSessionOpen) {
                //During Reset/unplug-device Session Closed. Need to re-Initilize Device
                if (deviceInit()) showMessage("Device initialized")
            }
            if (device != null) {
                val context = NBBiometricsContext(device)
                val msg = "Device went to low power mode."
                try {
                    context.cancelOperation()
                    device!!.lowPowerMode()
                } catch (ex: NextBiometricsException) {
                    showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
                    //                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
        }
    }

    //Ask USB Device allow pop up
    private fun askToAllowTheDevice() {
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        device?.let { requestUsbPermission(it) }
    }

    //Ask USB Device allow pop up
    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * For Upgrading the firmware through the widget click
     * This is a custom dialog box
     * @return
     */
    private fun showFirmwareUpgradeDialog() {
        // Create the custom dialog
        /*customDialog = Dialog(this)
        customDialog!!.requestWindowFeature(Window.FEATURE_NO_TITLE) // Optional: Remove dialog title
        customDialog.setContentView(R.layout.firmware_upgrade_loading_progress)
        // Set a transparent background for the entire dialog window
        customDialog!!.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        customDialog!!.setCancelable(false)
        upgraded_success_icon = customDialog!!.findViewById<ImageView>(R.id.upgraded_success_icon)
        firmware_upgrade_btn = customDialog!!.findViewById(R.id.firmware_upgrade_btn)
        progress_success_layout = customDialog!!.findViewById<RelativeLayout>(R.id.progress_layout)
        progress_update_text = customDialog!!.findViewById<TextView>(R.id.progress_update_text)
        progress_bar = customDialog!!.findViewById<ProgressBar>(R.id.progress_bar)
        firmware_current_version =
            customDialog!!.findViewById<TextView>(R.id.firmware_current_version)
        close_icon = customDialog!!.findViewById<ImageView>(R.id.close_icon)
        firmware_available_version =
            customDialog!!.findViewById<TextView>(R.id.firmware_available_version)
        upgraded_success_icon.setVisibility(View.GONE)
        progress_bar.setVisibility(View.GONE)
        progress_update_text.setVisibility(View.GONE)
        firmware_current_version.setText(device!!.firmwareVersion.major.toString() + "." + device!!.firmwareVersion.minor + "." + device!!.firmwareVersion.build)
        if (!availableVersionCheck) {
            firmware_available_version_value =
                addDotAfterEachDigit(device!!.firwareAvailableVersion(-0x4a4a0001))
        }
        if (firmware_available_version_value != "0") {
            firmware_available_version.setText(firmware_available_version_value)
        } else {
            firmware_upgrade_btn.setText("Okay")
            Toast.makeText(this, "Available version error", Toast.LENGTH_SHORT).show()
        }
        if (firmware_available_version_value == firmware_current_version.getText().toString()) {
            firmware_upgrade_btn.setText("Okay")
            Toast.makeText(this, "Already upto date", Toast.LENGTH_SHORT).show()
        }
        firmware_upgrade_btn.setOnClickListener(View.OnClickListener { view ->
            firmwareUpgradeProcess(
                view
            )
        })
        close_icon.setOnClickListener(View.OnClickListener {
            if (!availableVersionCheck) {
                availableVersionCheck = true
                NBDevices.terminate()
            }
            if (restartFlag) {
                Handler().postDelayed({ // Restart the activity
                    NBDevices.terminate()
                    customDialog!!.dismiss()
                    reopenApp()
                }, 1000)
            } else {
                customDialog!!.dismiss()
            }
        })

        // Show the custom dialog
        customDialog!!.show()*/
    }

    private fun addDotAfterEachDigit(number: Int): String {
        return if (number > 0) {
            val numberAsString = number.toString()
            val result = StringBuilder()
            for (i in 0 until numberAsString.length) {
                result.append(numberAsString[i])
                if (i < numberAsString.length - 1) {
                    result.append(".")
                }
            }
            result.toString()
        } else {
            "0"
        }
    }

    private fun firmwareUpgradeProcess(view: View) {
       /* if (firmwareUpgradeInProgress) {
            // If firmware upgrade is already in progress, do nothing
            return
        }
        firmware_upgrade_btn!!.isEnabled = false
        firmwareUpgradeInProgress = true
        if (firmware_upgrade_btn!!.text == "Okay") {
            if (!availableVersionCheck) {
                availableVersionCheck = true
                NBDevices.terminate()
            }
            if (restartFlag) {
                Handler().postDelayed({ // Restart the activity
                    NBDevices.terminate()
                    customDialog!!.dismiss()
                    reopenApp()
                }, 1000)
            } else {
                firmwareUpgradeInProgress = false
                customDialog!!.dismiss()
            }
        } else {
            // Start a background thread for firmware upgrade
            val backgroundThread = Thread {
                try {
                    runOnUiThread {
                        upgraded_success_icon!!.visibility = View.GONE
                        progress_bar!!.visibility = View.VISIBLE
                        progress_update_text!!.visibility = View.VISIBLE
                        progress_update_text!!.text =
                            resources.getString(R.string.firmware_upgrade_progress)
                    }

                    // Perform firmware upgrade on the background thread
                    fw_update_result = device!!.firwareUpdateViaMobile(-0x484a0001)

                    // Update UI on the main thread
                    runOnUiThread {
                        if (fw_update_result == 0) {
                            upgraded_success_icon!!.setColorFilter(resources.getColor(R.color.success_color))
                            upgraded_success_icon!!.setImageDrawable(getDrawable(R.drawable.tick_circle))
                            upgraded_success_icon!!.visibility = View.VISIBLE
                            progress_bar!!.visibility = View.GONE
                            firmwareUpgradeInProgress = false
                            progress_update_text!!.text = resources.getString(R.string.completed)
                            firmware_upgrade_btn!!.text = "Okay"
                            restartFlag = true
                        } else {
                            upgraded_success_icon!!.setColorFilter(resources.getColor(R.color.error_color))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                upgraded_success_icon!!.setImageDrawable(getDrawable(R.drawable.app_icon))
                            }
                            upgraded_success_icon!!.visibility = View.VISIBLE
                            progress_bar!!.visibility = View.GONE
                            progress_update_text!!.visibility = View.GONE
                            // Enable the button and reset the flag
                            firmware_upgrade_btn!!.isEnabled = true
                            firmwareUpgradeInProgress = false
                        }
                    }
                } catch (ex: NextBiometricsException) {
                    runOnUiThread { handleFirmwareUpgradeError(ex, view) }
                } catch (ex: Exception) {
                    runOnUiThread { handleFirmwareUpgradeError(ex, view) }
                } finally {
                    // Enable the button and reset the flag when the firmware upgrade is completed or failed
                    runOnUiThread {
                        firmware_upgrade_btn!!.isEnabled = true
                        firmwareUpgradeInProgress = false
                    }
                }
            }
            backgroundThread.start()
        }*/
    }

    private fun handleFirmwareUpgradeError(ex: Exception, view: View) {
       /* upgraded_success_icon!!.setColorFilter(resources.getColor(R.color.error_color))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            upgraded_success_icon!!.setImageDrawable(getDrawable(R.drawable.app_icon))
        }
        upgraded_success_icon!!.visibility = View.VISIBLE
        progress_bar!!.visibility = View.GONE
        progress_update_text!!.visibility = View.GONE
        // Enable the button and reset the flag
        firmware_upgrade_btn!!.isEnabled = true
        firmwareUpgradeInProgress = false*/
    }

    /**
     * Reopen App when Upgrade is finished
     * @param
     * @return
     */
    fun reopenApp() {
        try {
            val intent =
                baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
            intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) // Add this line to disable transition animation
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("TAG", "Exception during restart: " + e.message)
            e.printStackTrace()
        }
    }


   /* // For create Menu-Options
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater = menuInflater
//        inflater.inflate(R.menu.menu, menu)
        return true
    }

    // For created Menu-Options and select items based on click events
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
//        menu.findItem(R.id.configureSettings).setEnabled(true)
//        menu.findItem(R.id.autosaveSettings).setEnabled(true)
        return true
    }
*/
    // On press back button send toast message for closing the App.
    // Based on user confirmation App will be closed.
    override fun onBackPressed() {
       super.onBackPressed()
       if (pressedTime + 2000 > System.currentTimeMillis()) {
            if (device != null) {
                device!!.dispose()
                device = null
            }
            if (terminate) {
                NBDevices.terminate()
            }
            val pid = Process.myPid()
            Process.killProcess(pid)
        } else {
            Toast.makeText(baseContext, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
        pressedTime = System.currentTimeMillis()
    }

   /* override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.configureSettings -> isConfigureSettings(item)
            R.id.autosaveSettings -> isConfigureSettings(item)
        }
        return super.onOptionsItemSelected(item)
    }
*/
    override fun onDestroy() {
        if (device != null) {
            device!!.dispose()
            device = null
        }
        if (terminate) {
            NBDevices.terminate()
        }
        super.onDestroy()
        // Unregister the BroadcastReceiver when the activity is destroyed
        unregisterReceiver(usbReceiver)
    }

    private fun makePin(chipNumber: Int, gpioNumber: Int): Int {
        return 0x01000000 or (chipNumber shl 16) or gpioNumber
    }

    //Check storage permission for device calibration data(65210-S).
    private fun checkPermission(): Boolean {
        /*API-Level-30-Start*/
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val writePermission = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            readPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        /*API-Level-30-Start*/
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.setData(
                    Uri.parse(
                        String.format(
                            "package:%s", *arrayOf<Any>(
                                applicationContext.packageName
                            )
                        )
                    )
                )
                startActivityForResult(intent, PERMISSION_CALLBACK_CODE)
            } catch (e: Exception) {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, PERMISSION_CALLBACK_CODE)
            }
        } else {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ACTION_USB_PERMISSION
                ),
                READ_WRITE_PERMISSION_REQUEST_CODE
            )
        }
    }

    /*API-Level-30-Start*/ //Handling permission callback for Android 11 or above version
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_CALLBACK_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                isPermissionGranted = if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Permission allowed.", Toast.LENGTH_SHORT).show()
                    true
                } else {
                    Toast.makeText(this, "Allow permission for storage access!", Toast.LENGTH_SHORT)
                        .show()
                    false
                }
            }
        } else if (requestCode == PICK_NBF_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            // Handle the selected file URI
            val selectedFileUri = data.data
            val fileData = readSelectedFile(selectedFileUri)
            showFirmwareUpgradeDialog()
            // Perform operations with the selected file URI
        }
    }

    private fun readSelectedFile(fileUri: Uri?): ByteArray? {
        var bytes: ByteArray? = null
        try {
            val inputStream = contentResolver.openInputStream(fileUri!!)
            if (inputStream != null) {
                bytes = getBytesFromInputStreamFromFile(inputStream)
                // Now 'bytes' contains the data from the selected file
                // Do something with the byte array
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return bytes
    }

    @Throws(IOException::class)
    private fun getBytesFromInputStreamFromFile(inputStream: InputStream): ByteArray? {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead)
        }
        return byteArrayOutputStream.toByteArray()
    }
    //
    // UI methods
    //

    //
    // UI methods
    //
    //Allow settings configurations
    fun isConfigureSettings(item: MenuItem): Boolean {
        item.setEnabled(true)
        if (isScanAndExtractInProgress) {
            val toast =
                Toast.makeText(applicationContext, "Operation In-Progress", Toast.LENGTH_SHORT)
            toast.setMargin(50f, 50f)
            toast.show()
            item.setEnabled(false)
            return true
        }
        //Device is null or Session Not opened.
        if (device == null || device != null && !device!!.isSessionOpen) {
            val toast =
                Toast.makeText(applicationContext, "Device is not ready", Toast.LENGTH_SHORT)
            toast.setMargin(50f, 50f)
            toast.show()
            item.setEnabled(false)
            return true
        } else {
            onConfigureMenuItem(item)
        }
        return true
    }

    // Configure MenuItem feature
    fun onConfigureMenuItem(item: MenuItem) {
       /* val context: Context = this
        if (item.itemId == R.id.configureSettings) {
            val li = LayoutInflater.from(context)
            val promptsView: View = li.inflate(R.layout.menu_layout, null)
            val alertDialogBuilder = AlertDialog.Builder(
                context
            )
            alertDialogBuilder.setView(promptsView)
            val checkSpoof = promptsView
                .findViewById<View>(R.id.spoofview) as CheckBox
            val userInput = promptsView
                .findViewById<View>(R.id.editTextDialogUserInput) as EditText
            userInput.setText(ANTISPOOF_THRESHOLD)
            userInput.isEnabled = false
            //Retain Old-Value until New value set.
            if (isSpoofEnabled) {
                checkSpoof.isChecked = true
                userInput.isEnabled = true
            }
            validateCheckBox(checkSpoof, userInput)
            alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(
                    "OK"
                ) { dialog, id -> validateAndSetSpoof(userInput, checkSpoof) }
                .setNegativeButton(
                    "Reset"
                ) { dialog, id ->
                    userInput.isEnabled = true
                    checkSpoof.isChecked = false
                    isSpoofEnabled = false
                    userInput.setText(DEFAULT_ANTISPOOF_THRESHOLD)
                    ANTISPOOF_THRESHOLD = userInput.text.toString()
                    dialog.cancel()
                }
            val alertDialog = alertDialogBuilder.create()
            alertDialog.show()
        } else if (item.itemId == R.id.refreshSettings) {
            NBDevices.terminate()
            val intent = intent
            finish()
            startActivity(intent)
        } else {
            val li = LayoutInflater.from(context)
            val promptsView: View = li.inflate(R.layout.auto_save_layout, null)
            val alertDialogBuilder = AlertDialog.Builder(
                context
            )
            alertDialogBuilder.setView(promptsView)
            val checkautosave = promptsView
                .findViewById<View>(R.id.spoofview) as CheckBox
            val imageFormatSpinner =
                promptsView.findViewById<View>(R.id.imageformatSpinner) as Spinner
            val imageFormatAdapter = ArrayAdapter.createFromResource(
                applicationContext, R.array.image_format_list,
                android.R.layout.simple_spinner_item
            )
            imageFormatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            imageFormatSpinner.adapter = imageFormatAdapter
            imageFormatSpinner.isEnabled = false
            imageFormatSpinner.setSelection(imageFormatSpinnerSelected)
            if (isAutoSaveEnabled) {
                checkautosave.isChecked = true
                imageFormatSpinner.isEnabled = true
            }
            checkautosave.setOnClickListener {
                if (checkautosave.isChecked) {
                    imageFormatSpinner.isEnabled = true
                } else {
                    imageFormatSpinner.isEnabled = false
                }
            }
            alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton(
                    "OK"
                ) { dialog, id ->
                    isAutoSaveEnabled = checkautosave.isChecked
                    imageFormatSpinnerSelected = imageFormatSpinner.selectedItemId.toInt()
                    val item = imageFormatAdapter.getItem(imageFormatSpinner.selectedItemPosition)
                    imageFormatName = item.toString()
                }
                .setNegativeButton(
                    "Reset"
                ) { dialog, id ->
                    isAutoSaveEnabled = false
                    imageFormatSpinnerSelected = 0
                }
            val alertDialog = alertDialogBuilder.create()
            alertDialog.show()
        }*/
    }

    fun validateCheckBox(checkSpoof: CheckBox, userInput: EditText) {
        checkSpoof.setOnClickListener {
            if (checkSpoof.isChecked) {
                userInput.isEnabled = true
            } else {
                userInput.isEnabled = false
            }
        }
    }

    // Validate and Enable/Set spoof threshold
    fun validateAndSetSpoof(userInput: EditText, checkSpoof: CheckBox) {
        if (userInput.text.toString().isEmpty()) {
            userInput.setText(DEFAULT_ANTISPOOF_THRESHOLD)
        }
        ANTISPOOF_THRESHOLD = userInput.text.toString()
        spoofThreshold = ANTISPOOF_THRESHOLD.toInt()
        if (spoofThreshold <= MIN_ANTISPOOF_THRESHOLD) {
            spoofThreshold = MIN_ANTISPOOF_THRESHOLD
            ANTISPOOF_THRESHOLD = Integer.toString(MIN_ANTISPOOF_THRESHOLD)
        }
        if (spoofThreshold >= MAX_ANTISPOOF_THRESHOLD) {
            spoofThreshold = MAX_ANTISPOOF_THRESHOLD
            ANTISPOOF_THRESHOLD = Integer.toString(MAX_ANTISPOOF_THRESHOLD)
        }
        if (checkSpoof.isChecked) {
            checkSpoof.isChecked = true
            isSpoofEnabled = true
            enableSpoof()
        } else {
            checkSpoof.isChecked = false
            isSpoofEnabled = false
            device!!.setParameter(CONFIGURE_ANTISPOOF.toLong(), DISABLE_ANTISPOOF)
        }
    }

    fun enableSpoof() {
        device!!.setParameter(CONFIGURE_ANTISPOOF.toLong(), ENABLE_ANTISPOOF)
        device!!.setParameter(CONFIGURE_ANTISPOOF_THRESHOLD.toLong(), spoofThreshold)
    }

    fun onInitializationSuccess() {
        btnScanAndExtract!!.isEnabled = true
        android_low_power_mode_btn!!.isEnabled = true
        main_firmware_upgrade_btn!!.isEnabled = true
    }

    fun onScanExtractCompleted() {
        if (device == null) {
            //device is null
            showMessage("Device Unplugged or No Active Session.", true)
        }
        btnScanAndExtract!!.isEnabled = true
        android_low_power_mode_btn!!.isEnabled = true
        main_firmware_upgrade_btn!!.isEnabled = true
        isScanAndExtractInProgress = false
    }

    fun showResult(image: ByteArray?, text: String?) {
        if (image != null) {
            imageView!!.setImageBitmap(convertToBitmap(scanFormatInfo, image))
        } else {
            imageView!!.setImageResource(R.drawable.ic_android_fingerprint_infra_red)
        }
        Log.i("MainActivity", text!!)
        textResults!!.text = text
    }

    //
    // Non-UI methods
    //
    private fun initializeDevice() {
        try {
            if (deviceInit()) {
                showMessage("Device initialized")
            } else {
                return
            }
            runOnUiThread { onInitializationSuccess() }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
        } catch (ex: Throwable) {
            showMessage("ERROR: $ex", true)
            ex.printStackTrace()
        }
    }

    private fun isAllowOneTimeBG(dev: NBDevice): Boolean {
        val devType = dev.type
        //For the below device types alone one-time BG capture supported. NBEnhance supported modules.
        return if (devType == NBDeviceType.NB2020U || devType == NBDeviceType.NB2023U || devType == NBDeviceType.NB2033U || devType == NBDeviceType.NB65200U || devType == NBDeviceType.NB65210S) {
            true
        } else {
            false
        }
    }

    private fun deviceInit(): Boolean {
        return try {
            NBDevices.initialize(applicationContext)
            terminate = true
            // wait for callback here
            // or for the sake of simplicity, sleep
            showMessage("Waiting for a USB device")
            for (i in 0..49) {
                Thread.sleep(500)
                if (NBDevices.getDevices().size != 0) break
            }
            val devices = NBDevices.getDevices()
            if (devices.size == 0) {
                Log.i("MainActivity", "No USB device connected")
                showMessage("No USB device found, trying an SPI device")
                try {
                    isSpi = false
                    device = NBDevice.connectToSpi(
                        DEFAULT_SPI_NAME,
                        DEFAULT_SYSFS_PATH,
                        DEFAULT_SPICLK,
                        DEFAULT_AWAKE_PIN_NUMBER,
                        DEFAULT_RESET_PIN_NUMBER,
                        DEFAULT_CHIP_SELECT_PIN_NUMBER,
                        FLAGS
                    )
                    isSpi = true
                } catch (e: Exception) {
                    // showMessage("Problem when opening SPI device: " + e.getMessage());
                }
                if (device == null) {
                    Log.i("MainActivity", "No SPI devices connected")
                    showMessage("No device connected", true)
                    return false
                }
                if (devices.size == 0 && !isSpi) {
                    showMessage("No device connected.", true)
                    NBDevices.terminate()
                    device!!.dispose()
                    device = null
                    return false
                }
            } else {
                device = devices[0]
                isSpi = false
            }
            openSession()
            // If the device requires external calibration data (NB-65210-S), load or create them
            if (device != null && device!!.capabilities.requiresExternalCalibrationData) {
                solveCalibrationData(device!!)
            }
            val scanFormats = device!!.supportedScanFormats
            if (scanFormats.size == 0) throw Exception("No supported formats found!")
            scanFormatInfo = scanFormats[0]
            if (device != null && isAllowOneTimeBG(device!!)) {
                device!!.setParameter(
                    NBDevice.NB_DEVICE_PARAMETER_SUBTRACT_BACKGROUND.toLong(),
                    ENABLE_BACKGROUND_SUBTRACTION
                )
                bgscanResult = device!!.scanBGImage(scanFormatInfo)
            }
            true
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
            false
        } catch (ex: Throwable) {
            showMessage("ERROR: $ex", true)
            ex.printStackTrace()
            false
        }
    }

    private fun openSession() {
        if (device != null && !device!!.isSessionOpen) {
            val cakId = "DefaultCAKKey1\u0000".toByteArray()
            val cak = byteArrayOf(
                0x05.toByte(),
                0x4B.toByte(),
                0x38.toByte(),
                0x3A.toByte(),
                0xCF.toByte(),
                0x5B.toByte(),
                0xB8.toByte(),
                0x01.toByte(),
                0xDC.toByte(),
                0xBB.toByte(),
                0x85.toByte(),
                0xB4.toByte(),
                0x47.toByte(),
                0xFF.toByte(),
                0xF0.toByte(),
                0x79.toByte(),
                0x77.toByte(),
                0x90.toByte(),
                0x90.toByte(),
                0x81.toByte(),
                0x51.toByte(),
                0x42.toByte(),
                0xC1.toByte(),
                0xBF.toByte(),
                0xF6.toByte(),
                0xD1.toByte(),
                0x66.toByte(),
                0x65.toByte(),
                0x0A.toByte(),
                0x66.toByte(),
                0x34.toByte(),
                0x11.toByte()
            )
            val cdkId = "Application Lock\u0000".toByteArray()
            val cdk = byteArrayOf(
                0x6B.toByte(),
                0xC5.toByte(),
                0x51.toByte(),
                0xD1.toByte(),
                0x12.toByte(),
                0xF7.toByte(),
                0xE3.toByte(),
                0x42.toByte(),
                0xBD.toByte(),
                0xDC.toByte(),
                0xFB.toByte(),
                0x5D.toByte(),
                0x79.toByte(),
                0x4E.toByte(),
                0x5A.toByte(),
                0xD6.toByte(),
                0x54.toByte(),
                0xD1.toByte(),
                0xC9.toByte(),
                0x90.toByte(),
                0x28.toByte(),
                0x05.toByte(),
                0xCF.toByte(),
                0x5E.toByte(),
                0x4C.toByte(),
                0x83.toByte(),
                0x63.toByte(),
                0xFB.toByte(),
                0xC2.toByte(),
                0x3C.toByte(),
                0xF6.toByte(),
                0xAB.toByte()
            )
            val defaultAuthKey1Id = "AUTH1\u0000".toByteArray()
            val defaultAuthKey1 = byteArrayOf(
                0xDA.toByte(),
                0x2E.toByte(),
                0x35.toByte(),
                0xB6.toByte(),
                0xCB.toByte(),
                0x96.toByte(),
                0x2B.toByte(),
                0x5F.toByte(),
                0x9F.toByte(),
                0x34.toByte(),
                0x1F.toByte(),
                0xD1.toByte(),
                0x47.toByte(),
                0x41.toByte(),
                0xA0.toByte(),
                0x4D.toByte(),
                0xA4.toByte(),
                0x09.toByte(),
                0xCE.toByte(),
                0xE8.toByte(),
                0x35.toByte(),
                0x48.toByte(),
                0x3C.toByte(),
                0x60.toByte(),
                0xFB.toByte(),
                0x13.toByte(),
                0x91.toByte(),
                0xE0.toByte(),
                0x9E.toByte(),
                0x95.toByte(),
                0xB2.toByte(),
                0x7F.toByte()
            )
            val security = NBDeviceSecurityModel.get(device!!.capabilities.securityModel.toInt())
            if (security == NBDeviceSecurityModel.Model65200CakOnly) {
                device!!.openSession(cakId, cak)
            } else if (security == NBDeviceSecurityModel.Model65200CakCdk) {
                try {
                    device!!.openSession(cdkId, cdk)
                    device!!.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, null)
                    device!!.closeSession()
                } catch (ex: RuntimeException) {
                }
                device!!.openSession(cakId, cak)
                device!!.SetBlobParameter(NBDevice.BLOB_PARAMETER_SET_CDK, cdk)
                device!!.closeSession()
                device!!.openSession(cdkId, cdk)
            } else if (security == NBDeviceSecurityModel.Model65100) {
                device!!.openSession(defaultAuthKey1Id, defaultAuthKey1)
            }
        }
    }

    private fun scanAndExtract() {
        //Hide Menu before scan and extract starts
        isScanAndExtractInProgress = true

        //Check storage permission before scan and extract starts
        while (!checkPermission()) {
            requestPermission()
        }
        var context: NBBiometricsContext? = null
        try {
            if (device == null || device != null && !device!!.isSessionOpen) {
                //During Reset/unplug-device Session Closed. Need to re-Initilize Device
                if (deviceInit()) showMessage("Device initialized")
            }
            if (device != null) {
                // If the device requires external calibration data (NB-65210-S), load or create them
                if (device!!.capabilities.requiresExternalCalibrationData) {
                    solveCalibrationData(device!!)
                }
                context = NBBiometricsContext(device)
                var extractResult: NBBiometricsExtractResult? = null
                showMessage("")
                showMessage("Extracting fingerprint template, please put your finger on sensor!")
                previewListener.reset()
                timeStop = 0
                timeStart = timeStop
                quality = 0
                success = false
                try {
                    timeStart = System.currentTimeMillis()
                    //Hide Menu before scan and extract starts.
                    isScanAndExtractInProgress = true
                    if (isSpoofEnabled) enableSpoof()
                    //Enable Image preview for FAP20
                    //device.setParameter(410,1);
                    extractResult = context.extract(
                        NBBiometricsTemplateType.ISO,
                        NBBiometricsFingerPosition.UNKNOWN,
                        scanFormatInfo,
                        previewListener
                    )
                    timeStop = System.currentTimeMillis()
                } catch (ex: Exception) {
                    if (!isSpi) throw ex

                    // Workaround for a specific customer device problem: If the SPI is idle for certain time, it is put to sleep in a way which breaks the communication
                    // The workaround is to reopen the SPI connection, which resets the communication
                    if (isSpi && ex.message.equals(retrySPICause, ignoreCase = true)) {
                        //Retry Max Times for SPI
                        context.dispose()
                        context = null
                        device!!.dispose()
                        device = null
                        device = NBDevice.connectToSpi(
                            DEFAULT_SPI_NAME,
                            DEFAULT_SYSFS_PATH,
                            DEFAULT_SPICLK,
                            DEFAULT_AWAKE_PIN_NUMBER,
                            DEFAULT_RESET_PIN_NUMBER,
                            DEFAULT_CHIP_SELECT_PIN_NUMBER,
                            FLAGS
                        )
                        // If the device requires external calibration data (NB-65210-S), load or create them
                        if (device != null && device!!.capabilities.requiresExternalCalibrationData) {
                            solveCalibrationData(device!!)
                        }
                        val scanFormats = device?.supportedScanFormats
                        if ((scanFormats?.size ?: 0) == 0) throw Exception("No supported formats found!")
                        scanFormatInfo = scanFormats?.get(0)
                        // And retry the extract operation
                        context = NBBiometricsContext(device)
                        timeStart = System.currentTimeMillis()
                        if (isSpoofEnabled) enableSpoof()
                        //Enable Image preview for FAP20
                        //device.setParameter(410,1);
                        extractResult = context.extract(
                            NBBiometricsTemplateType.ISO,
                            NBBiometricsFingerPosition.UNKNOWN,
                            scanFormatInfo,
                            previewListener
                        )
                        timeStop = System.currentTimeMillis()
                    } else {
                        //If block handled for IO Command failed exception for SPI.
                        throw ex
                    }
                }
                if (extractResult!!.status != NBBiometricsStatus.OK) {
                    throw Exception("Extraction failed, reason: " + extractResult.status)
                }
                //Antispoof check
                val tmpSpoofThreshold = ANTISPOOF_THRESHOLD.toInt()
                if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                    throw Exception("Extraction failed, reason: $spoofCause")
                }
                showMessage("Extracted successfully!")
                val template = extractResult.template
                quality = template.quality
                showResultOnUiThread(
                    previewListener.lastImage, String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract = %d msec, Total time = %d msec\nTemplate quality = %d, Last finger detect score = %d",
                        previewListener.timeScanEnd - previewListener.timeScanStart,
                        previewListener.timeOK - previewListener.timeScanEnd,
                        timeStop - previewListener.timeOK,
                        timeStop - previewListener.timeScanStart,
                        quality,
                        previewListener.fdetScore
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Extraction_Template")
                }
                // Verification
                //
                showMessage("")
                showMessage("Verifying fingerprint, please put your finger on sensor!")
                previewListener.reset()
                context.dispose()
                context = null
                context = NBBiometricsContext(device)
                timeStart = System.currentTimeMillis()
                //Enable Image preview for FAP20
                //device.setParameter(410,1);
                val verifyResult = context.verify(
                    NBBiometricsTemplateType.ISO,
                    NBBiometricsFingerPosition.UNKNOWN,
                    scanFormatInfo,
                    previewListener,
                    template,
                    NBBiometricsSecurityLevel.NORMAL
                )
                timeStop = System.currentTimeMillis()
                if (verifyResult.status != NBBiometricsStatus.OK) {
                    throw Exception("Not verified, reason: " + verifyResult.status)
                }
                if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                    throw Exception("Not verified, reason: $spoofCause")
                }
                showMessage("Verified successfully!")
                showResultOnUiThread(
                    previewListener.lastImage, String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract+Verify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                        previewListener.timeScanEnd - previewListener.timeScanStart,
                        previewListener.timeOK - previewListener.timeScanEnd,
                        timeStop - previewListener.timeOK,
                        timeStop - previewListener.timeScanStart,
                        verifyResult.score,
                        previewListener.fdetScore
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Verification_Template")
                }
                // Identification
                //
                showMessage("")
                val templates: MutableList<AbstractMap.SimpleEntry<Any, NBBiometricsTemplate>> =
                    LinkedList()
                templates.add(AbstractMap.SimpleEntry("TEST", template))
                // add more templates
                showMessage("Identifying fingerprint, please put your finger on sensor!")
                previewListener.reset()
                context.dispose()
                context = null
                context = NBBiometricsContext(device)
                timeStart = System.currentTimeMillis()
                //Enable Image preview for FAP20
                //device.setParameter(410,1);
                val identifyResult = context.identify(
                    NBBiometricsTemplateType.ISO,
                    NBBiometricsFingerPosition.UNKNOWN,
                    scanFormatInfo,
                    previewListener,
                    templates.iterator(),
                    NBBiometricsSecurityLevel.NORMAL
                )
                timeStop = System.currentTimeMillis()
                if (identifyResult.status != NBBiometricsStatus.OK) {
                    throw Exception("Not identified, reason: " + identifyResult.status)
                }
                if (isSpoofEnabled && isValidSpoofScore && previewListener.getSpoofScore() <= tmpSpoofThreshold) {
                    throw Exception("Not identified, reason: $spoofCause")
                }
                showMessage("Identified successfully with fingerprint: " + identifyResult.templateId)
                showResultOnUiThread(
                    previewListener.lastImage, String.format(
                        "Last scan = %d msec, Image process = %d msec, Extract+Identify = %d msec, Total time = %d msec\nMatch score = %d, Last finger detect score = %d",
                        previewListener.timeScanEnd - previewListener.timeScanStart,
                        previewListener.timeOK - previewListener.timeScanEnd,
                        timeStop - previewListener.timeOK,
                        timeStop - previewListener.timeScanStart,
                        verifyResult.score,
                        previewListener.fdetScore
                    )
                )
                if (isAutoSaveEnabled) {
                    saveImageApi(imageFormatName, "Identification_Template")
                }
                // Save template
                val binaryTemplate = context.saveTemplate(template)
                showMessage(
                    String.format(
                        "Extracted template length: %d bytes",
                        binaryTemplate.size
                    )
                )
                val base64Template = Base64.encodeToString(binaryTemplate, 0)
                showMessage("Extracted template: $base64Template")

                // Store template to file
                val dirPath =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .toString() + "/NBCapturedImages/"
                val filePath = dirPath + createFileName() + "-ISO-Template.bin"
                val files = File(dirPath)
                files.mkdirs()
                showMessage("Saving ISO template to $filePath")
                val fos = FileOutputStream(filePath)
                fos.write(binaryTemplate)
                fos.close()
                success = true
            }
        } catch (ex: NextBiometricsException) {
            showMessage("ERROR: NEXT Biometrics SDK error: $ex", true)
            ex.printStackTrace()
        } catch (ex: Throwable) {
            showMessage("ERROR: " + ex.message, true)
            ex.printStackTrace()
        }
        if (context != null) {
            context.dispose()
            context = null
        }
        runOnUiThread { onScanExtractCompleted() }
    }

    @Throws(Exception::class)
    private fun solveCalibrationData(device: NBDevice) {
        val paths = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .toString() + "/NBData/" + device.serialNumber + "_calblob.bin"
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toString() + "/NBData/"
        ).mkdirs()
        val file = File(paths)
        if (!file.exists()) {
            // Ask the user whether he wants to calibrate the device
            runOnUiThread {
                val dialogClickListener =
                    DialogInterface.OnClickListener { dialog, which ->
                        synchronized(this@MainActivity) {
                            dialogResult = which == DialogInterface.BUTTON_POSITIVE
//                            this@MainActivity.notifyAll()
                        }
                    }
                AlertDialog.Builder(this@MainActivity).setMessage(
                    """
                    This device is not calibrated yet. Do you want to calibrate it?
                    
                    If yes, at first perfectly clean the sensor, and only then select the YES button.
                    """.trimIndent()
                ).setPositiveButton("Yes", dialogClickListener)
                    .setNegativeButton("No", dialogClickListener)
                    .show()
            }
            synchronized(this@MainActivity) {
//                this@MainActivity.wait()
                if (!dialogResult) throw Exception("The device is not calibrated")
            }
            showMessage("Creating calibration data: $paths")
            showMessage("This operation may take several minutes.")
            try {
                val data = device.GenerateCalibrationData()
                val fos = FileOutputStream(paths)
                fos.write(data)
                fos.close()
                showMessage("Calibration data created")
            } catch (e: Exception) {
                showMessage(e.message, true)
            }
        }
        if (file.exists()) {
            val size = file.length().toInt()
            val bytes = ByteArray(size)
            try {
                val buf = BufferedInputStream(FileInputStream(file))
                buf.read(bytes, 0, bytes.size)
                buf.close()
            } catch (ex: IOException) {
            }
            device.SetBlobParameter(NBDevice.BLOB_PARAMETER_CALIBRATION_DATA, bytes)
        } else {
            throw Exception("Missing compensation data - $paths")
        }
    }

    @Throws(IOException::class)
    private fun readFile(filePath: String): ByteArray? {
        val file = File(filePath)
        val buffer = ByteArray(file.length().toInt())
        var ios: InputStream? = null
        try {
            ios = FileInputStream(file)
            if (ios.read(buffer) == -1) {
                throw IOException("EOF reached while trying to read the whole file")
            }
        } finally {
            ios?.close()
        }
        return buffer
    }

    @Throws(IOException::class)
    private fun readFileResource(resourcePath: String): ByteArray? {
        val `is` = this.javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: throw IOException("cannot find resource: $resourcePath")
        return getBytesFromInputStream(`is`)
    }

    @Throws(IOException::class)
    private fun getBytesFromInputStream(`is`: InputStream): ByteArray? {
        val os = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var len: Int
        while (`is`.read(buffer).also { len = it } != -1) {
            os.write(buffer, 0, len)
        }
        os.flush()
        return os.toByteArray()
    }

    private fun convertToBitmap(formatInfo: NBDeviceScanFormatInfo?, image: ByteArray): Bitmap? {
        val buf = IntBuffer.allocate(image.size)
        for (pixel in image) {
            val grey = pixel.toInt() and 0x0ff
            buf.put(Color.argb(255, grey, grey, grey))
        }
        bufferBytes = image
        templateBmp = Bitmap.createBitmap(
            buf.array(),
            formatInfo!!.width,
            formatInfo.height,
            Bitmap.Config.ARGB_8888
        )
        return templateBmp
    }

    private fun showResultOnUiThread(image: ByteArray?, text: String) {
        runOnUiThread { showResult(image, text) }
    }

    private fun showMessage(message: String) {
        showMessage(message, false)
    }

    private fun showMessage(message: String?, isErrorMessage: Boolean) {
        runOnUiThread {
            if (message.equals("ERROR: Invalid operation", ignoreCase = true)) {
                val msg = "Device is in low power mode.Touch the sensor to wakeup the device."
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } else {
                if (isErrorMessage) {
                    Log.e("MainActivity", message!!)
                } else {
                    Log.i("MainActivity", message!!)
                }
                val messagesHolder = findViewById<LinearLayout>(R.id.messagesHolder)
                val scrollView = findViewById<ScrollView>(R.id.scrollView1)
                val singleMessage = TextView(applicationContext)
                if (isErrorMessage) singleMessage.setTextColor(resources.getColor(R.color.error_message_color))
                singleMessage.append(message)
                messagesHolder.addView(singleMessage)
                lastMessage = singleMessage
            }
        }

        // Scroll to the end. This must be done with a delay, after the last message is drawn
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    val scrollView = findViewById<ScrollView>(R.id.scrollView1)
                    scrollView.fullScroll(View.FOCUS_DOWN)
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
                lastMessage!!.text = message
            }
        }
    }

    private fun clearMessages() {
        runOnUiThread {
            Log.i("MainActivity", "-------------------------------------------")
            val messagesHolder = findViewById<LinearLayout>(R.id.messagesHolder)
            messagesHolder.removeAllViewsInLayout()
            lastMessage = null
        }
    }

    fun createFileName(): String {
        val DEFAULT_FILE_PATTERN = "yyyy-MM-dd-HH-mm-ss"
        val date = Date(System.currentTimeMillis())
        val format = SimpleDateFormat(DEFAULT_FILE_PATTERN)
        return format.format(date)
    }

    private fun writeImage(imageFormatName: String, filePath: String) {
        var out: FileOutputStream? = null
        try {
            when (imageFormatName) {
                "JPG" -> {
                    out = FileOutputStream(filePath)
                    templateBmp!!.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                }

                "PNG" -> {
                    out = FileOutputStream(filePath)
                    templateBmp!!.compress(Bitmap.CompressFormat.PNG, 90, out)
                    out.flush()
                    out.close()
                }

                "RAW" -> {
                    out = FileOutputStream(filePath)
                    out.write(bufferBytes)
                    out.close()
                }

                else -> {
                    val ScannedBMP = AndroidBmpUtil()
                    try {
                        ScannedBMP.save(templateBmp, filePath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveImageApi(imageFormatName: String?, featureProcess: String) {
        var dirPath: String? = null
        var saveName: String? = null
        val filePath: String
        if (imageFormatName == null) return
        when (imageFormatName) {
            "JPG" -> saveName = createFileName() + "_" + featureProcess + ".jpg"
            "PNG" -> saveName = createFileName() + "_" + featureProcess + ".png"
            "RAW" -> saveName = createFileName() + "_" + featureProcess + ".raw"
            "BMP" -> saveName = createFileName() + "_" + featureProcess + ".bmp"
        }
        try {
            dirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .toString() + "/NBCapturedImages/"
            filePath = dirPath + saveName
            val files = File(dirPath)
            files.mkdirs()
            writeImage(imageFormatName, filePath)
            showMessage("Image save in : $filePath")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    class AndroidBmpUtil {
        @Throws(IOException::class)
        fun save(orgBitmap: Bitmap?, filePath: String?): Boolean {
            if (orgBitmap == null) {
                return false
            }
            if (filePath == null) {
                return false
            }
            val width = orgBitmap.width
            val height = orgBitmap.height

            //image empty data size
            //reason : the amount of bytes per image row must be a multiple of 4 (requirements of bmp format)
            var emptyBytesPerRow: ByteArray? = null
            var hasEmpty = false
            val rowWidthInBytes =
                BYTE_PER_PIXEL * width //source image width * number of bytes to encode one pixel.
            if (rowWidthInBytes % BMP_WIDTH_OF_TIMES > 0) {
                hasEmpty = true
                //the number of empty bytes we need to add on each row
                emptyBytesPerRow =
                    ByteArray(BMP_WIDTH_OF_TIMES - rowWidthInBytes % BMP_WIDTH_OF_TIMES)
                //just fill an array with the empty bytes we need to append at the end of each row
                for (emptyBytesPerRowIndex in emptyBytesPerRow.indices) {
                    emptyBytesPerRow[emptyBytesPerRowIndex] = 0xFF.toByte()
                }
            }

            //an array to receive the pixels from the source image
            val pixels = IntArray(width * height)

            //the number of bytes used in the file to store raw image data (excluding file headers)
            val imageSize =
                (rowWidthInBytes + if (hasEmpty) emptyBytesPerRow!!.size else 0) * height
            //file headers size
            val imageDataOffset = 0x36

            //final size of the file
            val fileSize = imageSize + imageDataOffset

            //Android Bitmap Image Data
            orgBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            //ByteArrayOutputStream baos = new ByteArrayOutputStream(fileSize);
            val buffer = ByteBuffer.allocate(fileSize)
            /**
             * BITMAP FILE HEADER Write Start
             */
            buffer.put(0x42.toByte())
            buffer.put(0x4D.toByte())

            //size
            buffer.put(writeInt(fileSize))

            //reserved
            buffer.put(writeShort(0.toShort()))
            buffer.put(writeShort(0.toShort()))

            //image data start offset
            buffer.put(writeInt(imageDataOffset))
            /** BITMAP FILE HEADER Write End  */

            //*******************************************
            /** BITMAP INFO HEADER Write Start  */
            //size
            buffer.put(writeInt(0x28))

            //width, height
            //if we add 3 empty bytes per row : it means we add a pixel (and the image width is modified.
            buffer.put(writeInt(width + if (hasEmpty) (if (emptyBytesPerRow!!.size == 3) 1 else 0) else 0))
            buffer.put(writeInt(height))

            //planes
            buffer.put(writeShort(1.toShort()))

            //bit count
            buffer.put(writeShort(24.toShort()))

            //bit compression
            buffer.put(writeInt(0))

            //image data size
            buffer.put(writeInt(imageSize))

            //horizontal resolution in pixels per meter
            buffer.put(writeInt(0))

            //vertical resolution in pixels per meter (unreliable)
            buffer.put(writeInt(0))
            buffer.put(writeInt(0))
            buffer.put(writeInt(0))
            /** BITMAP INFO HEADER Write End  */
            var row = height
            var startPosition = (row - 1) * width
            var endPosition = row * width
            while (row > 0) {
                for (pixelsIndex in startPosition until endPosition) {
                    buffer.put((pixels[pixelsIndex] and 0x000000FF).toByte())
                    buffer.put((pixels[pixelsIndex] and 0x0000FF00 shr 8).toByte())
                    buffer.put((pixels[pixelsIndex] and 0x00FF0000 shr 16).toByte())
                }
                if (hasEmpty) {
                    buffer.put(emptyBytesPerRow)
                }
                row--
                endPosition = startPosition
                startPosition = startPosition - width
            }
            try {
                val fos = FileOutputStream(filePath, false)
                fos.write(buffer.array())
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            return true
        }

        //Write integer to little-endian
        @Throws(IOException::class)
        private fun writeInt(value: Int): ByteArray {
            val byteArray = ByteArray(4)
            byteArray[0] = (value and 0x000000FF).toByte()
            byteArray[1] = (value and 0x0000FF00 shr 8).toByte()
            byteArray[2] = (value and 0x00FF0000 shr 16).toByte()
            byteArray[3] = (value and -0x1000000 shr 24).toByte()
            return byteArray
        }

        //Write short to little-endian byte array
        @Throws(IOException::class)
        private fun writeShort(value: Short): ByteArray {
            val byteArray = ByteArray(2)
            byteArray[0] = (value.toInt() and 0x00FF).toByte()
            byteArray[1] = (value.toInt() and 0xFF00 shr 8).toByte()
            return byteArray
        }

        companion object {
            private const val BMP_WIDTH_OF_TIMES = 4
            private const val BYTE_PER_PIXEL = 3
        }
    }

}