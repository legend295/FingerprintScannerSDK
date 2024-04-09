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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.scanner.utils.helper.ReaderSessionHelper
import com.scanner.utils.ReaderStatus
import com.scanner.utils.readers.FingerprintHelper
import com.github.legend295.fingerprintscanner.R
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
    private val list: ArrayList<File> = ArrayList()
    private val listOfTemplate = ArrayList<NBBiometricsExtractResult>()
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
    private var dialog: Pair<Dialog, AppCompatTextView>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        dialog = readersInitializationDialog()
        tvStatus = findViewById(R.id.tvStatus)
        // Initialize Fingerprint readers on background thread on main thread UI will stuck
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fingerprintHelper.init()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        tvStatus?.setOnClickListener {
            if (checkStorageAndCameraPermission()) {
                handleClick()
            } else {
                requestStorageAndCameraPermission()
            }
        }
    }

    private fun handleClick() {
        when (readerStatus) {
            ReaderStatus.SERVICE_BOUND, ReaderStatus.SESSION_OPEN -> {
                setMessage("Please tap your both fingers on readers.")
                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)
            }

            ReaderStatus.SESSION_CLOSED, ReaderStatus.INIT_FAILED -> {
                //first call cancel tap function of readers and then re-initialize the readers
                setMessage("Retrying...")
                list.clear()
                fingerprintHelper.cancelTap()
                fingerprintHelper.init()
            }

            ReaderStatus.NONE -> {}
            ReaderStatus.FINGERS_RELEASED -> {
//                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)
            }

            ReaderStatus.FINGERS_READ_SUCCESS -> {
//                fingerprintHelper.waitFingerRead(difFingerprintReadInfo)
            }

            ReaderStatus.FINGERS_READ_FAILED -> {}
            ReaderStatus.FINGERS_DETECTED -> {}
            ReaderStatus.TAP_CANCELLED -> {
                fingerprintHelper.stop()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fingerprintHelper.isSessionOpen()
    }

    override fun onSessionChanges(readerStatus: ReaderStatus) {
        this.readerStatus = readerStatus
        when (readerStatus) {
            ReaderStatus.NONE -> {
                //Initial value of the readers and readers are not initialized in this phase
                setMessage(getString(R.string.initializing))
                runOnUiThread {
                    dialog?.first?.show()
                }
            }

            ReaderStatus.SERVICE_BOUND -> {
                //Start scanning process readers are initialized
                setMessage(getString(R.string.scan))
                runOnUiThread {
                    dialog?.second?.text = "Initialized"
                    dialog?.first?.dismiss()
                }
                    if (fingerprintHelper.start()) {
                        Log.d(tag, "START OK")
                    } else {
                        Log.d(tag, "START FAILED")
                    }
            }

            ReaderStatus.INIT_FAILED -> {
                setMessage(getString(R.string.initializing_failed))
            }

            ReaderStatus.FINGERS_RELEASED -> {}
            ReaderStatus.FINGERS_READ_SUCCESS -> {
                // save prints to storage and return the response through interface to main activity
                fingerprintHelper.waitFingersRelease()
                setMessage(getString(R.string.read_success))
                enableLowPowerMode()
                val intent = Intent()
                intent.putExtra(ScannerConstants.DATA, list)
                setResult(RESULT_OK, intent)
                finish()

            }

            ReaderStatus.FINGERS_READ_FAILED -> {}
            ReaderStatus.SESSION_CLOSED -> {
                setMessage(getString(R.string.session_closed_retry))
            }

            ReaderStatus.SESSION_OPEN -> {
                setMessage(getString(R.string.scan))
            }

            ReaderStatus.FINGERS_DETECTED -> {
                setMessage(getString(R.string.fingers_detected))
            }

            ReaderStatus.TAP_CANCELLED -> {

            }
        }
    }

    //Listener is registered when file saved in storage
    private val onFileSavedListener = object : OnFileSavedListener {
        override fun onSuccess(path: String) {
            val file = File(path)
            Log.d(ScannerActivity::class.simpleName, file.name)
            list.add(file)

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

    //Enable low power mode when finger scanning success
    private fun enableLowPowerMode() = fingerprintHelper.enableLowPowerMode()

    override fun onDestroy() {
        super.onDestroy()
        enableLowPowerMode()
        fingerprintHelper.stop()
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

}