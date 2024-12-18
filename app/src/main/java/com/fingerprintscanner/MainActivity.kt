package com.fingerprintscanner

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import com.fingerprintscanner.utility.showFieldsDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.firestore.DocumentSnapshot
import com.scanner.activity.FingerprintScanner
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.Keys
import com.scanner.utils.constants.Keys.FINGER_PRINT_SYNCED_ON_CLOUD
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.constants.Source
import com.scanner.utils.enums.ScanningType
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var tvStatus: AppCompatTextView? = null
    private var sheet: BottomSheetDialog? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvRegistration: Button = findViewById(R.id.btnRegistration)
        val tvVerification: Button = findViewById(R.id.btnVerification)
        tvStatus = findViewById(R.id.tvStatus)
        val themeOptions = ThemeOptions().apply {
            buttonColor = R.color.black
            buttonTextColor = R.color.white
            messageColor = R.color.black
            titleTextColor = R.color.black
            contentTextColor = R.color.black
            buttonBackground = R.drawable.bg_round_white
            popUpBackground = R.drawable.bg_round_white
        }
        tvRegistration.setOnClickListener {
            /*sheet =
                showFieldsDialog(ScanningType.REGISTRATION) { bvnNumber, phoneNumber, name, _, key ->
                    FingerprintScanner.Builder(this).setUniqueId(bvnNumber)
                        .setPhoneNumber(phoneNumber)
                        .setScanningType(ScanningType.REGISTRATION)
                        .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
                        .setThemeOptions(themeOptions)
                        .setCustomData(JSONObject().apply {
                            put("pin", 1234)
                        })
                        .start(this, scanningLauncher)
                }*/
            if (FingerprintScanner().doesFileExistsInLocalStorage(this, "99999999914")) {
                println("Files found in local storage")
                FingerprintScanner().getUser("99999999914") { isSuccess, user ->
                    if (isSuccess && user?.fingerPrintSyncedOnCloud == false) {
                        Log.d(MainActivity::class.simpleName, "By Unique Id - $user")
                        FingerprintScanner().uploadFiles(
                            this,
                            user
                        ) { _, msg ->
                            println("MainActivity File upload - $msg")
                        }
                    } else {
                        println("User not found")
                    }
                }
                /*FingerprintScanner().queryUserByKeyValue(
                    hashMapOf(
                        Keys.UNIQUE_ID to "99999999914",
                        FINGER_PRINT_SYNCED_ON_CLOUD to false
                    ),
                    null
                ) { isSuccess, users ->

                }*/
            } else println("Files not found in local storage")

            /*FingerprintScanner().getUser("99999999914",) { isSuccess, users ->
                if (isSuccess) {
                    Log.d(MainActivity::class.simpleName, "By Unique Id - ${users.toString()}")
                } else {
                    println("User not found")
                }
            }*/
        }

        tvVerification.setOnClickListener {
            sheet = showFieldsDialog(ScanningType.VERIFICATION) { bvnNumber, _, _, amount, key ->
                FingerprintScanner.Builder(this).setUniqueId(bvnNumber)
                    .setAmount(amount.toInt())
                    .setScanningType(ScanningType.VERIFICATION)
                    .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
                    .start(this, scanningLauncher)
            }
        }


        /*val data = mutableMapOf<String, Any>()
        data.apply {
            put("pin", 1235)
        }
        FingerprintScanner().updateCustomDataInDb("99999999913", data){}

        FingerprintScanner().getUser("99999999913") { isSuccess, user ->
            Log.d(MainActivity::class.simpleName, user.toString())
            if (isSuccess) {

            }
        }*/


        /* FingerprintScanner().queryUserByKeyValue(Keys.PHONE_NUMBER,"9999999913") { isSuccess, user ->
             Log.d(MainActivity::class.simpleName, "By Phone - ${user.toString()}")
             if (isSuccess) {

             }
         }*/
    }

    override fun onPause() {
        super.onPause()
        try {
            sheet?.dismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val scanningLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                val list: ArrayList<File>? = it.data?.serializable(ScannerConstants.DATA)
//                val customObject: JSONObject =
//                    (it.data?.getStringExtra(ScannerConstants.CUSTOM_DATA).toString()) as JSONObject
//                Log.d(MainActivity::class.simpleName, customObject.toString())
                val isVerified: Boolean? =
                    it.data?.getBooleanExtra(ScannerConstants.VERIFICATION_RESULT, false)
                Log.d(MainActivity::class.simpleName, list?.size.toString())
                handleResponse(list, isVerified)
            }
        }

    private fun handleResponse(list: ArrayList<File>?, isVerified: Boolean?) {
        if (list.isNullOrEmpty()) {
            tvStatus?.text =
                StringBuilder().append("Fingerprint verification :- ").append(isVerified)
            return
        }
        var message = ""
        list.forEach {
            message += "\n${it.path}"
        }
        if (message.isNotEmpty()) {
            tvStatus?.text = StringBuilder().append("File saved to paths :- ").append(message)
        }
    }
}