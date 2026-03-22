package org.onebusaway.vehiclepositions.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import org.onebusaway.vehiclepositions.ui.vehiclesetup.VehicleSetupBottomSheet
import org.onebusaway.vehiclepositions.util.LocationStateHolder

private val ObaGreen = Color(0xFF6BAA39)
private val AGENCY_DEFAULT_LOCATION = LatLng(-1.2921, 36.8219)
private const val AGENCY_DEFAULT_ZOOM = 12f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToActiveTracking: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeVehicleId by viewModel.activeVehicleId.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    var showVehicleSetup by remember { mutableStateOf(false) }
    var hasNavigatedToTracking by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // shouldShowRationale cannot be read inside the permission callback's own init lambda,
    // so we toggle this flag to trigger a separate LaunchedEffect after each denial
    var checkRationaleFlag by remember { mutableStateOf(false) }

    // ── POST_NOTIFICATIONS (Android 13+) ─────────────────────────────────────
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS)
    } else null

    LaunchedEffect(Unit) {
        notificationPermission?.launchPermissionRequest()
    }

    // ── Fine location ─────────────────────────────────────────────────────────
    val fineLocationPermission = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            val locationManager = context.getSystemService(
                android.content.Context.LOCATION_SERVICE
            ) as LocationManager
            val isGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!isGpsOn) viewModel.onFineLocationGranted()
            else viewModel.onGpsConfirmed()
        } else {
            checkRationaleFlag = !checkRationaleFlag
        }
    }

    // Runs after each denial — fineLocationPermission is safely in scope here
    LaunchedEffect(checkRationaleFlag) {
        if (!fineLocationPermission.allPermissionsGranted) {
            val shouldShowRationale = fineLocationPermission.permissions
                .firstOrNull { it.permission == Manifest.permission.ACCESS_FINE_LOCATION }
                ?.status?.shouldShowRationale ?: false
            viewModel.onFineLocationDenied(shouldShowRationale)
        }
    }

    // ── Background location (Android 11+) ────────────────────────────────────
    val backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberPermissionState(
            permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) { granted ->
            if (granted) viewModel.onPermissionsGranted()
            else viewModel.onFineLocationGrantedButBackgroundDenied()
        }
    } else null

    // ── Request permissions on launch ─────────────────────────────────────────
    LaunchedEffect(Unit) {
        when {
            !fineLocationPermission.allPermissionsGranted ->
                fineLocationPermission.launchMultiplePermissionRequest()
            else -> {
                val locationManager = context.getSystemService(
                    android.content.Context.LOCATION_SERVICE
                ) as LocationManager
                val isGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                when {
                    !isGpsOn -> viewModel.onFineLocationGranted()
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            backgroundLocationPermission?.status?.isGranted == false ->
                        viewModel.onFineLocationGrantedButBackgroundDenied()
                    else -> viewModel.onPermissionsGranted()
                }
            }
        }
    }

    // ── Resume prompt ─────────────────────────────────────────────────────────
    if (uiState is HomeUiState.ResumePrompt && activeVehicleId != null) {
        ResumeShiftDialog(
            vehicleId = activeVehicleId!!,
            onResume = {
                viewModel.onResumeShift()
                onNavigateToActiveTracking(activeVehicleId!!)
            },
            onEnd = { viewModel.onEndPreviousShift() }
        )
    }

    // ── Navigate to active tracking ───────────────────────────────────────────
    LaunchedEffect(uiState) {
        if (uiState is HomeUiState.ActiveTracking &&
            activeVehicleId != null &&
            !hasNavigatedToTracking
        ) {
            hasNavigatedToTracking = true
            onNavigateToActiveTracking(activeVehicleId!!)
        }
        if (uiState is HomeUiState.EmptyMap) {
            hasNavigatedToTracking = false
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (permissionState is PermissionState.ShowFineLocationRationale) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Location Permission Required") },
            text = {
                Text(
                    "This app needs your location to report your vehicle's " +
                            "position to your transit agency in real time. " +
                            "Please grant location permission to continue."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fineLocationPermission.launchMultiplePermissionRequest()
                }) { Text("Grant Permission", color = ObaGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onFineLocationDenied(false)
                }) { Text("Not Now") }
            }
        )
    }

    if (permissionState is PermissionState.PermanentlyDenied) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Location Permission Denied") },
            text = {
                Text(
                    "Location permission has been permanently denied. " +
                            "Please go to Settings and enable location permission " +
                            "for this app to use all features."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) { Text("Open Settings", color = ObaGreen) }
            },
            dismissButton = {
                TextButton(onClick = {}) { Text("Cancel") }
            }
        )
    }

    if (permissionState is PermissionState.GpsDisabled) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Enable GPS") },
            text = {
                Text(
                    "Your GPS is turned off. This app needs GPS to track " +
                            "your vehicle location in real time.\n\n" +
                            "Please enable Location in Settings and come back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text("Enable GPS", color = ObaGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val locationManager = context.getSystemService(
                        android.content.Context.LOCATION_SERVICE
                    ) as LocationManager
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        viewModel.onGpsConfirmed()
                    }
                }) { Text("I've enabled it") }
            }
        )
    }

    // ── Background location ───────────────────────────────────────────────────
    if (permissionState is PermissionState.ShowBackgroundRationale) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Background Location Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "To keep tracking your vehicle while the screen is " +
                                "locked or the app is in the background, please " +
                                "allow location access all the time."
                    )
                    // Step-by-step instructions so the user knows exactly what to tap
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DialogStepRow(number = "1", text = "Tap \"Open Settings\" below")
                        DialogStepRow(number = "2", text = "Go to Permissions -> Location")
                        DialogStepRow(number = "3", text = "Select \"Allow all the time\"")
                        DialogStepRow(number = "4", text = "Come back and tap continue")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                }) { Text("Open Settings", color = ObaGreen) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        backgroundLocationPermission?.status?.isGranted == true
                    } else true
                    if (isGranted) viewModel.onPermissionsGranted()
                }) { Text("I've granted it") }
            }
        )
    }

    // ── Main Scaffold ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transit Driver") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            // Background rationale is a dialog, so the FAB is always visible
            FloatingActionButton(
                onClick = {
                    when {
                        !fineLocationPermission.allPermissionsGranted ->
                            fineLocationPermission.launchMultiplePermissionRequest()
                        else -> {
                            val locationManager = context.getSystemService(
                                android.content.Context.LOCATION_SERVICE
                            ) as LocationManager
                            when {
                                !locationManager.isProviderEnabled(
                                    LocationManager.GPS_PROVIDER
                                ) -> viewModel.onFineLocationGranted()

                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                        backgroundLocationPermission?.status?.isGranted == false ->
                                    viewModel.onFineLocationGrantedButBackgroundDenied()

                                else -> showVehicleSetup = true
                            }
                        }
                    }
                },
                containerColor = ObaGreen,
                contentColor = Color.White
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_input_add),
                    contentDescription = "Start Shift"
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map is always rendered behind — dialogs overlay on top
            GrayscaleMapWithLocation(
                isLocationGranted = fineLocationPermission.allPermissionsGranted,
                locationStateHolder = viewModel.locationStateHolder
            )
        }
    }

    // ── Vehicle Setup Bottom Sheet ────────────────────────────────────────────
    if (showVehicleSetup) {
        VehicleSetupBottomSheet(
            onDismiss = { showVehicleSetup = false },
            onShiftStarted = { vehicleId ->
                showVehicleSetup = false
                viewModel.onShiftStarted(vehicleId)
            }
        )
    }
}

