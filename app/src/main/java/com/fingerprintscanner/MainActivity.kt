package com.fingerprintscanner

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.widget.AppCompatTextView
import com.scanner.activity.ScannerActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStartScan: AppCompatTextView = findViewById(R.id.tvStartScan)
        tvStartScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity()::class.java))
        }
    }
}