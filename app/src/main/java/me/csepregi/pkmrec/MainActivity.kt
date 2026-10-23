package me.csepregi.pkmrec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.csepregi.pkmrec.tcg.TcgSession
import me.csepregi.pkmrec.ui.CameraScreen
import me.csepregi.pkmrec.ui.CurrentScan
import me.csepregi.pkmrec.ui.HomeScreen
import me.csepregi.pkmrec.ui.LoginScreen
import me.csepregi.pkmrec.ui.LookupScreen
import me.csepregi.pkmrec.ui.PkmrecTheme
import me.csepregi.pkmrec.ui.SettingsScreen
import me.csepregi.pkmrec.ui.UpdateDialog
import me.csepregi.pkmrec.update.UpdateManager
import me.csepregi.pkmrec.update.UpdateState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PkmrecTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = Routes.HOME) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onLogin = { nav.navigate(Routes.LOGIN) },
                                onRecognize = { nav.navigate(Routes.CAMERA) },
                            )
                        }
                        composable(Routes.CAMERA) {
                            CameraScreen(
                                onBack = { nav.popBackStack() },
                                onRecognized = { reading ->
                                    CurrentScan.reading = reading
                                    nav.navigate(Routes.LOOKUP)
                                },
                            )
                        }
                        composable(Routes.LOOKUP) {
                            LookupScreen(
                                onRescan = { nav.popBackStack() },
                                onHome = { nav.popBackStack(Routes.HOME, inclusive = false) },
                                onLogin = { nav.navigate(Routes.LOGIN) },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onLogin = { nav.navigate(Routes.LOGIN) },
                            )
                        }
                        composable(Routes.LOGIN) {
                            LoginScreen(onDone = { nav.popBackStack() })
                        }
                    }
                    UpdateDialog()
                }
            }
        }
        UpdateManager.checkOnStart(this)
        TcgSession.refresh(this)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the "install unknown apps" setting: continue the update if it was granted.
        val state = UpdateManager.state.value
        if (state is UpdateState.NeedsInstallPermission && packageManager.canRequestPackageInstalls()) {
            UpdateManager.startUpdate(this, state.release)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val CAMERA = "camera"
    const val LOOKUP = "lookup"
}
