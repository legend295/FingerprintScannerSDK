package com.scanner.app

import android.app.Application
import com.scanner.utils.KeyStore
import com.scanner.utils.readers.FingerprintHelper

internal class ScannerApp : Application() {

    internal var fingerprintHelper: FingerprintHelper? = null
    internal var key: String? = null


    companion object {
        private var instance: ScannerApp? = null
        fun getInstance(): ScannerApp = getValue()
        private fun getValue(): ScannerApp {
            if (instance == null) {
                instance = ScannerApp()
            }
            return instance as ScannerApp
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }


}