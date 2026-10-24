package me.csepregi.pkmrec

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import me.csepregi.pkmrec.cardmarket.EurHuf
import me.csepregi.pkmrec.tcg.TcgSession
import me.csepregi.pkmrec.ui.CameraScreen
import me.csepregi.pkmrec.ui.CardmarketDebugScreen
import me.csepregi.pkmrec.ui.CurrentScan
import me.csepregi.pkmrec.ui.HomeScreen
import me.csepregi.pkmrec.ui.LoginScreen
import me.csepregi.pkmrec.ui.LookupScreen
import me.csepregi.pkmrec.ui.NeonBackground
import me.csepregi.pkmrec.ui.PkmrecTheme
import me.csepregi.pkmrec.ui.PokedexResultScreen
import me.csepregi.pkmrec.ui.PokedexSearchScreen
import me.csepregi.pkmrec.ui.PokemonNamesScreen
import me.csepregi.pkmrec.ui.SealedCatalogScreen
import me.csepregi.pkmrec.ui.SealedItemScreen
import me.csepregi.pkmrec.ui.SettingsScreen
import me.csepregi.pkmrec.ui.UpdateDialog
import me.csepregi.pkmrec.update.UpdateManager
import me.csepregi.pkmrec.update.UpdateState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PkmrecTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    NeonBackground()
                    val nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = Routes.HOME,
                        // Screens are see-through over the shared background, so they must not cross-fade.
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onLogin = { nav.navigate(Routes.LOGIN) },
                                onRecognize = { nav.navigate(Routes.CAMERA) },
                                onPokedex = { nav.navigate(Routes.POKEDEX) },
                                onSealed = { nav.navigate(Routes.SEALED) },
                            )
                        }
                        composable(Routes.SEALED) {
                            SealedCatalogScreen(
                                onBack = { nav.popBackStack() },
                                onOpenItem = { nav.navigate(Routes.sealedItem(it)) },
                            )
                        }
                        composable(
                            Routes.SEALED_ITEM,
                            arguments = listOf(navArgument("id") { type = NavType.IntType }),
                        ) { entry ->
                            SealedItemScreen(id = entry.arguments?.getInt("id") ?: 0, onBack = { nav.popBackStack() })
                        }
                        composable(Routes.POKEDEX) {
                            PokedexSearchScreen(
                                onBack = { nav.popBackStack() },
                                onPick = { nav.navigate(Routes.pokedexResult(it.number)) },
                            )
                        }
                        composable(
                            Routes.POKEDEX_RESULT,
                            arguments = listOf(navArgument("number") { type = NavType.IntType }),
                        ) { entry ->
                            PokedexResultScreen(
                                number = entry.arguments?.getInt("number") ?: 0,
                                onBack = { nav.popBackStack() },
                                onLogin = { nav.navigate(Routes.LOGIN) },
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
                                onCardmarketDebug = { nav.navigate(Routes.CARDMARKET_DEBUG) },
                                onPokemonNames = { nav.navigate(Routes.POKEMON_NAMES) },
                            )
                        }
                        composable(Routes.CARDMARKET_DEBUG) {
                            CardmarketDebugScreen(onBack = { nav.popBackStack() })
                        }
                        composable(Routes.POKEMON_NAMES) {
                            PokemonNamesScreen(onBack = { nav.popBackStack() })
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
        EurHuf.refresh(this)
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
    const val POKEDEX = "pokedex"
    const val POKEDEX_RESULT = "pokedex/{number}"
    const val CARDMARKET_DEBUG = "cardmarket-debug"
    const val POKEMON_NAMES = "pokemon-names"
    const val SEALED = "sealed"
    const val SEALED_ITEM = "sealed/{id}"

    fun pokedexResult(number: Int) = "pokedex/$number"

    fun sealedItem(id: Int) = "sealed/$id"
}
