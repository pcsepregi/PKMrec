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
import me.csepregi.pkmrec.ui.HomeScreen
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
                            HomeScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }
                    }
                    UpdateDialog()
                }
            }
        }
        UpdateManager.checkOnStart(this)
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
}
