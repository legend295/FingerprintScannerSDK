package com.scanner.app

import android.app.Application
import com.github.legend295.fingerprintscanner.BuildConfig
import com.newrelic.agent.android.NewRelic
import com.newrelic.agent.android.logging.LogLevel
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

        /*NewRelic.withApplicationToken(
            BuildConfig.NEW_RELIC_TOKEN
        ).withLoggingEnabled(true).withCrashReportingEnabled(true).start(this)*/
    }


}