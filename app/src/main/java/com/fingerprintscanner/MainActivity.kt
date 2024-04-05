package com.fingerprintscanner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.AppCompatTextView
import com.scanner.activity.ScannerActivity
import com.scanner.utils.ReaderSessionHelper
import com.scanner.utils.ReaderStatus

class MainActivity : AppCompatActivity(), ReaderSessionHelper {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStartScan: AppCompatTextView = findViewById(R.id.tvStartScan)
        tvStartScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity()::class.java))
        }
    }

    override fun onSessionChanges(readerStatus: ReaderStatus) {
        when (readerStatus) {
            ReaderStatus.NONE -> {

            }

            ReaderStatus.SERVICE_BOUND -> {}
            ReaderStatus.INIT_FAILED -> {}
            ReaderStatus.FINGERS_RELEASED -> {}
            ReaderStatus.FINGERS_READ_SUCCESS -> {}
            ReaderStatus.FINGERS_READ_FAILED -> {}
            ReaderStatus.SESSION_CLOSED -> {}
            ReaderStatus.SESSION_OPEN -> {}
            ReaderStatus.FINGERS_DETECTED -> {}
            ReaderStatus.TAP_CANCELLED -> {}
        }
    }
}