package org.onebusaway.vehiclepositions.ui.vehiclesetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.onebusaway.vehiclepositions.data.local.VehicleEntity
import org.onebusaway.vehiclepositions.data.repository.VehicleRepository
import javax.inject.Inject

data class VehicleSetupUiState(
    val searchQuery: String = "",
    val favorites: List<VehicleEntity> = emptyList(),
    val recents: List<VehicleEntity> = emptyList(),
    val validationError: String? = null
)

// Alphanumeric + hyphens/underscores, max 50 chars — matches server-side validation
private val VEHICLE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,50}$")

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class VehicleSetupViewModel @Inject constructor(
    private val repository: VehicleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleSetupUiState())
    val uiState: StateFlow<VehicleSetupUiState> = _uiState.asStateFlow()

    // Separate flow for the search query so flatMapLatest can react to it independently
    private val _searchQuery = MutableStateFlow("")

    init {
        // ── Favorites ─────────────────────────────────────────────────────────
        viewModelScope.launch {
            repository.getFavorites().collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }

        // ── Recents / search ──────────────────────────────────────────────────
        // flatMapLatest cancels the previous DB collector on each new query, preventing
        // stale coroutines from racing to write to _uiState simultaneously.
        // debounce avoids firing a query on every single keystroke.
        viewModelScope.launch {
            _searchQuery
                .debounce(150L)
                .flatMapLatest { query ->
                    if (query.isBlank()) repository.getRecents()
                    else repository.searchVehicles(query)
                }
                .collect { results ->
                    _uiState.update { it.copy(recents = results) }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        val error = when {
            query.isBlank() -> null
            !VEHICLE_ID_REGEX.matches(query) ->
                "Only letters, numbers, hyphens and underscores allowed (max 50 chars)"
            else -> null
        }
        _uiState.update { it.copy(searchQuery = query, validationError = error) }
        _searchQuery.value = query
    }

    fun onVehicleSelected(vehicleId: String) {
        _uiState.update { it.copy(searchQuery = vehicleId, validationError = null) }
    }

    fun toggleFavorite(vehicleId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(vehicleId, isFavorite)
        }
    }

    fun validateAndStart(vehicleId: String): Boolean {
        return if (VEHICLE_ID_REGEX.matches(vehicleId)) {
            _uiState.update { it.copy(validationError = null) }
            true
        } else {
            _uiState.update { it.copy(validationError = "Invalid Vehicle ID format") }
            false
        }
    }
}