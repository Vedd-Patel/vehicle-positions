package org.onebusaway.vehiclepositions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import org.onebusaway.vehiclepositions.service.LocationForegroundService
import org.onebusaway.vehiclepositions.ui.activetracking.ActiveTrackingScreen
import org.onebusaway.vehiclepositions.ui.home.HomeScreen
import org.onebusaway.vehiclepositions.ui.home.HomeViewModel
import org.onebusaway.vehiclepositions.ui.theme.VehiclePositionDriverTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var stopShiftReceiver: BroadcastReceiver? = null
    private var homeViewModelRef: HomeViewModel? = null
    private var navControllerRef: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerStopShiftReceiver()

        setContent {
            VehiclePositionDriverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        onHomeViewModelReady = { vm -> homeViewModelRef = vm },
                        onNavControllerReady = { nav -> navControllerRef = nav }
                    )
                }
            }
        }
    }

    private fun registerStopShiftReceiver() {
        stopShiftReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LocationForegroundService.BROADCAST_STOP_SHIFT) {
                    android.util.Log.d("MainActivity", "Stop shift broadcast received")

                    homeViewModelRef?.onShiftStopped()

                    // Pop back to home if the user is on the active tracking screen
                    navControllerRef?.let { nav ->
                        if (nav.currentDestination?.route?.startsWith("active_tracking") == true) {
                            nav.popBackStack()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(LocationForegroundService.BROADCAST_STOP_SHIFT)
        registerReceiver(stopShiftReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopShiftReceiver?.let { unregisterReceiver(it) }
    }
}

@Composable
fun AppNavigation(
    onHomeViewModelReady: (HomeViewModel) -> Unit = {},
    onNavControllerReady: (NavController) -> Unit = {}
) {
    val navController = rememberNavController()

    // Expose the nav controller to MainActivity so the broadcast receiver can pop the back stack
    LaunchedEffect(navController) {
        onNavControllerReady(navController)
    }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") { backStackEntry ->
            val homeViewModel: HomeViewModel = hiltViewModel(backStackEntry)
            onHomeViewModelReady(homeViewModel)
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToActiveTracking = { vehicleId ->
                    navController.navigate("active_tracking/$vehicleId") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = "active_tracking/{vehicleId}",
            arguments = listOf(
                navArgument("vehicleId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val vehicleId = backStackEntry.arguments?.getString("vehicleId") ?: ""

            // Reuse the existing HomeViewModel instance from the back stack rather than
            // creating a new one, so onShiftStopped() updates the correct state
            val homeBackStackEntry = remember(navController.currentBackStackEntry) {
                navController.getBackStackEntry("home")
            }
            val homeViewModel: HomeViewModel = hiltViewModel(homeBackStackEntry)

            ActiveTrackingScreen(
                vehicleId = vehicleId,
                onShiftStopped = {
                    homeViewModel.onShiftStopped()
                    navController.popBackStack()
                }
            )
        }
    }
}