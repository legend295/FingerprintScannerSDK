package com.scanner.app

import android.app.Application
import com.github.legend295.fingerprintscanner.BuildConfig
import com.newrelic.agent.android.NewRelic
import com.newrelic.agent.android.logging.LogLevel
import com.scanner.updated.reader.ScannerSessionManager
import com.scanner.utils.KeyStore
import com.scanner.utils.readers.FingerprintHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class ScannerApp : Application() {

    internal var fingerprintHelper: FingerprintHelper? = null
    internal var sessionManager: ScannerSessionManager? = null
    internal var key: String? = null

    /**
     * Scope for cloud writes that must outlive the activity that started them.
     *
     * A scan session ends with the user tapping Done, which finishes the activity and cancels
     * its `lifecycleScope` — killing any Storage upload still in flight ("Job was cancelled").
     * Firestore document writes survive that on their own because the SDK queues them locally,
     * but Storage uploads do not: they are plain suspending calls with nothing behind them.
     *
     * [SupervisorJob] so one failed upload cannot take the others down with it. Never cancelled —
     * it lives as long as the process, and work queued here is all short and fire-and-forget.
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


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