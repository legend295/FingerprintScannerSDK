package com.scanner.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.gson.Gson
import com.scanner.activity.ScannerActivity
import com.scanner.model.User
import com.scanner.utils.builder.BuilderOptions
import com.scanner.utils.builder.ThemeOptions
import com.scanner.utils.constants.Constant
import com.scanner.utils.constants.Constant.SCANNING_OPTIONS
import com.scanner.utils.enums.ScanningType
import org.json.JSONObject

class FingerprintScanner {

    class Builder(val context: Context) {
        private val options: BuilderOptions = BuilderOptions()

        fun setScanningType(scanningType: ScanningType?): Builder {
            options.scanningType = scanningType
            return this
        }


        fun setUniqueId(uniqueId: String): Builder {
            options.uniqueId = uniqueId
            return this
        }

        fun setPhoneNumber(phoneNumber: String): Builder {
            options.phoneNumber = phoneNumber
            return this
        }

        fun setAmount(amount: Int): Builder {
            options.amount = amount
            return this
        }

        fun setKey(key: String): Builder {
            options.key = key
            return this
        }

        fun setBankProvider(bankProviderName: String): Builder {
            options.bankProvider = bankProviderName
            return this
        }

        fun setLoginType(loginType: String): Builder {
            options.loginType = loginType
            return this
        }

        fun setUserId(userId: String): Builder {
            options.userId = userId
            return this
        }

        fun setThemeOptions(themeOptions: ThemeOptions): Builder {
            options.themeOptions = themeOptions
            return this
        }

        fun setCustomData(customObject: JSONObject): Builder {
            options.customObject = customObject
            return this
        }

        fun skipLocation(skipLocation: Boolean): Builder {
            options.skipLocation = skipLocation
            return this
        }

        fun newRelicToken(newRelicToken: String): Builder {
            options.newRelicToken = newRelicToken
            return this
        }

        fun storagePath(path: String): Builder {
            options.storagePath = path
            return this
        }

        fun skipFirebaseActions(skipFirebaseActions: Boolean): Builder {
            options.skipFirebaseActions = skipFirebaseActions
            return this
        }

        fun enableBmpExport(enable: Boolean): Builder {
            options.enableBmpExport = enable
            return this
        }


        fun start(
            activity: Activity,
            launcher: ActivityResultLauncher<Intent>
        ) {
            try {
                validate()
                launcher.launch(getIntent(activity))
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }

        fun start(fragment: Fragment, launcher: ActivityResultLauncher<Intent>) {
            validate()
            launcher.launch(getIntent(fragment.activity))
        }

        private fun validate() {
            if (options.scanningType == null) throw NullPointerException("Scanning Type cannot be null")
            if (options.skipFirebaseActions) return
            if (options.uniqueId == null) throw NullPointerException("Bvn number cannot be null")
            require(options.uniqueId!!.isNotEmpty()) { "Bvn number cannot be empty" }
            /*if (options.scanningType == ScanningType.REGISTRATION) {
                if (options.phoneNumber == null) throw NullPointerException("Phone number cannot be null")
                require(options.phoneNumber!!.isNotEmpty()) { "Phone number cannot be empty" }
            } else*/ if (options.scanningType == ScanningType.VERIFICATION) {
                if (options.amount == null) throw NullPointerException("Please enter amount")
                require((options.amount ?: 0) > 0) { "Please enter valid amount" }
            }
            if (options.key?.trim()
                    .isNullOrEmpty()
            ) throw NullPointerException("Encryption key cannot be null or empty")
            require(options.key?.trim()?.isNotEmpty() == true) { "Encryption key cannot be empty" }
        }

        private fun getIntent(activity: Activity?): Intent {
            val intent = Intent(activity, ScannerActivity::class.java)
            val gson = Gson()
            val bundle = Bundle()
            bundle.putString(
                SCANNING_OPTIONS,
                gson.toJson(options)
            )
            intent.putExtras(bundle)
            return intent
        }
    }


    fun getUser(bvnNumber: String, callback: (Boolean, User?) -> Unit) {
        ScannerActivity().getUser(bvnNumber, callback)
    }

    fun uploadFiles(context: Context, user: User?, callback: (Boolean, String) -> Unit) {
        ScannerActivity().uploadFiles(context, user, callback)
    }

    fun doesFileExistsInLocalStorage(
        context: Context,
        uniqueId: String,
        callback: (Boolean) -> Unit
    ) =
        ScannerActivity().doesFileExistsInLocalStorage(context, uniqueId, callback)

    fun doesFileExistsOnFirebaseStorage(
        uniqueId: String,
        callback: (Boolean, fileCount: Int) -> Unit
    ) =
        ScannerActivity().doesFileExistsOnFirebaseStorage(uniqueId, callback)

    fun deleteFilesFromFirebaseStorage(
        context: Context,
        uniqueId: String,
        callback: (Boolean) -> Unit
    ) = ScannerActivity().deleteFilesFromFirebaseStorage(context, uniqueId, callback)

    fun queryUserByKeyValue(
        queryMap: HashMap<String, Any>,
        source: com.scanner.utils.constants.Source?,
        callback: (Boolean, users: QuerySnapshot?) -> Unit
    ) {
        ScannerActivity().queryUserByKeyValue(queryMap, source, callback)
    }

    fun updateCustomDataInDb(
        uniqueId: String,
        customObject: MutableMap<String, Any>,
        callback: (Boolean) -> Unit
    ) {
        ScannerActivity().updateCustomDataInDb(
            uniqueId,
            hashMapOf(Constant.CUSTOM_OBJECT to customObject),
            callback
        )
    }

}