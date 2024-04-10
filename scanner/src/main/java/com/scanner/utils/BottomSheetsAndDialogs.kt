package com.scanner.utils

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.github.legend295.fingerprintscanner.R
import com.github.legend295.fingerprintscanner.databinding.LayoutInitializationDialogBinding

fun Context.readersInitializationDialog(): Pair<Dialog, AppCompatTextView> {
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)
    val tvStatus = layout.findViewById<AppCompatTextView>(R.id.tvStatus)
    with(layout) {
        setOnClickListener {
//            dialog.dismiss()
        }
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)

    return Pair(dialog, tvStatus)
}