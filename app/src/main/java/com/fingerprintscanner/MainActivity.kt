package com.fingerprintscanner

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ContentLoadingProgressBar
import com.fingerprintscanner.utility.showFieldsDialog
import com.github.legend295.fingerprintscanner.BuildConfig
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanner.activity.FingerprintScanner
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.ScannerConstants
import com.scanner.utils.enums.ScanningType
import org.json.JSONObject
import java.io.File
import androidx.core.content.edit
import com.scanner.updated.UpdatedFingerprintScanner
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private var tvStatus: AppCompatTextView? = null
    private var sheet: BottomSheetDialog? = null
    private var progressBar: ContentLoadingProgressBar? = null
    private var pendingBvn: String? = null
    private val themeOptions = ThemeOptions().apply {
        buttonColor = R.color.black
        buttonTextColor = R.color.white
        messageColor = R.color.black
        titleTextColor = R.color.black
        contentTextColor = R.color.black
        buttonBackground = R.drawable.bg_round_white
        popUpBackground = R.drawable.bg_round_white
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvRegistration: Button = findViewById(R.id.btnRegistration)
        val tvVerification: Button = findViewById(R.id.btnVerification)
        val tvVersion: AppCompatTextView = findViewById(R.id.tvVersion)
        progressBar = findViewById(R.id.progressBar)
        progressBar?.hide()

        tvStatus = findViewById(R.id.tvStatus)


        tvVersion.text = StringBuilder().append("v").append(com.fingerprintscanner.BuildConfig.VERSION_NAME)


        tvRegistration.setOnClickListener {
            val generatedBvn = generateBvnNumber()
            val generatedPhone = generatedBvn.take(10)
            sheet = showFieldsDialog(
                type = ScanningType.REGISTRATION,
                preFillBvn = generatedBvn,
                preFillPhone = generatedPhone
            ) { bvnNumber, phoneNumber, _, _, _ ->
                sheet?.dismiss()
                progressBar?.show()
                FingerprintScanner().getUser(bvnNumber) { _, user ->
                    if (user == null) {
                        progressBar?.hide()
                        startRegistration(bvnNumber, phoneNumber)
                        return@getUser
                    }
                    FingerprintScanner().doesFileExistsInLocalStorage(this, bvnNumber) { doesExist ->
                        if (doesExist)
                            FingerprintScanner().uploadFiles(this, user) { _, msg ->
                                progressBar?.hide()
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            }
                        else {
                            progressBar?.hide()
                            startRegistration(bvnNumber, phoneNumber)
                        }
                    }
                }
            }
        }

        tvVerification.setOnClickListener {
            val registeredBvn = getRegisteredBvn()
            sheet = showFieldsDialog(
                type = ScanningType.VERIFICATION,
                preFillBvn = registeredBvn,
                preFillPhone = ""
            ) { bvnNumber, phoneNumber, _, amount, _ ->
                sheet?.dismiss()
                pendingBvn = null
//                progressBar?.show()
                val finalAmount = if (amount.isEmpty()) 100 else amount.toInt()
//                FingerprintScanner.Builder(this)
                UpdatedFingerprintScanner.Builder(this)
                    .setUniqueId(bvnNumber)
                    .setAmount(finalAmount)
                    .skipFirebaseActions(true)
                    .setScanningType(ScanningType.VERIFICATION)
                    .newRelicToken(BuildConfig.NEW_RELIC_TOKEN)
                    .skipLocation(skipLocation = true)
                    .setThemeOptions(themeOptions)
                    .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
                    .start(this, scanningLauncher)
            }

//            if (registeredBvn == null) {
//                Toast.makeText(this, "No user found. Please register first.", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }

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

    private fun startRegistration(bvnNumber: String, phoneNumber: String) {
        pendingBvn = bvnNumber
        UpdatedFingerprintScanner.Builder(this)
//        FingerprintScanner.Builder(this)
            .setUniqueId(bvnNumber)
            .setPhoneNumber(phoneNumber)
            .skipFirebaseActions(true)
            .setScanningType(ScanningType.REGISTRATION)
            .storagePath("biometrics/")
            .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
            .setThemeOptions(themeOptions)
            .setCustomData(JSONObject().apply {
                put("pin", 1234)
            })
            .newRelicToken(BuildConfig.NEW_RELIC_TOKEN)
            .skipLocation(skipLocation = false)
            .enableBmpExport(enable = true)
            .uploadBmpToFirebase(enable = true)
            .start(this, scanningLauncher)
    }

    private fun generateBvnNumber(): String {
        val lastRegisteredBvn = getRegisteredBvn()
        val lastSuffix = lastRegisteredBvn?.takeLast(4)?.toIntOrNull() ?: 0
        val nextSuffix = (lastSuffix + 1).coerceAtMost(9999)
        return "9999999${nextSuffix.toString().padStart(4, '0')}"
    }

    private fun saveRegisteredBvn(bvn: String) {
        /*val dir = File("${filesDir.path}/$bvn/")
        val datCount = dir.listFiles { f -> f.name.endsWith(".dat") }?.size ?: 0
        if (datCount >= 2) {*/
        getSharedPreferences("scanner_prefs", MODE_PRIVATE)
            .edit { putString("registered_bvn", bvn) }
//        }
    }

    private fun getRegisteredBvn(): String? {
        return getSharedPreferences("scanner_prefs", MODE_PRIVATE)
            .getString("registered_bvn", null)
    }

    private fun startScanning() {
        FingerprintScanner.Builder(this)
            .setScanningType(ScanningType.REGISTRATION)
            .skipFirebaseActions(true)
            .storagePath("biometrics/")
            .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
            .setThemeOptions(themeOptions)
            .setCustomData(JSONObject().apply {
                put("pin", 1234)
            })
            .newRelicToken(BuildConfig.NEW_RELIC_TOKEN)
            .skipLocation(skipLocation = false)
            .start(this, scanningLauncher)

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
                val templateList: ArrayList<File>? =
                    it.data?.serializable(ScannerConstants.TEMPLATE_DATA)
                val isVerified: Boolean? =
                    it.data?.getBooleanExtra(ScannerConstants.VERIFICATION_RESULT, false)
                Log.d(MainActivity::class.simpleName, list?.size.toString())

                // Both fingerprints scanned and saved → persist BVN for future verification
                if (pendingBvn != null) {
                    saveRegisteredBvn(pendingBvn!!)
                    pendingBvn = null
                }

                handleResponse(list, isVerified, templateList)
            }
        }

    private fun handleResponse(
        list: ArrayList<File>?,
        isVerified: Boolean?,
        templateList: ArrayList<File>?
    ) {
        if (list.isNullOrEmpty()) {
            tvStatus?.text =
                StringBuilder().append("Fingerprint verification :- ").append(isVerified)
            return
        }
        println(list.size)
        var message = ""
        list.forEach {
            message += "\n${it.path}"
        }
        if (message.isNotEmpty()) {
            tvStatus?.text = StringBuilder().append("File saved to paths :- ").append(message)
        }
        var templateMessage = ""
        println(templateList?.size)
//        Toast.makeText(this, "Template size - ${templateList?.size}", Toast.LENGTH_SHORT).show()
        templateList?.forEach {
            templateMessage += "\n${it.path}"
        }
        if (templateMessage.isNotEmpty()) {
            tvStatus?.text =
                StringBuilder().append("File saved to paths :- ").append(message)
                    .append("\nTemplate Saved at paths:-\n")
                    .append(templateMessage)
        }
    }
}