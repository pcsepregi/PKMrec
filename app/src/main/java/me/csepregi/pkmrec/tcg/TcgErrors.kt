package me.csepregi.pkmrec.tcg

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** TCG Collector answered with an error status (e.g. 429, 503). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** TCG Collector shows a maintenance page instead of the requested page. */
class MaintenanceException : IOException("maintenance page")

/** A TCG Collector page no longer looks the way the app expects: the site has changed. */
class PageFormatException(message: String) : IOException(message)

/** What went wrong while talking to TCG Collector, as far as the user is concerned. */
enum class TcgErrorKind {
    NO_INTERNET,
    TIMEOUT,
    TOO_MANY_REQUESTS,
    UNAVAILABLE,
    SITE_CHANGED,
    OTHER,
}

/** A failed TCG Collector request: [kind] picks the message shown, [detail] is the technical text for the error log. */
data class TcgError(val kind: TcgErrorKind, val detail: String) {
    companion object {
        fun of(e: Throwable): TcgError = TcgError(kindOf(e), e.message ?: e.javaClass.simpleName)

        internal fun kindOf(e: Throwable): TcgErrorKind {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is PageFormatException -> return TcgErrorKind.SITE_CHANGED
                    is MaintenanceException -> return TcgErrorKind.UNAVAILABLE
                    is HttpStatusException -> return when (cause.code) {
                        429 -> TcgErrorKind.TOO_MANY_REQUESTS
                        in 500..599 -> TcgErrorKind.UNAVAILABLE
                        else -> TcgErrorKind.OTHER
                    }
                    is UnknownHostException, is ConnectException, is NoRouteToHostException -> return TcgErrorKind.NO_INTERNET
                    // SocketTimeoutException is an InterruptedIOException; OkHttp's call timeout is a plain one.
                    is SocketTimeoutException, is InterruptedIOException -> return TcgErrorKind.TIMEOUT
                }
                cause = cause.cause?.takeIf { it !== cause }
            }
            return TcgErrorKind.OTHER
        }
    }
}
