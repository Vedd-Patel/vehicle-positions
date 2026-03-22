package org.onebusaway.vehiclepositions.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onebusaway.vehiclepositions.MainActivity
import org.onebusaway.vehiclepositions.data.remote.LocationRequest as LocationPayload
import org.onebusaway.vehiclepositions.data.repository.VehicleRepository
import org.onebusaway.vehiclepositions.util.LocationStateHolder
import org.onebusaway.vehiclepositions.util.ServiceEvent
import org.onebusaway.vehiclepositions.util.ServiceEventBus
import org.onebusaway.vehiclepositions.util.ShiftStateManager
import org.onebusaway.vehiclepositions.util.TokenManager
import javax.inject.Inject

@AndroidEntryPoint
class LocationForegroundService : Service() {

    @Inject lateinit var shiftStateManager: ShiftStateManager
    @Inject lateinit var repository: VehicleRepository
    @Inject lateinit var locationStateHolder: LocationStateHolder
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var serviceEventBus: ServiceEventBus

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var vehicleId: String = ""
    private var shiftStartTime: Long = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var reportingJob: Job? = null
    private var notificationJob: Job? = null

    companion object {
        const val CHANNEL_ID           = "location_tracking_channel"
        const val NOTIFICATION_ID      = 1001
        const val EXTRA_VEHICLE_ID     = "vehicle_id"
        const val ACTION_STOP_SHIFT    = "action_stop_shift"
        const val BROADCAST_STOP_SHIFT =
            "org.onebusaway.vehiclepositiondriver.STOP_SHIFT"

        private const val TAG                = "LocationForegroundService"
        private const val REPORT_INTERVAL_MS = 10_000L  // POST every 10 s
        private const val GPS_INTERVAL_MS    = 2_000L   // FLP updates every 2 s
        private const val NOTIF_REFRESH_MS   = 1_000L   // notification timer tick
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP_SHIFT) {
            handleStopShift()
            return START_NOT_STICKY
        }

        vehicleId      = intent?.getStringExtra(EXTRA_VEHICLE_ID) ?: "Unknown"
        shiftStartTime = System.currentTimeMillis()

        Log.d(TAG, "Starting shift for vehicle=$vehicleId")
        startForeground(NOTIFICATION_ID, buildNotification("Starting shift..."))

        startLocationUpdates()
        startReportingLoop()
        startNotificationRefreshLoop()

        return START_STICKY
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    locationStateHolder.updateLocation(location)
                    Log.d(TAG, "GPS fix received — accuracy=${location.accuracy}m")
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                .setMinUpdateIntervalMillis(GPS_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build(),
            locationCallback,
            Looper.getMainLooper()
        ).addOnSuccessListener {
            Log.d(TAG, "Location updates registered")
        }.addOnFailureListener { exception ->
            // Permission was revoked while the service was running — end the shift cleanly
            Log.e(TAG, "Location permission revoked: ${exception.message}")
            serviceEventBus.emit(
                ServiceEvent.LocationPermissionRevoked(
                    "Location permission was revoked. Shift ended."
                )
            )
            serviceScope.launch { shiftStateManager.endShift() }
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d(TAG, "Location updates removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location updates: ${e.message}")
            }
        }
    }

    // ── Reporting loop — POSTs location every 10 seconds ─────────────────────

    private fun startReportingLoop() {
        reportingJob = serviceScope.launch {
            Log.d(TAG, "Reporting loop started — interval=${REPORT_INTERVAL_MS}ms")
            while (true) {
                delay(REPORT_INTERVAL_MS)
                val location = locationStateHolder.lastLocation.value
                if (location != null) {
                    sendLocationReport(location)
                } else {
                    Log.d(TAG, "10s tick — no GPS fix yet, skipping report")
                }
            }
        }
    }

    private suspend fun sendLocationReport(location: android.location.Location) {
        val payload = LocationPayload(
            vehicleId = vehicleId,
            latitude  = location.latitude,
            longitude = location.longitude,
            bearing   = location.bearing,
            speed     = location.speed,
            accuracy  = location.accuracy,
            timestamp = System.currentTimeMillis() / 1000
        )

        Log.d(TAG, "-> POST /api/v1/locations  vehicle=$vehicleId  " +
                "lat=${payload.latitude}  lng=${payload.longitude}  " +
                "bearing=${payload.bearing}  speed=${payload.speed}  " +
                "accuracy=${payload.accuracy}  ts=${payload.timestamp}")

        repository.postLocation(payload).onFailure { error ->
            when {
                error.message?.contains("401") == true -> {
                    Log.w(TAG, "401 — attempting token refresh")
                    repository.refreshToken()
                        .onSuccess {
                            Log.d(TAG, "Token refreshed — retrying POST")
                            repository.postLocation(payload).onFailure { retryError ->
                                Log.e(TAG, "Retry after refresh failed: ${retryError.message}")
                            }
                        }
                        .onFailure {
                            // Refresh failed — session is unrecoverable, send user back to login
                            Log.e(TAG, "Token refresh failed — ending shift")
                            tokenManager.clearTokens()
                            shiftStateManager.endShift()
                            serviceEventBus.emit(
                                ServiceEvent.NavigateToLogin(
                                    "Your session has expired. Please log in again."
                                )
                            )
                            stopShiftAndSelf()
                        }
                }
                else -> {
                    Log.e(TAG, "POST failed: ${error.message} — will retry next cycle")
                }
            }
        }
    }

    // ── Notification — updates every second for a live elapsed timer ──────────

    private fun startNotificationRefreshLoop() {
        notificationJob = serviceScope.launch {
            while (true) {
                delay(NOTIF_REFRESH_MS)
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification("Vehicle: $vehicleId  •  ${getElapsedTime()}")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    private fun getElapsedTime(): String {
        val elapsed = System.currentTimeMillis() - shiftStartTime
        val hours   = elapsed / 3_600_000
        val minutes = (elapsed % 3_600_000) / 60_000
        val seconds = (elapsed % 60_000) / 1_000
        return if (hours > 0)
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else
            String.format("%02d:%02d", minutes, seconds)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, LocationForegroundService::class.java).apply {
                action = ACTION_STOP_SHIFT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transit Driver — Active Shift")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Shift", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active shift status and vehicle tracking"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }

    // ── Stop helpers ──────────────────────────────────────────────────────────

    private fun handleStopShift() {
        Log.d(TAG, "Stop shift from notification action")
        serviceScope.launch { shiftStateManager.endShift() }
        sendBroadcast(Intent(BROADCAST_STOP_SHIFT).apply { setPackage(packageName) })
        stopShiftAndSelf()
    }

    private fun stopShiftAndSelf() {
        reportingJob?.cancel()
        notificationJob?.cancel()
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed — cleaning up")
        serviceScope.launch { shiftStateManager.endShift() }
        stopShiftAndSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopLocationUpdates()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}