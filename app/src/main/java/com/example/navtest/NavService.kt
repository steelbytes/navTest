package com.example.navtest

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.navigation.AudioGuidanceSettings
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class NavService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sequence: Job? = null
    private var navigator: Navigator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        running.value = true
        if (sequence?.isActive != true) sequence = scope.launch { runSequence() }
        return START_NOT_STICKY
    }

    private suspend fun runSequence() {
        log("service started")
        delay(5_000)
        var failures = 0
        for (loop in 1..LOOPS) {
            log("loop $loop of $LOOPS")
            val nav = createNavigator() ?: break
            navigator = nav
            if (!navigate(nav) && ++failures >= MAX_SET_DESTINATION_FAILURES) {
                log("setDestination failed $failures times, stopping service")
                destroyNavigator()
                break
            }

            delay(3_000)
            log("stopping navigation")
            nav.stopGuidance()

            delay(35_000)
            destroyNavigator()

            if (loop < LOOPS) delay(35_000)
        }
        log("sequence finished, stopping service")
        stopSelf()
    }

    private suspend fun createNavigator(): Navigator? = suspendCancellableCoroutine { cont ->
        log("Places.initialize")
        Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
        log("getNavigator")
        NavigationApi.getNavigator(application, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                log("onNavigatorReady")
                nav.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
                nav.setAudioGuidanceSettings(
                    AudioGuidanceSettings.Builder()
                        .setGuidanceMode(AudioGuidanceSettings.GuidanceMode.SILENT)
                        .setVolumeLevel(AudioGuidanceSettings.VolumeLevel.NORMAL)
                        .build()
                )
                val registered = nav.registerServiceForNavUpdates(
                    packageName,
                    NavInfoReceivingService::class.java.name,
                    NavigationUpdatesOptions.builder()
                        .setNumNextStepsToPreview(1)
                        .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.NONE)
                        .build()
                )
                log("registerServiceForNavUpdates $registered")
                cont.resume(nav)
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                log("getNavigator onError $errorCode")
                cont.resume(null)
            }
        })
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before the service is started
    /** Returns true if a route was found and guidance started. */
    private suspend fun navigate(nav: Navigator): Boolean {
        val dest = DESTINATIONS.random()
        log("setDestination $dest")
        // Entries are either a place ID or "lat,lng".
        val latLng = dest.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        val waypoint = if (latLng.size == 2) {
            Waypoint.builder().setLatLng(latLng[0], latLng[1]).build()
        } else {
            Waypoint.builder().setPlaceIdString(dest).build()
        }
        val routingOptions = RoutingOptions()
            .travelMode(RoutingOptions.TravelMode.DRIVING)
            .avoidFerries(true)
            .avoidHighways(true)
            .avoidTolls(true)
            .locationTimeoutMs(10_000L)
        // Suspend the sequence until the route result arrives, but don't let a request
        // that never answers stall the loop.
        val startMs = SystemClock.elapsedRealtime()
        val status = withTimeoutOrNull(SET_DESTINATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                nav.setDestination(waypoint, routingOptions).setOnResultListener { cont.resume(it) }
            }
        }
        if (status == null) {
            log("setDestination timed out after ${SET_DESTINATION_TIMEOUT_MS / 1000}s, clearing destination")
            nav.clearDestinations()
            return false
        }
        log("setDestination result $status in ${SystemClock.elapsedRealtime() - startMs}ms")
        if (status != Navigator.RouteStatus.OK) return false
        log("startGuidance")
        nav.startGuidance()
        return true
    }

    private fun destroyNavigator() {
        navigator?.let {
            log("destroying navigator")
            it.stopGuidance()
            it.clearDestinations()
            it.unregisterServiceForNavUpdates()
            it.cleanup()
            log("Places.deinitialize")
            Places.deinitialize()
        }
        navigator = null
    }

    override fun onDestroy() {
        scope.cancel()
        destroyNavigator()
        log("service destroyed")
        running.value = false
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("NavTest")
            .setContentText("Navigation test running")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    // SINGLE_TOP brings back the existing activity rather than stacking a new one.
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun log(msg: String) = AppLog.d(msg)

    companion object {
        /** True while the service exists; MainActivity uses it to enable/disable its buttons. */
        val running = MutableStateFlow(false)

        private const val LOOPS = 10
        private const val SET_DESTINATION_TIMEOUT_MS = 61_000L
        private const val MAX_SET_DESTINATION_FAILURES = 3
        private const val CHANNEL_ID = "nav"
        private const val NOTIFICATION_ID = 1
        private val DESTINATIONS = listOf(
            "ChIJNUaI5PGH2GoR25TEZbsoA9k",
            "ChIJtS4pdiv2vGoRIh50LiVBisY",
            "ChIJ0Vl4-moBz2oRLClPTiLuQA0",
            "ChIJP7Mmxcc1t2oRQMaOYlQ2AwQ",
            "-37.81400,144.96332",
            "ChIJQXVbMShRumoRmNwu-iozISE",
        )
    }
}
