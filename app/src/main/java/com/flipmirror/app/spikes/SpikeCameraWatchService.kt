package com.flipmirror.app.spikes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.flipmirror.app.R

/**
 * Spike de detección de cámara en uso: foreground service que registra un
 * [CameraManager.AvailabilityCallback] en background y mantiene una
 * notificación persistente con el estado actual (cámara libre o en uso).
 *
 * En Android 14+ se declara con foregroundServiceType specialUse y la
 * property PROPERTY_SPECIAL_USE_FGS_SUBTYPE en el manifest.
 */
class SpikeCameraWatchService : Service() {
    private lateinit var cameraManager: CameraManager
    private lateinit var notificationManager: NotificationManager

    // Ids de cámara ocupadas ahora mismo según los eventos recibidos.
    private val busyCameraIds = sortedSetOf<String>()
    private var callbackRegistered = false

    private val availabilityCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                busyCameraIds.remove(cameraId)
                logEvent(cameraId, available = true)
                updateNotification()
            }

            override fun onCameraUnavailable(cameraId: String) {
                busyCameraIds.add(cameraId)
                logEvent(cameraId, available = false)
                updateNotification()
            }
        }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(CameraManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(statusText()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (!callbackRegistered) {
            cameraManager.registerAvailabilityCallback(
                availabilityCallback,
                Handler(Looper.getMainLooper()),
            )
            callbackRegistered = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
            callbackRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logEvent(
        cameraId: String,
        available: Boolean,
    ) {
        CameraEventLog.add(
            CameraEventLog.CameraEvent(
                timestampMillis = System.currentTimeMillis(),
                cameraId = cameraId,
                available = available,
                source = SOURCE,
            ),
        )
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.spike_watch_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun statusText(): String =
        if (busyCameraIds.isEmpty()) {
            getString(R.string.spike_camera_free)
        } else {
            getString(R.string.spike_camera_in_use, busyCameraIds.joinToString(", "))
        }

    private fun buildNotification(text: String): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, SpikeLabActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.spike_watch_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText()))
    }

    companion object {
        private const val CHANNEL_ID = "spike_camera_watch"
        private const val NOTIFICATION_ID = 1001

        /** Etiqueta de origen con la que este service firma sus eventos en el log. */
        const val SOURCE = "svc"
    }
}
