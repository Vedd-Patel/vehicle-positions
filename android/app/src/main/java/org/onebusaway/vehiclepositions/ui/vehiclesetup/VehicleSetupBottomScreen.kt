package org.onebusaway.vehiclepositions.ui.vehiclesetup

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.onebusaway.vehiclepositions.data.local.VehicleEntity

private val ObaGreen = Color(0xFF6BAA39)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VehicleSetupBottomSheet(
    onDismiss: () -> Unit,
    onShiftStarted: (String) -> Unit,
    viewModel: VehicleSetupViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiState by viewModel.uiState.collectAsState()
    var hasStarted by remember { mutableStateOf(false) }
    var showGpsOffDialog by remember { mutableStateOf(false) }
    var showEmptyVehicleWarning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val listState = rememberLazyListState()

    // Prevents the bottom sheet from dismissing on upward fling while the list is scrolled to top
    val sheetDismissBlocker = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                return if (available.y > 0 && atTop) available.copy(x = 0f)
                else Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val atTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                return if (available.y > 0 && atTop) available else Velocity.Zero
            }
        }
    }

    if (showGpsOffDialog) {
        AlertDialog(
            onDismissRequest = { showGpsOffDialog = false },
            title = { Text("GPS is Disabled") },
            text = {
                Text(
                    "Your GPS was turned off. Please enable location " +
                            "services to start your shift."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGpsOffDialog = false
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    )
                }) { Text("Enable GPS", color = ObaGreen) }
            },
            dismissButton = {
                TextButton(onClick = { showGpsOffDialog = false }) { Text("Cancel") }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {

            Text(
                text = "Vehicle Setup",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = {
                    viewModel.onSearchQueryChanged(it)
                    if (it.isNotBlank()) showEmptyVehicleWarning = false
                },
                label = { Text("Vehicle ID") },
                placeholder = { Text("Enter or search Vehicle ID...") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                isError = uiState.validationError != null,
                supportingText = {
                    uiState.validationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ObaGreen,
                    focusedLabelColor = ObaGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cursorColor = ObaGreen
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Favorites ─────────────────────────────────────────────────────
            if (uiState.favorites.isNotEmpty()) {
                Text(
                    text = "FAVORITES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.favorites.forEach { vehicle ->
                        FavoriteChip(
                            vehicleId = vehicle.id,
                            onClick = { viewModel.onVehicleSelected(vehicle.id) },
                            onLongClick = { viewModel.toggleFavorite(vehicle.id, false) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Recent vehicles ───────────────────────────────────────────────
            if (uiState.recents.isNotEmpty()) {
                Text(
                    text = "RECENT VEHICLES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .nestedScroll(sheetDismissBlocker)
                ) {
                    items(
                        items = uiState.recents.take(10),
                        key = { it.id }
                    ) { vehicle ->
                        VehicleRow(
                            vehicle = vehicle,
                            onClick = { viewModel.onVehicleSelected(vehicle.id) },
                            onFavoriteToggle = {
                                viewModel.toggleFavorite(vehicle.id, !vehicle.isFavorite)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (showEmptyVehicleWarning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(
                            color = Color(0xFFFFF3CD),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text("ⓘ", fontSize = 16.sp, color = Color(0xFF856404))
                    Text(
                        text = "Please enter a valid Vehicle ID to start your shift.",
                        fontSize = 13.sp,
                        color = Color(0xFF856404),
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Start Shift ───────────────────────────────────────────────────
            Button(
                onClick = {
                    if (!hasStarted) {
                        if (uiState.searchQuery.isBlank()) {
                            showEmptyVehicleWarning = true
                            return@Button
                        }
                        showEmptyVehicleWarning = false

                        val locationManager = context.getSystemService(
                            android.content.Context.LOCATION_SERVICE
                        ) as android.location.LocationManager
                        if (!locationManager.isProviderEnabled(
                                android.location.LocationManager.GPS_PROVIDER
                            )
                        ) {
                            showGpsOffDialog = true
                            return@Button
                        }

                        val vehicleId = uiState.searchQuery.trim()
                        if (viewModel.validateAndStart(vehicleId)) {
                            hasStarted = true
                            onShiftStarted(vehicleId)
                        }
                    }
                },
                enabled = !hasStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.searchQuery.isBlank()) Color.Gray else ObaGreen,
                    disabledContainerColor = ObaGreen.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = if (hasStarted) "Starting..." else "Start Shift",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

// ── Favorite chip — long press to unfavorite ──────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteChip(
    vehicleId: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(android.R.drawable.btn_star_big_on),
                contentDescription = "Favorite",
                tint = ObaGreen,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = vehicleId,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Vehicle row ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VehicleRow(
    vehicle: VehicleEntity,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(android.R.drawable.ic_menu_directions),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = vehicle.id,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(
            onClick = onFavoriteToggle,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(
                    if (vehicle.isFavorite) android.R.drawable.btn_star_big_on
                    else android.R.drawable.btn_star_big_off
                ),
                contentDescription = if (vehicle.isFavorite) "Remove favorite"
                else "Add favorite",
                tint = if (vehicle.isFavorite) ObaGreen
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}