package com.fingerprintscanner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import com.scanner.activity.ScannerActivity
import com.scanner.utils.constants.ScannerConstants
import java.io.File
import java.lang.StringBuilder

class MainActivity : AppCompatActivity() {
    private var tvStatus: AppCompatTextView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStartScan: Button = findViewById(R.id.btnStartScan)
        tvStatus = findViewById(R.id.tvStatus)
        tvStartScan.setOnClickListener {
            scanningLauncher.launch(Intent(this, ScannerActivity()::class.java))
        }


    }

    private val scanningLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val list: ArrayList<File>? = it.data?.serializable(ScannerConstants.DATA)
                Log.d(MainActivity::class.simpleName, list?.size.toString())
                handleResponse(list)
            }
        }

    private fun handleResponse(list: ArrayList<File>?) {
        var message = ""
        list?.forEach {
            message += "\n${it.path}"
        }
        if (message.isNotEmpty()) {
            tvStatus?.text = StringBuilder().append("File saved to paths :- ").append(message)
        }
    }
}