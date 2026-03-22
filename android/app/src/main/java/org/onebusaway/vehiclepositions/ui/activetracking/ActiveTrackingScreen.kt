package org.onebusaway.vehiclepositions.ui.activetracking

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.concurrent.atomic.AtomicBoolean

private val ObaGreen = Color(0xFF6BAA39)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTrackingScreen(
    vehicleId: String,
    onShiftStopped: () -> Unit,
    viewModel: ActiveTrackingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPositionState = rememberCameraPositionState()
    var cameraInitialized by remember { mutableStateOf(false) }
    val stopped = remember { AtomicBoolean(false) }
    val context = LocalContext.current
    val currentOnShiftStopped by rememberUpdatedState(onShiftStopped)

    LaunchedEffect(vehicleId) {
        viewModel.startTracking(vehicleId)
    }

    // If the token refresh fails the service emits NavigateToLogin — handle it here
    LaunchedEffect(Unit) {
        viewModel.navigateToLogin.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            // TODO: replace with navController.navigate("login") when login PR merges
            currentOnShiftStopped()
        }
    }

    // Seed the camera from cache immediately to avoid defaulting to (0, 0) on shift start
    LaunchedEffect(Unit) {
        val cached = viewModel.getCachedLocation()
        if (cached != null && !cameraInitialized) {
            cameraInitialized = true
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(cached.latitude, cached.longitude), 17f
                )
            )
        }
    }

    // Always follow real GPS when available; fall back to cached only for the first frame
    LaunchedEffect(uiState.currentLocation, uiState.isGpsAvailable) {
        uiState.currentLocation?.let { location ->
            if (uiState.isGpsAvailable) {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude), 17f
                    )
                )
            } else if (!cameraInitialized) {
                cameraInitialized = true
                cameraPositionState.move(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude), 17f
                    )
                )
            }
        }
    }

    // Recompute the arrow only when bearing changes, not on every recomposition
    val arrowBitmap = remember(uiState.currentLocation?.bearing) {
        createArrowBitmap(
            bearing = uiState.currentLocation?.bearing ?: 0f,
            color = android.graphics.Color.parseColor("#1A73E8")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Vehicle: $vehicleId")
                        Spacer(modifier = Modifier.width(12.dp))
                        LiveIndicator(isGpsAvailable = uiState.isGpsAvailable)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // compareAndSet guarantees stopTracking is called exactly once
                    // even if the user taps rapidly
                    if (stopped.compareAndSet(false, true)) {
                        viewModel.stopTracking()
                        onShiftStopped()
                    }
                },
                icon = {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_delete),
                        contentDescription = "Stop Shift"
                    )
                },
                text = {
                    Text(
                        text = if (!stopped.get()) "Stop Shift" else "Stopping..."
                    )
                },
                containerColor = if (!stopped.get()) Color.Red else Color.Gray,
                contentColor = Color.White
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = false),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false
                )
            ) {
                uiState.currentLocation?.let { location ->
                    Marker(
                        state = MarkerState(
                            position = LatLng(location.latitude, location.longitude)
                        ),
                        icon = BitmapDescriptorFactory.fromBitmap(arrowBitmap),
                        title = "Vehicle: $vehicleId",
                        flat = true,
                        anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                    )
                }
            }
        }
    }
}

// ── Navigation arrow bitmap ───────────────────────────────────────────────────
fun createArrowBitmap(bearing: Float, color: Int): Bitmap {
    val size = 120
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val fillPaint = Paint().apply {
        isAntiAlias = true
        this.color = color
        style = Paint.Style.FILL
    }

    val strokePaint = Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    val path = Path().apply {
        moveTo(size / 2f, 8f)
        lineTo(size - 16f, size - 16f)
        lineTo(size / 2f, size - 32f)
        lineTo(16f, size - 16f)
        close()
    }

    val matrix = Matrix()
    matrix.postRotate(bearing, size / 2f, size / 2f)
    path.transform(matrix)

    canvas.drawPath(path, fillPaint)
    canvas.drawPath(path, strokePaint)

    return bitmap
}

// ── Live / GPS searching indicator ────────────────────────────────────────────
@Composable
fun LiveIndicator(isGpsAvailable: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val color by animateColorAsState(
        targetValue = if (isGpsAvailable) ObaGreen else Color(0xFFFFEB3B),
        label = "color"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isGpsAvailable) "LIVE" else "GPS SEARCHING",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}