package me.csepregi.pkmrec.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.BuildConfig
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** What the update UI should show. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
    data class CheckFailed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer version, downloads the APK, verifies its SHA-256
 * and installs it with the PackageInstaller session API.
 */
object UpdateManager {

    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private val AUTO_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val client = GitHubReleaseClient(BuildConfig.GITHUB_REPO, http)

    val installedVersion: AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME)

    /** Automatic check on app start: throttled, silent when nothing is found or the network fails. */
    fun checkOnStart(context: Context) {
        // Debug builds have a different package name and cannot be updated by a release APK.
        if (BuildConfig.DEBUG) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        if (_state.value != UpdateState.Idle) return
        scope.launch {
            runCatching { client.fetchLatest() }
                .onSuccess { release ->
                    prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                    if (release != null && isNewer(release)) {
                        _state.value = UpdateState.Available(release)
                    }
                }
            // Errors are ignored on the automatic check; the next start tries again.
        }
    }

    /** Manual check from the settings screen: always reports a result. */
    fun checkNow(context: Context) {
        _state.value = UpdateState.Checking
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            runCatching { client.fetchLatest() }
                .onSuccess { release ->
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    _state.value = if (release != null && isNewer(release)) {
                        UpdateState.Available(release)
                    } else {
                        UpdateState.UpToDate
                    }
                }
                .onFailure { _state.value = UpdateState.CheckFailed(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Starts download and installation of [release] (after the user tapped "Frissítés"). */
    fun startUpdate(context: Context, release: ReleaseInfo) {
        val appContext = context.applicationContext
        if (BuildConfig.DEBUG) {
            _state.value = UpdateState.Failed("teszt (debug) verzió nem frissíthető így")
            return
        }
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission(release)
            return
        }
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            try {
                val dir = File(appContext.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val apk = File(dir, release.apkName)
                client.download(release.apkUrl, apk) { progress ->
                    _state.value = UpdateState.Downloading(release, progress)
                }

                _state.value = UpdateState.Verifying
                val shaUrl = release.sha256Url ?: error("hiányzik az ellenőrzőösszeg a kiadásból")
                val expected = GitHubReleaseClient.parseSha256File(client.downloadText(shaUrl))
                    ?: error("hibás ellenőrzőösszeg-fájl")
                val actual = GitHubReleaseClient.sha256Of(apk)
                if (expected != actual) error("a letöltött fájl sérült (SHA-256 eltérés)")

                _state.value = UpdateState.Installing
                install(appContext, apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    /** Called by [InstallResultReceiver] when installation failed or was cancelled. */
    internal fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    /** Called when the system confirmation dialog is shown. */
    internal fun onWaitingForUser() {
        _state.value = UpdateState.Installing
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun isNewer(release: ReleaseInfo): Boolean {
        val installed = installedVersion ?: return true
        return release.version > installed
    }
}
