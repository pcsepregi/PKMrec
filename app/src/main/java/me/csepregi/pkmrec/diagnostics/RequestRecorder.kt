package me.csepregi.pkmrec.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Debug tool: the requests TCG Collector's own page sends while the user clicks around in the
 * in-app browser (e.g. adding a card to a list), so the app can later send the same request.
 * Kept in memory only; secrets are masked before anything is stored.
 */
object RequestRecorder {

    const val MAX_ENTRIES = 50

    data class Entry(
        val time: String,
        val kind: String,
        val method: String,
        val url: String,
        val headers: String,
        val body: String,
        val status: String,
        val response: String,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun record(kind: String, method: String, url: String, headers: String, body: String, status: String, response: String) {
        val entry = Entry(
            time = LocalTime.now().format(TIME),
            kind = kind,
            method = method.uppercase(),
            url = url,
            headers = RequestFormat.mask(headers),
            body = RequestFormat.cut(RequestFormat.mask(body), 4000),
            status = status,
            response = RequestFormat.cut(RequestFormat.mask(response), 1500),
        )
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun report(): String = _entries.value.joinToString("\n") { RequestFormat.entry(it) }
}

/** Text format and masking of recorded requests, free of Android classes so it can be unit tested. */
internal object RequestFormat {

    /** Values of keys that look secret: tokens, passwords, cookies, e-mail addresses. */
    private val SECRET_KEY = Regex(
        """("?[\w.-]*(?:csrf|token|password|passwd|secret|cookie|session|email)[\w.-]*"?\s*[:=]\s*)("[^"]*"|[^\s,;&}\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    fun mask(text: String): String =
        EMAIL.replace(SECRET_KEY.replace(text) { it.groupValues[1] + "***" }, "***@***")

    fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + " … (${text.length} chars)"

    fun entry(e: RequestRecorder.Entry): String = buildString {
        appendLine("${e.time} [${e.kind}] ${e.method} ${e.url} -> ${e.status.ifEmpty { "?" }}")
        if (e.headers.isNotBlank()) appendLine("  headers: ${e.headers}")
        if (e.body.isNotBlank()) appendLine("  body: ${e.body}")
        if (e.response.isNotBlank()) appendLine("  response: ${e.response}")
    }
}
