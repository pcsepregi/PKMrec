package me.csepregi.pkmrec.diagnostics

/** Text format of the error log, kept free of Android classes so it can be unit tested. */
internal object LogFormat {

    private const val MAX_FRAMES = 8
    private const val MAX_CAUSES = 3
    private val ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \[""", RegexOption.MULTILINE)

    /** One entry: a header line, then the exception with its first stack frames and causes, then a blank line. */
    fun entry(time: String, where: String, message: String?, e: Throwable?): String = buildString {
        append("$time [$where]")
        message?.let { append(" $it") }
        appendLine()
        var cause = e
        var depth = 0
        while (cause != null && depth <= MAX_CAUSES) {
            append(if (depth == 0) "  " else "  Caused by: ")
            appendLine(cause.toString())
            cause.stackTrace.take(MAX_FRAMES).forEach { appendLine("    at $it") }
            if (cause.stackTrace.size > MAX_FRAMES) appendLine("    … ${cause.stackTrace.size - MAX_FRAMES} more")
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        appendLine()
    }

    fun countEntries(log: String): Int = ENTRY_START.findAll(log).count()

    /** Keeps about the last [keepChars] characters, starting at an entry boundary. */
    fun trim(log: String, keepChars: Int): String {
        if (log.length <= keepChars) return log
        val cut = log.length - keepChars
        val start = ENTRY_START.find(log, cut)?.range?.first ?: return ""
        return log.substring(start)
    }
}
