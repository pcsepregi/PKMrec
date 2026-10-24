package me.csepregi.pkmrec.diagnostics

import android.content.Context
import android.os.Build
import me.csepregi.pkmrec.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Error log kept only on the phone, in the app's private storage: nothing is sent anywhere.
 * The settings screen can copy it to the clipboard, so a problem can be reported by pasting it.
 */
object ErrorLog {

    private const val FILE_NAME = "error-log.txt"

    /** Upper limit of the log file; above it the oldest half is dropped. Small enough for the clipboard. */
    private const val MAX_CHARS = 64 * 1024

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile private var file: File? = null

    /** Called once from [me.csepregi.pkmrec.PkmrecApp]; also logs crashes before the app closes. */
    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { log("crash in ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Adds an entry; [where] names the part of the app, e.g. "lookup" or "camera". Never throws. */
    fun log(where: String, e: Throwable? = null, message: String? = null) {
        val target = file ?: return
        val entry = LogFormat.entry(LocalDateTime.now().format(TIME), where, message, e)
        synchronized(this) {
            runCatching {
                target.appendText(entry)
                if (target.length() > MAX_CHARS) target.writeText(LogFormat.trim(target.readText(), MAX_CHARS / 2))
            }
        }
    }

    fun entryCount(): Int = synchronized(this) { LogFormat.countEntries(readRaw()) }

    /** The whole log with the app version and phone model on top, ready to paste into a bug report. */
    fun report(): String {
        val body = synchronized(this) { readRaw() }
        return buildString {
            appendLine("PKMrec ${BuildConfig.VERSION_NAME} error log")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(body.ifEmpty { "(empty)\n" })
        }
    }

    fun clear() {
        synchronized(this) { runCatching { file?.delete() } }
    }

    private fun readRaw(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
}
