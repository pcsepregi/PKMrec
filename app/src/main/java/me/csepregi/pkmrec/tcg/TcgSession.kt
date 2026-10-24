package me.csepregi.pkmrec.tcg

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.diagnostics.ErrorLog

sealed interface SessionState {
    /** Not checked yet in this app run. */
    data object Unknown : SessionState
    data object Checking : SessionState
    data class LoggedIn(val user: TcgUser) : SessionState
    data object LoggedOut : SessionState

    /** The check failed (e.g. no network); [lastUser] is the user from the last successful check, if any. */
    data class Error(val error: TcgError, val lastUser: TcgUser?) : SessionState
}

/**
 * Keeps track of the TCG Collector login. The password is only ever typed into TCG Collector's
 * own login page in a WebView; the app keeps nothing but the session cookie (in Android's
 * CookieManager) and the display name of the last logged-in user.
 */
object TcgSession {

    private const val PREFS = "tcg_session"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_PREMIUM = "user_premium"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile private var client: TcgClient? = null

    fun client(context: Context): TcgClient =
        client ?: synchronized(this) {
            client ?: TcgClient(WebSettings.getDefaultUserAgent(context.applicationContext)).also { client = it }
        }

    /** Checks the session in the background by loading a page that requires login. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        _state.value = SessionState.Checking
        scope.launch {
            _state.value = try {
                val page = client(appContext).fetchPage(Tcg.SESSION_CHECK_URL)
                val user = TcgUser.fromAppState(page.requireAppState())
                if (user != null) {
                    saveUser(appContext, user)
                    SessionState.LoggedIn(user)
                } else {
                    clearUser(appContext)
                    SessionState.LoggedOut
                }
            } catch (e: Exception) {
                ErrorLog.log("session check", e)
                SessionState.Error(TcgError.of(e), loadUser(appContext))
            }
        }
    }

    /** Called by the login screen once the WebView shows a logged-in page. */
    fun onLoggedIn(context: Context, user: TcgUser) {
        CookieManager.getInstance().flush()
        saveUser(context.applicationContext, user)
        _state.value = SessionState.LoggedIn(user)
    }

    fun logout(context: Context) {
        val appContext = context.applicationContext
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        clearUser(appContext)
        _state.value = SessionState.LoggedOut
    }

    private fun saveUser(context: Context, user: TcgUser) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_USER_ID, user.id)
            .putString(KEY_USER_NAME, user.displayName)
            .putBoolean(KEY_USER_PREMIUM, user.isPremium)
            .apply()
    }

    private fun loadUser(context: Context): TcgUser? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_USER_ID)) return null
        return TcgUser(
            id = prefs.getInt(KEY_USER_ID, 0),
            displayName = prefs.getString(KEY_USER_NAME, "").orEmpty(),
            isPremium = prefs.getBoolean(KEY_USER_PREMIUM, false),
        )
    }

    private fun clearUser(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
