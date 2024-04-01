package com.fingerprintscanner

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.scanner.ArithmaticsHelper

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ArithmaticsHelper().add(4, 4)
    }
}