package com.fingerprintscanner

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ContentLoadingProgressBar
import android.content.Intent
import android.widget.ImageView
import com.fingerprintscanner.data.AppSettings
import com.fingerprintscanner.data.ScanHistory
import com.fingerprintscanner.data.ScanRecord
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

    /**
     * Operator settings, read fresh at each scan launch rather than cached — the user can
     * change them in [SettingsActivity] and come straight back here.
     */
    private val settings by lazy { AppSettings(this) }

    /** Local audit log — every scan lands here, including declines and abandoned attempts. */
    private val history by lazy { ScanHistory(this) }

    /**
     * ID of the verification just launched. [pendingBvn] only tracks registrations, so without
     * this a verification result would be logged against an empty ID.
     */
    private var lastScannedId: String = ""

    /** Amount attached to the verification just launched, for the history row. */
    private var lastAmount: Double? = null
    private val themeOptions = ThemeOptions().apply {
        buttonColor = R.color.waxd_primary
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

        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }


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
                val finalAmount = if (amount.isEmpty()) 100 else amount.toInt()
                lastScannedId = bvnNumber
                lastAmount = finalAmount.toDouble()
//                FingerprintScanner.Builder(this)
                UpdatedFingerprintScanner.Builder(this)
                    .setUniqueId(bvnNumber)
                    .setAmount(finalAmount)//7347364276@08
//                    .skipFirebaseActions(true)
                    .setScanningType(ScanningType.VERIFICATION)
                    .newRelicToken(BuildConfig.NEW_RELIC_TOKEN)
                    .skipLocation(skipLocation = false)
//                    .enableBmpExport(enable = true)
//                    .uploadBmpToFirebase(enable = true)
                    // Read at launch, not cached: the operator may have just changed it.
                    .saveVerificationCaptures(settings.saveVerificationCaptures)
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
        lastScannedId = bvnNumber
        lastAmount = null
        UpdatedFingerprintScanner.Builder(this)
//        FingerprintScanner.Builder(this)
            .setUniqueId(bvnNumber)
            .setPhoneNumber(phoneNumber)
//            .skipFirebaseActions(true)
            .setScanningType(ScanningType.REGISTRATION)
            .storagePath("biometrics/")
            .setKey("com.scanner.24e2c72b-6506-490d-a818-4112526db233")
            .setThemeOptions(themeOptions)
            .setCustomData(JSONObject().apply {
                put("pin", 1234)
            })
            .newRelicToken(BuildConfig.NEW_RELIC_TOKEN)
            .skipLocation(skipLocation = false)
            .allowDuplicateFingerprints(settings.allowDuplicateFingerprints)
            .enableBmpExport(enable = true)
            .uploadBmpToFirebase(enable = true)
            .start(this, scanningLauncher)
    }

    /**
     * Appends one row to the local audit log.
     *
     * [summaryJson] is [ScannerConstants.SCAN_SUMMARY] straight from the scanner — the liveness,
     * threshold, quality and match figures the SDK measured. Parsed leniently: a missing or
     * malformed summary costs the numbers, never the row itself.
     */
    private fun recordHistory(
        isRegistration: Boolean,
        uniqueId: String,
        outcome: String,
        detail: String,
        summaryJson: String?,
    ) {
        if (uniqueId.isBlank()) return
        val summary = summaryJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        history.record(
            ScanRecord(
                at = System.currentTimeMillis(),
                kind = if (isRegistration) HistoryActivity.KIND_REGISTRATION
                else HistoryActivity.KIND_VERIFICATION,
                uniqueId = uniqueId,
                outcome = outcome,
                detail = detail,
                amount = if (isRegistration) null else lastAmount,
                liveness = summary?.optString("liveness").orEmpty(),
                quality = summary?.optString("quality").orEmpty(),
                threshold = summary?.optInt("threshold")?.takeIf { it > 0 },
                score = summary?.optInt("matchScore")?.takeIf { it > 0 },
            )
        )
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
            if (it.resultCode != RESULT_OK) {
                // The scanner closes with RESULT_CANCELED when the operator backs out of a
                // failed pass. Worth logging: an abandoned attempt is exactly what an assessor
                // asks about, and it never reaches Firestore.
                recordHistory(
                    isRegistration = pendingBvn != null,
                    uniqueId = pendingBvn ?: lastScannedId,
                    outcome = "Cancelled",
                    detail = "Closed without completing the scan",
                    summaryJson = null,
                )
                pendingBvn = null
                return@registerForActivityResult
            }

            val list: ArrayList<File>? = it.data?.serializable(ScannerConstants.DATA)
            val templateList: ArrayList<File>? =
                it.data?.serializable(ScannerConstants.TEMPLATE_DATA)
            val isVerified: Boolean? =
                it.data?.getBooleanExtra(ScannerConstants.VERIFICATION_RESULT, false)
            val summaryJson = it.data?.getStringExtra(ScannerConstants.SCAN_SUMMARY)
            Log.d(MainActivity::class.simpleName, list?.size.toString())

            val wasRegistration = pendingBvn != null
            recordHistory(
                isRegistration = wasRegistration,
                uniqueId = pendingBvn ?: lastScannedId,
                outcome = when {
                    wasRegistration -> "Registered"
                    isVerified == true -> "Approved"
                    else -> "Declined"
                },
                detail = if (wasRegistration) {
                    "${templateList?.size ?: 0} template(s) saved"
                } else {
                    ""
                },
                summaryJson = summaryJson,
            )

            // Both fingerprints scanned and saved → persist BVN for future verification
            if (pendingBvn != null) {
                saveRegisteredBvn(pendingBvn!!)
                pendingBvn = null
            }

            handleResponse(list, isVerified, templateList)
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