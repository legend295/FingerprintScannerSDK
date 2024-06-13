package com.scanner.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.scanner.utils.BuilderOptions
import com.scanner.utils.constants.Constant.SCANNING_OPTIONS
import com.scanner.utils.enums.ScanningType

class FingerprintScanner {

    /* fun builder(): Builder {
         return Builder()
     }*/

    class Builder(val context: Context) {
        private val options: BuilderOptions = BuilderOptions()

        fun setScanningType(scanningType: ScanningType?): Builder {
            options.scanningType = scanningType
            return this
        }


        fun setBvnNumber(bvnNumber: String): Builder {
            options.bvnNumber = bvnNumber
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

        /*  fun setFirebaseFireStore(firebaseFireStore: FirebaseFirestore): Builder {
              options.firebaseFireStore = firebaseFireStore
              return this
          }*/

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
            if (options.bvnNumber == null) throw NullPointerException("Bvn number cannot be null")
            require(options.bvnNumber!!.isNotEmpty()) { "Bvn number cannot be empty" }
            if (options.scanningType == ScanningType.REGISTRATION) {
                if (options.phoneNumber == null) throw NullPointerException("Phone number cannot be null")
                require(options.phoneNumber!!.isNotEmpty()) { "Phone number cannot be empty" }
            } else if (options.scanningType == ScanningType.VERIFICATION) {
                if (options.amount == null) throw NullPointerException("Please enter amount")
                require((options.amount ?: 0) > 0) { "Please enter valid amount" }
            }
//            if (options.firebaseFireStore == null) throw NullPointerException("Firebase FireStore cannot be null")
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

}