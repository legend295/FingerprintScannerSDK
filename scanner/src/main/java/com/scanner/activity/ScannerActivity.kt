package com.scanner.activity

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.scanner.utils.helper.ReaderSessionHelper
import com.scanner.utils.ReaderStatus
import com.scanner.utils.readers.FingerprintHelper
import com.github.legend295.fingerprintscanner.R
import com.nextbiometrics.biometrics.NBBiometricsTemplate
import com.scanner.utils.constants.Constant.FINGER_PRINT_READ_INFO
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.helper.OnFileSavedListener
import com.scanner.utils.readersInitializationDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ScannerActivity : AppCompatActivity(),
    ReaderSessionHelper {

    private var tvStatus: AppCompatTextView? = null
    private var tvLeftQuality: AppCompatTextView? = null
    private var tvRightQuality: AppCompatTextView? = null
    private var btnStart: AppCompatButton? = null
    private var btnCancel: AppCompatButton? = null
    private var ivScannerLeft: AppCompatImageView? = null
    private var ivScannerRight: AppCompatImageView? = null

    private var leftQuality: String? = null
    private var rightQuality: String? = null

    private val list: ArrayList<File> = ArrayList()
    private val listOfTemplate = ArrayList<NBBiometricsTemplate>()
    private val fingerprintHelper by lazy {
        FingerprintHelper(
            this,
            this,
            listOfTemplate,
            onFileSavedListener
        )
    }
    private var readerStatus: ReaderStatus = ReaderStatus.NONE
    private val tag = ScannerActivity::class.java.simpleName
    private val difFingerprintReadInfo = FINGER_PRINT_READ_INFO
    private var dialog: Dialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
//        dialog = readersInitializationDialog()

        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        ivScannerLeft = findViewById(R.id.ivScannerLeft)
        ivScannerRight = findViewById(R.id.ivScannerRight)
        tvLeftQuality = findViewById(R.id.tvLeftQuality)
        tvRightQuality = findViewById(R.id.tvRightQuality)

        // Initialize Fingerprint readers on background thread on main thread UI will stuck
        initialize()

        btnStart?.setOnClickListener {
            if (checkStorageAndCameraPermission()) {
                handleClick()
            } else {
                requestStorageAndCameraPermission()
            }
        }

        btnCancel?.setOnClickListener {
            btnCancel?.isEnabled = false
            fingerprintHelper.cancelTap()
            fingerprintHelper.stop()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun initialize() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fingerprintHelper.init()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getDialog(): Dialog? {
        if (dialog == null) {
            dialog = readersInitializationDialog()
        } else dialog
        return dialog
    }

    private fun handleClick() {
        when (readerStatus) {
            ReaderStatus.SERVICE_BOUND, ReaderStatus.SESSION_OPEN -> {
                setMessage("Please tap your both fingers on readers.")
                handleCancelButtonsVisibility(isVisible = true)
                setStartButtonMessage("", isVisible = false)
                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)
            }

            ReaderStatus.SESSION_CLOSED, ReaderStatus.INIT_FAILED -> {
                //first call cancel tap function of readers and then re-initialize the readers
//                setMessage("Retrying...")
                list.clear()
                fingerprintHelper.cancelTap()
                initialize()
                runOnUiThread {
                    getDialog()?.show()
                }


            }

            ReaderStatus.NONE -> {}

            ReaderStatus.FINGERS_READ_SUCCESS, ReaderStatus.FINGERS_RELEASED -> {
                if (list.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "No data found.", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val intent = Intent()
                intent.putExtra(ScannerConstants.DATA, list)
                setResult(RESULT_OK, intent)
                finish()
                /*if (list.size > 8) {
                    return
                }
                setMessage("Reading fingers again.")
                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)*/
            }

            ReaderStatus.FINGERS_READ_FAILED -> {}
            ReaderStatus.FINGERS_DETECTED -> {}
            ReaderStatus.TAP_CANCELLED -> {
                fingerprintHelper.stop()
            }

            ReaderStatus.LOW_FINGERS_QUALITY -> {
                setMessage("Please tap your both fingers on readers.")
                handleCancelButtonsVisibility(isVisible = true)
                setStartButtonMessage("", isVisible = false)
                resetImages()
                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fingerprintHelper.isSessionOpen()
    }

    override fun onSessionChanges(readerStatus: ReaderStatus, data: String?) {
        this.readerStatus = readerStatus
        when (readerStatus) {
            ReaderStatus.NONE -> {
                //Initial value of the readers and readers are not initialized in this phase
//                setMessage(getString(R.string.initializing))
                runOnUiThread {
                    getDialog()?.show()
                }
            }

            ReaderStatus.SERVICE_BOUND -> {
                //Start scanning process readers are initialized
                setMessage(getString(R.string.scan))
                runOnUiThread {
                    setStartButtonMessage("Scan", true)
                    getDialog()?.dismiss()
                }
                if (fingerprintHelper.start()) {
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
                data?.setFingerQuality()
            }

            ReaderStatus.FINGERS_READ_SUCCESS -> {
                // save prints to storage and return the response through interface to main activity
                setStartButtonMessage("Done", true)
                handleCancelButtonsVisibility(isVisible = false)
                setMessage(getString(R.string.read_success))
                fingerprintHelper.waitFingersRelease()
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
                setStartButtonMessage("Start", true)
                setMessage(getString(R.string.scan))
            }

            ReaderStatus.FINGERS_DETECTED -> {
//                setMessage(getString(R.string.fingers_detected))
            }

            ReaderStatus.TAP_CANCELLED -> {}
            ReaderStatus.LOW_FINGERS_QUALITY -> {}
        }
    }

    //Listener is registered when file saved in storage
    private val onFileSavedListener = object : OnFileSavedListener {
        override fun onSuccess(path: String, readerNo: Int) {
            val file = File(path)
            Log.d(ScannerActivity::class.simpleName, file.name)
            list.add(file)
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
    }

    private fun setMessage(message: String) {
        runOnUiThread {
            tvStatus?.text = message
        }
    }

    private fun handleCancelButtonsVisibility(isVisible: Boolean) {
        runOnUiThread {
            btnCancel?.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun setStartButtonMessage(msg: String, isVisible: Boolean) {
        runOnUiThread {
            btnStart?.text = msg
            btnStart?.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    //Enable low power mode when finger scanning success
    // But enabling this will cause issues while termination of NBDevices and this error cause issue while reinitializing the readers
    //
    private fun enableLowPowerMode() = fingerprintHelper.enableLowPowerMode()

    override fun onDestroy() {
        super.onDestroy()
//        enableLowPowerMode()

    }

    override fun onStop() {
        super.onStop()
        fingerprintHelper.stop()
        setResult(RESULT_CANCELED)
        finish()
    }

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

    private fun showStoragePermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage and Camera permissions are required to access files and camera.")
            .setPositiveButton("Ok") { _, _ ->
                this.openApplicationDetailsSettings()
            }.show()
    }

    private fun Context.openApplicationDetailsSettings() {
        val intent = Intent()
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.setData(uri)
        startActivity(intent)
    }

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

    private fun checkQualityOfFingers(leftQualityPercentage: Int, rightQualityPercentage: Int) {
        if (leftQualityPercentage < 50 || rightQualityPercentage < 50) {
            list.clear()
            readerStatus = ReaderStatus.LOW_FINGERS_QUALITY
            setMessage("Scanned fingers quality should be more than 50%. Please scan again")
            handleCancelButtonsVisibility(isVisible = false)
            setStartButtonMessage("Scan again", isVisible = true)
        }
    }

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

}


