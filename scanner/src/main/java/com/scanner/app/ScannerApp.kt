package com.scanner.app

import android.app.Application
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.nibss.nibss_pos_tracker_compact.NibssSdk
import com.nibss.nibss_pos_tracker_compact.core.NibssConfiguration
import com.scanner.activity.ScannerActivity
import com.scanner.model.User
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
//        NibssSdk.initialize(this, NibssConfiguration.Builder().setDeviceTerminalSerialNo("1329487239").build())
    }


}