package com.fingerprintscanner

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fingerprintscanner.data.BiometricExport
import com.fingerprintscanner.data.ScanHistory
import com.fingerprintscanner.data.ScanRecord
import com.google.android.material.button.MaterialButton
import java.io.File

/** Every scan this terminal has performed, newest first, with CSV and artifact exports. */
class HistoryActivity : AppCompatActivity() {

    private val history by lazy { ScanHistory(this) }
    private val exporter by lazy { BiometricExport(this) }

    private lateinit var list: RecyclerView
    private lateinit var summary: AppCompatTextView
    private lateinit var empty: AppCompatTextView
    private lateinit var btnExport: MaterialButton
    private lateinit var btnExportAll: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        list = findViewById(R.id.list)
        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        btnExport = findViewById(R.id.btnExport)
        btnExportAll = findViewById(R.id.btnExportAll)

        list.layoutManager = LinearLayoutManager(this)
        btnExport.setOnClickListener { exportCsv() }
        btnExportAll.setOnClickListener { exportAllFiles() }
    }

    override fun onResume() {
        super.onResume()
        val records = history.all()
        list.adapter = Adapter(records)
        empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        btnExport.isEnabled = records.isNotEmpty()
        btnExportAll.isEnabled = exporter.registeredIds().isNotEmpty()

        val registrations = records.count { it.kind == KIND_REGISTRATION }
        val approved = records.count { it.outcome == "Approved" }
        val declined = records.count { it.kind == KIND_VERIFICATION && it.outcome != "Approved" }
        summary.text =
            getString(R.string.history_summary, records.size, registrations, approved, declined)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Detail
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Full detail for one row, plus a route to that ID's artifacts.
     *
     * The list rows are deliberately terse — three lines each — so everything the record
     * carries is shown here instead of being truncated on screen.
     */
    private fun showDetail(record: ScanRecord) {
        val artifacts = exporter.countFor(record.uniqueId)
        val body = buildString {
            appendLine("Type: ${record.kind}")
            appendLine("Outcome: ${record.outcome}")
            appendLine("Unique ID: ${record.uniqueId}")
            appendLine("Time: ${record.formattedTime()}")
            record.amount?.let { appendLine("Amount: %.2f".format(it)) }
            record.score?.let { appendLine("Match score: $it") }
            if (record.liveness.isNotBlank()) {
                appendLine("Liveness: ${record.liveness}")
                record.threshold?.takeIf { it > 0 }?.let { appendLine("Threshold: $it") }
            }
            if (record.quality.isNotBlank()) appendLine("Quality: ${record.quality}")
            if (record.detail.isNotBlank()) {
                appendLine()
                appendLine(record.detail)
            }
            appendLine()
            append(
                if (artifacts > 0) getString(R.string.detail_artifacts, artifacts)
                else getString(R.string.detail_no_artifacts)
            )
        }

        val dialog = AlertDialog.Builder(this, R.style.AlertStyle)
            .setTitle("${record.kind} · ${record.outcome}")
            .setMessage(body)
            .setNegativeButton(R.string.close, null)

        if (artifacts > 0) {
            dialog.setPositiveButton(R.string.export_files) { _, _ ->
                exportFilesFor(record.uniqueId)
            }
        }
        dialog.show()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Export
    // ──────────────────────────────────────────────────────────────────────────

    private fun exportCsv() {
        val file = history.exportCsv()
        if (file == null) {
            summary.text = getString(R.string.export_failed)
            return
        }
        // Shared through a FileProvider: handing out a file:// URI throws on Android 7+, and
        // the operator needs to get this off the terminal somehow.
        val uri = uriFor(file)
        if (uri == null) {
            summary.text = getString(R.string.export_saved, file.absolutePath)
            return
        }
        startChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            fallback = getString(R.string.export_saved, file.absolutePath),
        )
    }

    /** Single export: the .dat/.bmp/.jpg artifacts belonging to one ID. */
    private fun exportFilesFor(uniqueId: String) {
        val files = exporter.single(uniqueId)
        val uris = files.mapNotNull { uriFor(it) }
        if (uris.isEmpty()) {
            summary.text = getString(R.string.export_none, uniqueId)
            return
        }
        summary.text = getString(R.string.export_sharing, uris.size)
        // SEND_MULTIPLE keeps them as individual files rather than a zip, so an assessor does
        // not have to unpack an archive to reach a couple of templates.
        startChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_SUBJECT, "Fingerprint artifacts — $uniqueId")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    /** Batch export: every ID's artifacts as one zip. */
    private fun exportAllFiles() {
        val zip = exporter.batchZip()
        if (zip == null) {
            summary.text = getString(R.string.export_failed)
            return
        }
        val uri = uriFor(zip)
        if (uri == null) {
            summary.text = getString(R.string.export_saved, zip.absolutePath)
            return
        }
        summary.text = getString(R.string.export_zipped, zip.name)
        startChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun uriFor(file: File) = runCatching {
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }.getOrNull()

    private fun startChooser(intent: Intent, fallback: String = getString(R.string.export_failed)) {
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.export_csv))) }
            .onFailure { summary.text = fallback }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Adapter
    // ──────────────────────────────────────────────────────────────────────────

    private inner class Adapter(private val items: List<ScanRecord>) :
        RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val outcome: AppCompatTextView = view.findViewById(R.id.outcome)
            val time: AppCompatTextView = view.findViewById(R.id.time)
            val line2: AppCompatTextView = view.findViewById(R.id.line2)
            val line3: AppCompatTextView = view.findViewById(R.id.line3)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.outcome.text = "${r.kind} · ${r.outcome}"
            holder.outcome.setTextColor(
                ContextCompat.getColor(
                    this@HistoryActivity,
                    if (r.approved) R.color.settings_ok else R.color.settings_danger,
                )
            )
            holder.time.text = r.formattedTime()
            holder.line2.text = buildString {
                append("ID ${r.uniqueId}")
                r.amount?.let { append(" · %.2f".format(it)) }
                r.score?.let { append(" · match $it") }
            }
            holder.line3.text = listOfNotNull(
                r.liveness.takeIf { it.isNotBlank() }?.let { "liveness $it" },
                r.quality.takeIf { it.isNotBlank() }?.let { "quality $it" },
                r.detail.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            holder.line3.visibility = if (holder.line3.text.isBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { showDetail(r) }
        }
    }

    companion object {
        const val KIND_REGISTRATION = "Registration"
        const val KIND_VERIFICATION = "Verification"
    }
}
