package com.fingerprintscanner.utility

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.fingerprintscanner.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanner.utils.enums.ScanningType

private val bvnNumber =
    "coopvest823n283n23"// only for testing can be replaced with user's original bvn number
private val phoneNumber =
    "12345678900"// only for testing can be replaced with user's original phone number

fun Context.showFieldsDialog(
    type: ScanningType,
    preFillBvn: String? = null,
    preFillPhone: String? = null,
    callback: (String, String, String, String, String) -> Unit
): BottomSheetDialog {
    val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
    val layout = View.inflate(this, R.layout.layout_information_dialog, null)
    val bvnNumber = layout.findViewById<AppCompatEditText>(R.id.etBvnNumber)
    val phoneNumber = layout.findViewById<AppCompatEditText>(R.id.etPhoneNumber)
    val amount = layout.findViewById<AppCompatEditText>(R.id.etAmount)
    val name = layout.findViewById<AppCompatEditText>(R.id.etName)
    val etKey = layout.findViewById<AppCompatEditText>(R.id.etKey)

    if (!preFillBvn.isNullOrEmpty()) {
        bvnNumber.setText(preFillBvn)
//        bvnNumber.isEnabled = false
    }
    if (!preFillPhone.isNullOrEmpty()) {
        phoneNumber.setText(preFillPhone)
//        phoneNumber.isEnabled = false
    }

    if (type == ScanningType.VERIFICATION) {
        phoneNumber.visibility = View.GONE
        name.visibility = View.GONE
        amount.visibility = View.VISIBLE
        amount.imeOptions = EditorInfo.IME_ACTION_DONE
    }
    name.visibility = View.GONE
    etKey.visibility = View.GONE
    layout.findViewById<Button>(R.id.btnDone).setOnClickListener {
        if (amount.text.isNullOrEmpty() && type == ScanningType.VERIFICATION) {
            Toast.makeText(sheet.context, "Amount is required", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        if (bvnNumber.text.isNullOrEmpty()) {
            Toast.makeText(sheet.context, "BVN number is required", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        callback(
            bvnNumber.text.toString(),
            phoneNumber.text.toString(),
            name.text.toString(),
            amount.text.toString(),
            etKey.text.toString()
        )
    }
    sheet.setContentView(layout)
    sheet.setCommonSettings()
    sheet.show()
    return sheet
}

fun BottomSheetDialog.setCommonSettings() {
    behavior.skipCollapsed = true
    behavior.state = BottomSheetBehavior.STATE_EXPANDED

    dismissWithAnimation = true
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window?.setDimAmount(0.4f)
    window?.statusBarColor = Color.TRANSPARENT
}