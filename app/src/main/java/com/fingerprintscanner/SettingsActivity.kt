package com.fingerprintscanner

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import com.fingerprintscanner.data.AppSettings
import com.fingerprintscanner.data.ScanHistory
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.scanner.updated.UpdatedFingerprintScanner
import kotlinx.coroutines.launch

/**
 * Operator settings, and a scoped data wipe.
 *
 * The two switches are read by [MainActivity] when it launches a scan — nothing here changes
 * SDK behaviour on its own.
 *
 * The wipe deletes one unique ID at a time: its templates and verification captures on this
 * device, its Firebase Storage files and its Firestore user document. Nothing outside that ID
 * is touched, and it asks before doing any of it — this removes real records from a live
 * Firebase project and there is no undo.
 */
class SettingsActivity : AppCompatActivity() {

    private val settings by lazy { AppSettings(this) }

    /** Utility instance — the SDK's Firebase helpers are instance methods, not statics. */
    private val scanner by lazy { UpdatedFingerprintScanner() }

    /** Wiping an ID must take its audit rows with it, or History would list phantom scans. */
    private val history by lazy { ScanHistory(this) }

    private lateinit var inputUniqueId: TextInputEditText
    private lateinit var tvRegistered: AppCompatTextView
    private lateinit var tvClearStatus: AppCompatTextView
    private lateinit var btnClear: MaterialButton
    private lateinit var progress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        inputUniqueId = findViewById(R.id.inputUniqueId)
        tvRegistered = findViewById(R.id.tvRegistered)
        tvClearStatus = findViewById(R.id.tvClearStatus)
        btnClear = findViewById(R.id.btnClear)
        progress = findViewById(R.id.progress)

        val switchAllowDuplicates: MaterialSwitch = findViewById(R.id.switchAllowDuplicates)
        switchAllowDuplicates.isChecked = settings.allowDuplicateFingerprints
        switchAllowDuplicates.setOnCheckedChangeListener { _, checked ->
            settings.allowDuplicateFingerprints = checked
        }

        val switchSaveVerifications: MaterialSwitch = findViewById(R.id.switchSaveVerifications)
        switchSaveVerifications.isChecked = settings.saveVerificationCaptures
        switchSaveVerifications.setOnCheckedChangeListener { _, checked ->
            settings.saveVerificationCaptures = checked
        }

        btnClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        showRegisteredIds()
    }

    /** Lists what is actually on this device, so the operator can see what a wipe would target. */
    private fun showRegisteredIds() {
        val ids = scanner.localRegisteredIds(this)
        tvRegistered.text = if (ids.isEmpty()) {
            getString(R.string.no_local_registrations)
        } else {
            getString(R.string.local_registrations, ids.joinToString(", "))
        }
    }

    private fun confirmClear() {
        val uniqueId = inputUniqueId.text.toString().trim()
        if (uniqueId.length != 11) {
            tvClearStatus.text = getString(R.string.err_unique_id)
            return
        }
        // Deleting from a live project has no undo — make the operator confirm, and say exactly
        // what will go.
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_confirm_title, uniqueId))
            .setMessage(getString(R.string.clear_confirm_body, uniqueId))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm_yes) { _, _ -> clear(uniqueId) }
            .show()
    }

    private fun clear(uniqueId: String) {
        progress.visibility = View.VISIBLE
        btnClear.isEnabled = false
        tvClearStatus.text = getString(R.string.clearing, uniqueId)

        lifecycleScope.launch {
            val summary = runCatching { scanner.purge(this@SettingsActivity, uniqueId) }
                .getOrElse { "failed: ${it.message}" }
            val historyRows = history.clear(uniqueId)
            progress.visibility = View.GONE
            btnClear.isEnabled = true
            tvClearStatus.text = getString(R.string.cleared, uniqueId, summary, historyRows)
            showRegisteredIds()
        }
    }
}
