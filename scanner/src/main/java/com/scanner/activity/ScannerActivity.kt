package com.scanner.activity

import android.Manifest
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
import androidx.lifecycle.lifecycleScope
import com.nextbiometrics.biometrics.NBBiometricsExtractResult
import com.scanner.utils.ReaderSessionHelper
import com.scanner.utils.ReaderStatus
import com.scanner.utils.readers.FingerprintHelper
import com.github.legend295.fingerprintscanner.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScannerActivity : AppCompatActivity(),
    ReaderSessionHelper {

    private var tvStatus: AppCompatTextView? = null
    private val listOfTemplate = ArrayList<NBBiometricsExtractResult>()
    private val fingerprintHelper by lazy { FingerprintHelper(this, this, listOfTemplate) }
    private var readerStatus: ReaderStatus = ReaderStatus.NONE
    private val tag = ScannerActivity::class.java.simpleName
    private val difFingerprintReadInfo = "100,0,1.0"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        tvStatus = findViewById(R.id.tvStatus)
        lifecycleScope.launch(Dispatchers.IO) {
            fingerprintHelper.init()
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
                fingerprintHelper.close()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fingerprintHelper.isSessionOpen()
    }

    override fun onSessionChanges(readerStatus: ReaderStatus) {
//        readerSessionHelper.onSessionChanges(readerStatus)
        this.readerStatus = readerStatus
        when (readerStatus) {
            ReaderStatus.NONE -> {
                //Initial value of the readers and readers are not initialized in this phase
                setMessage(getString(R.string.initializing))
            }

            ReaderStatus.SERVICE_BOUND -> {
                //Start scanning process readers are initialized
                setMessage(getString(R.string.scan))
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

    private fun setMessage(message: String) {
        runOnUiThread {
            tvStatus?.text = message
        }
    }

    private fun enableLowPowerMode() = fingerprintHelper.enableLowPowerMode()

    override fun onDestroy() {
        super.onDestroy()
        fingerprintHelper.stop()
        fingerprintHelper.close()
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