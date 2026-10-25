package me.csepregi.pkmrec.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgErrorKind

/** The Hungarian message for a failed TCG Collector request; the technical text only for unexpected errors. */
@Composable
fun tcgErrorText(error: TcgError): String = when (error.kind) {
    TcgErrorKind.NO_INTERNET -> stringResource(R.string.error_no_internet)
    TcgErrorKind.TIMEOUT -> stringResource(R.string.error_timeout)
    TcgErrorKind.TOO_MANY_REQUESTS -> stringResource(R.string.error_too_many_requests)
    TcgErrorKind.BLOCKED -> stringResource(R.string.error_blocked)
    TcgErrorKind.UNAVAILABLE -> stringResource(R.string.error_unavailable)
    TcgErrorKind.SITE_CHANGED -> stringResource(R.string.error_site_changed)
    TcgErrorKind.OTHER -> stringResource(R.string.error_other, error.detail)
}
