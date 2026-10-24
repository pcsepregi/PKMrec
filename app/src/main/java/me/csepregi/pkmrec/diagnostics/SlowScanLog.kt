package me.csepregi.pkmrec.diagnostics

import android.content.Context
import android.os.Build
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.recognition.SlowScanTracker.SlowScan
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Debug mode: recognitions that took longer than 5 seconds with a card in view, with the OCR
 * report of the last frame, kept only on the phone (the newest [MAX_ENTRIES]). The settings
 * screen switches it on, copies it to the clipboard and clears it.
 */
object SlowScanLog {

    const val MAX_ENTRIES = 50

    private const val FILE_NAME = "slow-scans.txt"
    private const val PREFS = "debug"
    private const val KEY_ENABLED = "debug_mode"

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Adds an entry, dropping the oldest above [MAX_ENTRIES]. Never throws. */
    fun log(context: Context, scan: SlowScan) {
        val target = file(context)
        val entry = SlowScanFormat.entry(LocalDateTime.now().format(TIME), scan)
        synchronized(this) {
            runCatching {
                target.appendText(entry)
                val all = target.readText()
                if (SlowScanFormat.countEntries(all) > MAX_ENTRIES) target.writeText(SlowScanFormat.keepLast(all, MAX_ENTRIES))
            }.onFailure { ErrorLog.log("slow scan log", it) }
        }
    }

    fun entryCount(context: Context): Int = synchronized(this) { SlowScanFormat.countEntries(readRaw(context)) }

    /** All entries with the app version and phone model on top, ready to paste. */
    fun report(context: Context): String {
        val body = synchronized(this) { readRaw(context) }
        return buildString {
            appendLine("PKMrec ${BuildConfig.VERSION_NAME} slow recognitions")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(body.ifEmpty { "(empty)\n" })
        }
    }

    fun clear(context: Context) {
        synchronized(this) { runCatching { file(context).delete() } }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun readRaw(context: Context): String =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
}
