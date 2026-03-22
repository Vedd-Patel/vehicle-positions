package org.onebusaway.vehiclepositions.ui.home

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.onebusaway.vehiclepositions.util.LocationStateHolder
import org.onebusaway.vehiclepositions.util.ShiftStateManager
import javax.inject.Inject

sealed class HomeUiState {
    object EmptyMap : HomeUiState()
    object ActiveTracking : HomeUiState()
    object ResumePrompt : HomeUiState()
}

sealed class PermissionState {
    object NeverAsked : PermissionState()
    object ShowFineLocationRationale : PermissionState()
    object PermanentlyDenied : PermissionState()
    object GpsDisabled : PermissionState()
    object ShowBackgroundRationale : PermissionState()
    object Granted : PermissionState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val shiftStateManager: ShiftStateManager,
    val locationStateHolder: LocationStateHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.EmptyMap)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _activeVehicleId = MutableStateFlow<String?>(null)
    val activeVehicleId: StateFlow<String?> = _activeVehicleId.asStateFlow()

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.NeverAsked)
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    val isStartShiftAllowed: Boolean
        get() = _permissionState.value == PermissionState.Granted

    init {
        checkShiftState()
    }

    private fun checkShiftState() {
        viewModelScope.launch {
            val isActive = shiftStateManager.isShiftActive.first()
            if (isActive) {
                val vehicleId = shiftStateManager.activeVehicleId.first()
                if (!vehicleId.isNullOrBlank()) {
                    _activeVehicleId.value = vehicleId
                    _uiState.value = HomeUiState.ResumePrompt
                } else {
                    // Shift was marked active but has no vehicle ID — clean it up
                    shiftStateManager.endShift()
                    _uiState.value = HomeUiState.EmptyMap
                }
            }
        }
    }

    fun onFineLocationDenied(shouldShowRationale: Boolean) {
        _permissionState.value = if (shouldShowRationale)
            PermissionState.ShowFineLocationRationale
        else
            PermissionState.PermanentlyDenied
    }

    // Fine location granted — check GPS next
    fun onFineLocationGranted() {
        _permissionState.value = PermissionState.GpsDisabled
    }

    // GPS confirmed on — check background permission on Android 11+
    fun onGpsConfirmed() {
        _permissionState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionState.ShowBackgroundRationale
        } else {
            PermissionState.Granted
        }
    }

    fun onFineLocationGrantedButBackgroundDenied() {
        _permissionState.value = PermissionState.ShowBackgroundRationale
    }

    fun onPermissionsGranted() {
        _permissionState.value = PermissionState.Granted
    }

    fun onResumeShift() {
        _uiState.value = HomeUiState.ActiveTracking
    }

    fun onEndPreviousShift() {
        viewModelScope.launch {
            shiftStateManager.endShift()
            _uiState.value = HomeUiState.EmptyMap
        }
    }

    fun onShiftStarted(vehicleId: String) {
        viewModelScope.launch {
            shiftStateManager.startShift(vehicleId)
            _activeVehicleId.value = vehicleId
            _uiState.value = HomeUiState.ActiveTracking
        }
    }

    fun onShiftStopped() {
        _uiState.value = HomeUiState.EmptyMap
        _activeVehicleId.value = null
        viewModelScope.launch {
            shiftStateManager.endShift()
        }
    }
}