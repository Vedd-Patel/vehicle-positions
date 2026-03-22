package org.onebusaway.vehiclepositions.ui.activetracking

import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.onebusaway.vehiclepositions.data.repository.VehicleRepository
import org.onebusaway.vehiclepositions.service.LocationForegroundService
import org.onebusaway.vehiclepositions.util.LocationStateHolder
import org.onebusaway.vehiclepositions.util.ServiceEvent
import org.onebusaway.vehiclepositions.util.ServiceEventBus
import org.onebusaway.vehiclepositions.util.ShiftStateManager
import org.onebusaway.vehiclepositions.util.TokenManager
import javax.inject.Inject

data class ActiveTrackingUiState(
    val isShiftActive: Boolean = false,
    val isGpsAvailable: Boolean = false,
    val currentLocation: Location? = null,
    val vehicleId: String = "",
    val errorMessage: String? = null
)

/**
 * Starts and stops LocationForegroundService, mirrors live location onto the map,
 * watches for GPS timeout, and forwards session/permission events to the UI.
 *
 * GPS registration, the 10-second POST loop, and token refresh all live in
 * LocationForegroundService — not here.
 */
@HiltViewModel
class ActiveTrackingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VehicleRepository,
    private val shiftStateManager: ShiftStateManager,
    private val locationStateHolder: LocationStateHolder,
    private val tokenManager: TokenManager,
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActiveTrackingUiState())
    val uiState: StateFlow<ActiveTrackingUiState> = _uiState.asStateFlow()

    // One-shot events consumed by the Composable — SharedFlow so they survive recomposition
    private val _navigateToLogin = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToLogin: SharedFlow<String> = _navigateToLogin.asSharedFlow()

    private val GPS_TIMEOUT_MS = 30_000L

    private var lastGpsTime = 0L
    private var isStopped = false
    private var isTrackingStarted = false

    companion object {
        private const val TAG = "ActiveTrackingVM"
    }

    init {
        // Seed the UI with the last known location so the map isn't empty on launch
        locationStateHolder.lastLocation.value?.let { cached ->
            _uiState.update { it.copy(currentLocation = cached) }
        }

        // ── Live location ─────────────────────────────────────────────────────
        // LocationForegroundService writes to LocationStateHolder; we read here
        viewModelScope.launch {
            locationStateHolder.lastLocation.collect { location ->
                location ?: return@collect
                lastGpsTime = System.currentTimeMillis()
                _uiState.update {
                    it.copy(currentLocation = location, isGpsAvailable = true)
                }
            }
        }

        // ── Service events ────────────────────────────────────────────────────
        viewModelScope.launch {
            serviceEventBus.events.collect { event ->
                when (event) {
                    is ServiceEvent.NavigateToLogin -> {
                        Log.d(TAG, "ServiceEvent.NavigateToLogin: ${event.message}")
                        isStopped = true
                        _uiState.update { it.copy(isShiftActive = false) }
                        _navigateToLogin.tryEmit(event.message)
                    }
                    is ServiceEvent.LocationPermissionRevoked -> {
                        Log.d(TAG, "ServiceEvent.LocationPermissionRevoked")
                        isStopped = true
                        _uiState.update { it.copy(isShiftActive = false) }
                        _navigateToLogin.tryEmit(event.message)
                    }
                }
            }
        }

        // ── GPS timeout watchdog ──────────────────────────────────────────────
        // Switches LIVE -> GPS SEARCHING if no fix arrives for 30 seconds
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (isStopped) break
                val noGps = lastGpsTime > 0 &&
                        System.currentTimeMillis() - lastGpsTime > GPS_TIMEOUT_MS
                if (noGps && _uiState.value.isGpsAvailable) {
                    Log.w(TAG, "GPS timeout — switching to GPS SEARCHING state")
                    _uiState.update { it.copy(isGpsAvailable = false) }
                }
            }
        }
    }

    fun getCachedLocation(): Location? = locationStateHolder.lastLocation.value

    // Launches the foreground service which owns all GPS + POST work
    fun startTracking(vehicleId: String) {
        if (isTrackingStarted) return
        isTrackingStarted = true
        isStopped = false

        Log.d(TAG, "startTracking vehicle=$vehicleId")

        _uiState.update {
            it.copy(vehicleId = vehicleId, isShiftActive = true)
        }

        context.startForegroundService(
            Intent(context, LocationForegroundService::class.java).apply {
                putExtra(LocationForegroundService.EXTRA_VEHICLE_ID, vehicleId)
            }
        )
    }

    // Called from the in-app "Stop Shift" FAB
    fun stopTracking() {
        if (isStopped) return
        isStopped = true
        isTrackingStarted = false

        Log.d(TAG, "stopTracking vehicle=${_uiState.value.vehicleId}")

        _uiState.update { it.copy(isShiftActive = false) }

        try {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: ${e.message}")
        }

        viewModelScope.launch {
            shiftStateManager.endShift()
            val vid = _uiState.value.vehicleId
            if (vid.isNotBlank()) repository.saveVehicleToRecents(vid)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (!isStopped) stopTracking()
    }
}