// ── Step row used inside the background location dialog ──────────────────────
@Composable
private fun DialogStepRow(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(color = ObaGreen, shape = RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}

// ── Grayscale map with location tracking ─────────────────────────────────────
@SuppressLint("MissingPermission")
@Composable
fun GrayscaleMapWithLocation(
    isLocationGranted: Boolean,
    locationStateHolder: LocationStateHolder
) {
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()
    var cameraInitialized by remember { mutableStateOf(false) }

    // Fall back to agency default if permission is not yet granted
    LaunchedEffect(isLocationGranted) {
        if (!isLocationGranted && !cameraInitialized) {
            cameraInitialized = true
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(AGENCY_DEFAULT_LOCATION, AGENCY_DEFAULT_ZOOM)
            )
        }
    }

    LaunchedEffect(isLocationGranted) {
        if (!isLocationGranted || cameraInitialized) return@LaunchedEffect
        val cached = locationStateHolder.lastLocation.value
        if (cached != null) {
            cameraInitialized = true
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(LatLng(cached.latitude, cached.longitude), 15f)
            )
            return@LaunchedEffect
        }
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && !cameraInitialized) {
                    cameraInitialized = true
                    locationStateHolder.updateLocation(location)
                    cameraPositionState.move(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(location.latitude, location.longitude), 15f
                        )
                    )
                }
            }
        } catch (e: Exception) { }
    }

    val cachedLocation by locationStateHolder.lastLocation.collectAsState()
    LaunchedEffect(cachedLocation) {
        if (!cameraInitialized && cachedLocation != null && isLocationGranted) {
            cameraInitialized = true
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(cachedLocation!!.latitude, cachedLocation!!.longitude), 15f
                )
            )
        }
    }

    DisposableEffect(isLocationGranted) {
        if (!isLocationGranted) return@DisposableEffect onDispose { }
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    locationStateHolder.updateLocation(location)
                    if (!cameraInitialized) {
                        cameraInitialized = true
                        cameraPositionState.move(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(location.latitude, location.longitude), 15f
                            )
                        )
                    }
                }
            }
        }
        try {
            fusedClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
                    .setMaxUpdates(3).build(),
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) { }
        onDispose {
            try { fusedClient.removeLocationUpdates(locationCallback) } catch (e: Exception) { }
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = remember(isLocationGranted) {
            MapProperties(
                isMyLocationEnabled = isLocationGranted,
                mapStyleOptions = MapStyleOptions(GRAYSCALE_MAP_STYLE)
            )
        },
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = isLocationGranted
        )
    )
}

// ── Resume shift dialog ───────────────────────────────────────────────────────
@Composable
fun ResumeShiftDialog(
    vehicleId: String,
    onResume: () -> Unit,
    onEnd: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Active Shift Found") },
        text = { Text("Resume shift with Vehicle $vehicleId?") },
        confirmButton = {
            TextButton(onClick = onResume) { Text("Resume", color = ObaGreen) }
        },
        dismissButton = {
            TextButton(onClick = onEnd) { Text("End Previous Shift") }
        }
    )
}

// ── Grayscale map style ───────────────────────────────────────────────────────
val GRAYSCALE_MAP_STYLE = """
[
  { "elementType": "geometry", "stylers": [{ "saturation": -100 }] },
  { "elementType": "labels.text.fill", "stylers": [{ "saturation": -100 }] },
  { "elementType": "labels.text.stroke", "stylers": [{ "saturation": -100 }] }
]
""".trimIndent()