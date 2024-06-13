package com.fingerprintscanner.utility

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import com.fingerprintscanner.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanner.utils.enums.ScanningType

private val bvnNumber =
    "coopvest823n283n23"// only for testing can be replaced with user's original bvn number
private val phoneNumber =
    "12345678900"// only for testing can be replaced with user's original phone number

fun Context.showFieldsDialog(
    type: ScanningType,
    callback: (String, String, String, String) -> Unit
): BottomSheetDialog {
    val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
    val layout = View.inflate(this, R.layout.layout_information_dialog, null)
    val bvnNumber = layout.findViewById<AppCompatEditText>(R.id.etBvnNumber)
    val phoneNumber = layout.findViewById<AppCompatEditText>(R.id.etPhoneNumber)
    val amount = layout.findViewById<AppCompatEditText>(R.id.etAmount)
    val name = layout.findViewById<AppCompatEditText>(R.id.etName)
    if (type == ScanningType.VERIFICATION) {
        phoneNumber.visibility = View.GONE
        name.visibility = View.GONE
        amount.visibility = View.VISIBLE
        amount.imeOptions = EditorInfo.IME_ACTION_DONE
    }
    layout.findViewById<Button>(R.id.btnDone).setOnClickListener {
        if (amount.text.isNullOrEmpty() && type == ScanningType.VERIFICATION) {
            Toast.makeText(sheet.context, "Amount is required", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        callback(
            bvnNumber.text.toString(),
            phoneNumber.text.toString(),
            name.text.toString(),
            amount.text.toString()
        )
    }
    sheet.setContentView(layout)
    sheet.show()
    return sheet
}