package com.fingerprintscanner.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing that happened on this terminal. */
class ScanRecord(
    val at: Long,
    /** Registration | Verification */
    val kind: String,
    val uniqueId: String,
    /** Registered | Approved | Declined | Cancelled | Failed */
    val outcome: String,
    val detail: String,
    val amount: Double?,
    /** Per-reader liveness, e.g. `#0=41230 #1=39100`. Blank when the SDK reported none. */
    val liveness: String,
    /** Per-reader template quality, e.g. `#0=78 #1=81`. */
    val quality: String,
    /** Anti-spoof cutoff the readers were running at, so liveness can be judged. */
    val threshold: Int?,
    val score: Int?,
) {
    fun toJson(): String = JSONObject().apply {
        put("at", at); put("kind", kind); put("uniqueId", uniqueId); put("outcome", outcome)
        put("detail", detail); put("amount", amount ?: JSONObject.NULL)
        put("liveness", liveness); put("quality", quality)
        put("threshold", threshold ?: JSONObject.NULL)
        put("score", score ?: JSONObject.NULL)
    }.toString()

    val approved: Boolean get() = outcome == "Approved" || outcome == "Registered"

    fun formattedTime(): String = TIME.format(Date(at))

    companion object {
        private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun fromJson(line: String): ScanRecord? = runCatching {
            val o = JSONObject(line)
            ScanRecord(
                at = o.getLong("at"),
                kind = o.optString("kind"),
                uniqueId = o.optString("uniqueId"),
                outcome = o.optString("outcome"),
                detail = o.optString("detail"),
                amount = if (o.isNull("amount")) null else o.getDouble("amount"),
                liveness = o.optString("liveness"),
                quality = o.optString("quality"),
                threshold = if (o.isNull("threshold")) null else o.getInt("threshold"),
                score = if (o.isNull("score")) null else o.getInt("score"),
            )
        }.getOrNull()
    }
}

/**
 * A local log of every scan this terminal has performed.
 *
 * Deliberately local and append-only rather than read back from Firestore: it must show
 * declines and cancelled attempts, which are never written to the cloud, and it has to work
 * with no connectivity. One JSON object per line, so a partially written tail can never cost
 * more than the last entry.
 */
class ScanHistory(private val context: Context) {

    private val file get() = File(context.filesDir, FILE)

    fun record(record: ScanRecord) {
        runCatching { file.appendText(record.toJson() + "\n") }
            .onFailure { Log.w(TAG, "could not write history: ${it.message}") }
    }

    /** Newest first. */
    fun all(): List<ScanRecord> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { ScanRecord.fromJson(it) }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    /** Drops every entry for [uniqueId]. Returns how many were removed. */
    fun clear(uniqueId: String): Int = runCatching {
        val existing = all()
        val kept = existing.filterNot { it.uniqueId == uniqueId }
        file.writeText(kept.sortedBy { it.at }.joinToString("") { it.toJson() + "\n" })
        existing.size - kept.size
    }.getOrDefault(0)

    /**
     * Writes a CSV to the app's external files directory and returns it, for sharing.
     *
     * External rather than internal because only that directory is exposed through the
     * FileProvider — see `res/xml/file_paths.xml`.
     */
    fun exportCsv(): File? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "scan-history-$stamp.csv")
        out.bufferedWriter().use { w ->
            w.write("timestamp,type,unique_id,outcome,amount,match_score,liveness,threshold,quality,detail\n")
            all().forEach { r ->
                w.write(
                    listOf(
                        r.formattedTime(), r.kind, r.uniqueId, r.outcome,
                        r.amount?.toString() ?: "", r.score?.toString() ?: "",
                        r.liveness, r.threshold?.toString() ?: "", r.quality, r.detail,
                    ).joinToString(",") { csv(it) } + "\n"
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "export failed: ${it.message}") }.getOrNull()

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private companion object {
        const val TAG = "ScanHistory"
        const val FILE = "scan-history.jsonl"
    }
}
