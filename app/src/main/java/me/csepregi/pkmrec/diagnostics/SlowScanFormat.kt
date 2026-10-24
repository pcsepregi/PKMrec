package me.csepregi.pkmrec.diagnostics

import me.csepregi.pkmrec.recognition.SlowScanTracker.SlowScan
import java.util.Locale

/** Text format of the slow recognition log, kept free of Android classes so it can be unit tested. */
internal object SlowScanFormat {

    private const val ENTRY_MARK = "### "
    private val ENTRY_START = Regex("^$ENTRY_MARK", RegexOption.MULTILINE)

    /** One entry: a header line, what was read during the attempt, the last frame's OCR report, then a blank line. */
    fun entry(time: String, scan: SlowScan): String = buildString {
        append(ENTRY_MARK).append(time)
        append(" | ").append(String.format(Locale.ROOT, "%.1f s", scan.durationMs / 1000.0))
        append(" | ").append(scan.outcome.label)
        scan.result?.let { append(" ").append(it.display) }
        appendLine()
        appendLine("markers: " + scan.lastMarkers.joinToString(", ") { it.label }.ifEmpty { "-" })
        appendLine(
            "readings: " + scan.readings.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} x${it.value}" }
                .ifEmpty { "-" },
        )
        append(scan.lastFrame.trimEnd()).appendLine()
        appendLine()
    }

    fun countEntries(log: String): Int = ENTRY_START.findAll(log).count()

    /** Keeps only the newest [maxEntries] entries. */
    fun keepLast(log: String, maxEntries: Int): String {
        val starts = ENTRY_START.findAll(log).map { it.range.first }.toList()
        if (starts.size <= maxEntries) return log
        return log.substring(starts[starts.size - maxEntries])
    }
}
