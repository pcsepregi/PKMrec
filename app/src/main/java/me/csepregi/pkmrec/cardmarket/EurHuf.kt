package me.csepregi.pkmrec.cardmarket

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.diagnostics.ErrorLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * EUR → HUF rate for showing Cardmarket prices in forints: the European Central Bank's daily
 * reference rate, downloaded on app start (at most every [REFRESH_MS]) and kept on the phone.
 */
object EurHuf {

    const val ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

    private const val PREFS = "eur_huf"
    private const val KEY_RATE = "rate"
    private const val KEY_DATE = "date"
    private const val KEY_CHECKED = "checked"
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val STALE_DAYS = 7L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val HUF_RATE = Regex("""currency=['"]HUF['"]\s+rate=['"]([\d.]+)['"]""")
    private val DATE = Regex("""time=['"](\d{4}-\d{2}-\d{2})['"]""")

    /** The last downloaded rate (forints per euro), or null before the first download. */
    fun rate(context: Context): Double? =
        prefs(context).getFloat(KEY_RATE, 0f).takeIf { it > 0f }?.toDouble()

    /** The ECB date of [rate] ("2026-10-23"), or null before the first download. */
    fun rateDate(context: Context): String? = prefs(context).getString(KEY_DATE, null)?.takeIf { it.isNotEmpty() }

    /** A rate whose ECB date is more than [STALE_DAYS] before [today] (or unreadable) should show its date. */
    fun isOld(date: String?, today: LocalDate): Boolean {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return true
        return day.isBefore(today.minusDays(STALE_DAYS))
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val checked = prefs(appContext).getLong(KEY_CHECKED, 0L)
        if (rate(appContext) != null && System.currentTimeMillis() - checked < REFRESH_MS) return
        scope.launch {
            try {
                val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                val body = http.newCall(Request.Builder().url(ECB_URL).build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("ECB rates: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val (rate, date) = parse(body) ?: throw java.io.IOException("ECB rates: no HUF rate")
                prefs(appContext).edit()
                    .putFloat(KEY_RATE, rate.toFloat())
                    .putString(KEY_DATE, date)
                    .putLong(KEY_CHECKED, System.currentTimeMillis())
                    .apply()
            } catch (e: Exception) {
                ErrorLog.log("eur-huf rate", e)
            }
        }
    }

    /** HUF rate and its date from the ECB daily XML. */
    fun parse(xml: String): Pair<Double, String>? {
        val rate = HUF_RATE.find(xml)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return rate to DATE.find(xml)?.groupValues?.get(1).orEmpty()
    }

    /** "2 949 Ft": whole forints, non-breaking spaces between thousands and before "Ft". */
    fun formatHuf(euros: Double, rate: Double): String = formatForints((euros * rate).roundToLong())

    /** "12 345 Ft", "-1 200 Ft": whole forints with non-breaking spaces. */
    fun formatForints(forints: Long): String {
        val digits = kotlin.math.abs(forints).toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        return (if (forints < 0) "-" else "") + digits + "\u00A0Ft"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
