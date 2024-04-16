package com.fingerprintscanner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import androidx.databinding.DataBindingUtil
import com.scanner.activity.ScannerActivity
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.serializable
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStartScan: Button = findViewById(R.id.tvStartScan)
        tvStartScan.setOnClickListener {
            scanningLauncher.launch(Intent(this, ScannerActivity()::class.java))
        }
    }

    private val scanningLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val list: ArrayList<File>? = it.data?.serializable(ScannerConstants.DATA)
                Log.d(MainActivity::class.simpleName, list?.size.toString())
            }
        }
}