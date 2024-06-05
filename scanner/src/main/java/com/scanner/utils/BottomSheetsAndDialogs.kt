package com.scanner.utils

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.github.legend295.fingerprintscanner.R
import com.github.legend295.fingerprintscanner.databinding.LayoutInitializationDialogBinding

internal fun Context.readersInitializationDialog(): Dialog {
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)
//    val tvStatus = layout.findViewById<AppCompatTextView>(R.id.tvStatus)
    with(layout) {
        setOnClickListener {
//            dialog.dismiss()
        }
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)

    return dialog
}

internal fun Context.verificationDialog(): Dialog {
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)
//    val tvStatus = layout.findViewById<AppCompatTextView>(R.id.tvStatus)
    with(layout) {
        val title = findViewById<AppCompatTextView>(R.id.tvTitle)
        val message = findViewById<AppCompatTextView>(R.id.tvMessage)
        title.text = "Verifying Fingerprints"
        message.text =
            "The fingerprint authorization is in progress. Please tap both fingers above."
        setOnClickListener {
//            dialog.dismiss()
        }
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)

    return dialog
}

internal fun Context.templatesDownloadDialog(): Dialog {
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)

    with(layout) {
        val title = findViewById<AppCompatTextView>(R.id.tvTitle)
        val message = findViewById<AppCompatTextView>(R.id.tvMessage)
        title.text = getString(R.string.downloading_fingerprints)
        message.text = getString(R.string.fingerprints_download_is_in_progress_please_wait)
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)

    return dialog
}