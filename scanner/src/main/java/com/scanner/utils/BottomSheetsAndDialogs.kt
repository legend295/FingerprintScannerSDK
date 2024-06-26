package com.scanner.utils

import android.app.Dialog
import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.github.legend295.fingerprintscanner.R
import com.github.legend295.fingerprintscanner.databinding.LayoutInitializationDialogBinding
import java.util.logging.Handler

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

internal fun Context.verificationDialog(isSuccess: Boolean, callback: () -> Unit): Dialog {
    Log.d(this::class.simpleName, "showing:: verificationDialog")
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)
    with(layout) {
        val title = findViewById<AppCompatTextView>(R.id.tvTitle)
        val message = findViewById<AppCompatTextView>(R.id.tvMessage)
        val ivClose = findViewById<AppCompatImageView>(R.id.ivClose)
        val ivStatus = findViewById<AppCompatImageView>(R.id.ivStatus)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        progressBar.visibility = View.GONE
        ivClose.visibility = View.VISIBLE
        ivStatus.visibility = View.VISIBLE
        message.visibility = View.GONE
        ivStatus.setImageDrawable(
            ContextCompat.getDrawable(
                this@verificationDialog,
                if (isSuccess) R.drawable.ic_success else R.drawable.ic_failure
            )
        )
        title.text = if (isSuccess) "Transaction Authorised" else "Transaction Failed"
        message.text =
            if (isSuccess) "The fingerprint authorization is\nsucceeded." else "The fingerprint authorization is\nfailed."
        ivClose.setOnClickListener {
            dialog.dismiss()
            callback()
        }
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)
    dialog.show()

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

internal fun Context.transactionOutOfArea(callback: () -> Unit): Dialog {
    val dialog = Dialog(this, R.style.DialogStyleInstagram)
    val layout = View.inflate(this, R.layout.layout_initialization_dialog, null)
    with(layout) {
        val title = findViewById<AppCompatTextView>(R.id.tvTitle)
        val message = findViewById<AppCompatTextView>(R.id.tvMessage)
        val ivClose = findViewById<AppCompatImageView>(R.id.ivClose)
        val ivStatus = findViewById<AppCompatImageView>(R.id.ivStatus)
        val ivFingerprint = findViewById<AppCompatImageView>(R.id.ivFingerprint)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        progressBar.visibility = View.GONE
        ivClose.visibility = View.VISIBLE
        ivStatus.visibility = View.GONE
        message.visibility = View.VISIBLE
        ivFingerprint.setImageDrawable(
            ContextCompat.getDrawable(
                this@transactionOutOfArea,
                R.drawable.ic_map
            )
        )
        title.text = "Transaction Failed"
        message.text = "The transaction is out of the\nauthorized area."
        ivClose.setOnClickListener {
            dialog.dismiss()
            callback()
        }
    }

    dialog.setContentView(layout)
    dialog.setCancelable(false)
    dialog.show()

    return dialog
}
