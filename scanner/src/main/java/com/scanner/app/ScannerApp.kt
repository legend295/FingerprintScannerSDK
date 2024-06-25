package com.scanner.app

import android.app.Application
import com.scanner.utils.KeyStore
import com.scanner.utils.readers.FingerprintHelper

internal class ScannerApp : Application() {

    internal var fingerprintHelper: FingerprintHelper? = null


    companion object {
        private lateinit var instance: ScannerApp
        fun getInstance(): ScannerApp = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }


}