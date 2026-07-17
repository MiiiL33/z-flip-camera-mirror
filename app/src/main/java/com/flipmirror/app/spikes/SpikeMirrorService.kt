package com.flipmirror.app.spikes

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.flipmirror.app.R

/**
 * Spike 3b: foreground service de tipo mediaProjection que captura la
 * pantalla principal y la renderiza dentro de un overlay montado en el
 * display exterior (id 1 en los Samsung Flip), logrando el efecto espejo.
 *
 * Cadena completa: [SpikeMirrorActivity] obtiene el consentimiento de captura
 * (resultCode más data del diálogo del sistema) y se lo pasa por extras. Aquí:
 *
 * 1. startForeground con tipo mediaProjection ANTES de tocar el
 *    MediaProjection (obligatorio en Android 14+, si no SecurityException).
 * 2. Se reconstruye el MediaProjection y se registra su callback (también
 *    obligatorio en Android 14+ antes de crear el VirtualDisplay).
 * 3. Se monta un overlay TYPE_APPLICATION_OVERLAY en el display 1 con un
 *    SurfaceView dimensionado por [MirrorGeometry.letterbox] (sin deformar).
 * 4. La Surface de ese SurfaceView es la salida del VirtualDisplay espejo.
 *
 * Si el display 1 no existe (teléfono abierto en ciertos estados) cae al
 * display 0 como fallback de diagnóstico. Todo paso se registra en logcat con
 * el tag FlipSpike, prefijo "mirror".
 */
class SpikeMirrorService : Service() {
    private lateinit var displayManager: DisplayManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayView: View? = null

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                // El sistema puede cortar la proyección por su cuenta (por
                // ejemplo al bloquear el equipo): limpiamos sin re-stop.
                logSpike("mirror projection_stopped")
                mediaProjection = null
                stopMirror()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> startMirror(intent)
            ACTION_STOP -> {
                logSpike("mirror service_stop_requested")
                stopMirror()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> stopSelf()
        }
        // El token de consentimiento es de un solo uso: no tiene sentido que
        // el sistema reinicie el service sin un intent nuevo de la activity.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // region Arranque del espejo

    private fun startMirror(intent: Intent) {
        logSpike("mirror service_start")

        // Obligatorio en Android 14+: declarar el foreground con tipo
        // mediaProjection antes de reconstruir la proyección.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            logSpike("mirror error tipo=datos_invalidos resultCode=$resultCode dataNull=${resultData == null}")
            abort()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            logSpike("mirror error tipo=sin_permiso_overlay")
            abort()
            return
        }

        // Si había una sesión previa viva, se libera antes de abrir la nueva.
        stopMirror()

        val projection =
            try {
                projectionManager.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                // SecurityException si el token ya se usó o expiró.
                logSpike("mirror error tipo=${e.javaClass.simpleName} detalle=$e")
                abort()
                return
            }
        // Android 14+ exige registrar el callback antes del VirtualDisplay.
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection

        val targetDisplay = resolveTargetDisplay()
        if (targetDisplay == null) {
            logSpike("mirror error tipo=sin_display_destino")
            stopMirror()
            abort()
            return
        }
        createMirrorOverlay(targetDisplay)
    }

    /**
     * Display destino del espejo: el exterior (id 1) si existe; si One UI no
     * lo expone en este estado (por ejemplo con el teléfono abierto), fallback
     * de diagnóstico al display 0.
     */
    private fun resolveTargetDisplay(): Display? {
        val outer = displayManager.getDisplay(SpikeLabActivity.OUTER_DISPLAY_ID)
        if (outer != null) return outer
        logSpike("mirror display1_null")
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun createMirrorOverlay(display: Display) {
        try {
            val windowContext =
                createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val windowManager = windowContext.getSystemService(WindowManager::class.java)

            // Tamaño real del display destino (748x720 en la cover del Flip 5,
            // pero se lee en runtime) y de la fuente (display 0 con rotación).
            val targetBounds = windowManager.maximumWindowMetrics.bounds
            val sourceSize = currentSourceSize()
            val frame =
                MirrorGeometry.letterbox(
                    sourceWidth = sourceSize.width,
                    sourceHeight = sourceSize.height,
                    targetWidth = targetBounds.width(),
                    targetHeight = targetBounds.height(),
                )
            logSpike(
                "mirror letterbox src=${sourceSize.width}x${sourceSize.height} " +
                    "dst=${targetBounds.width()}x${targetBounds.height()} " +
                    "frame=${frame.width}x${frame.height}+${frame.left}+${frame.top}",
            )

            val surfaceView = SurfaceView(windowContext)
            surfaceView.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        attachVirtualDisplay(holder.surface, frame.width, frame.height)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // La Surface muere con el overlay: se desengancha para
                        // que el VirtualDisplay no escriba en memoria liberada.
                        virtualDisplay?.surface = null
                    }
                },
            )

            // Contenedor negro a pantalla completa: las bandas del letterbox.
            val container =
                FrameLayout(windowContext).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(
                        surfaceView,
                        FrameLayout.LayoutParams(frame.width, frame.height, Gravity.CENTER),
                    )
                }
            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE,
                )
            windowManager.addView(container, params)
            overlayWindowManager = windowManager
            overlayView = container
            logSpike("mirror overlay_on_display id=${display.displayId} resultado=ok")
            updateNotification(display.displayId)
        } catch (e: Exception) {
            logSpike("mirror overlay_on_display id=${display.displayId} resultado=error excepcion=$e")
            stopMirror()
            abort()
        }
    }

    private fun attachVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        val projection = mediaProjection
        if (projection == null) {
            logSpike("mirror error tipo=projection_null_al_crear_virtualdisplay")
            return
        }
        try {
            virtualDisplay =
                projection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    resources.configuration.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    mainHandler,
                )
            logSpike("mirror virtualdisplay_created w=$width h=$height")
        } catch (e: Exception) {
            logSpike("mirror error tipo=${e.javaClass.simpleName} detalle=$e")
            stopMirror()
            abort()
        }
    }

    /**
     * Tamaño actual de la pantalla principal (la fuente de la captura),
     * corrigiendo por rotación: mode.physical es en orientación natural.
     */
    private fun currentSourceSize(): Size {
        val source = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = source.mode
        val rotated =
            source.rotation == Surface.ROTATION_90 || source.rotation == Surface.ROTATION_270
        return if (rotated) {
            Size(mode.physicalHeight, mode.physicalWidth)
        } else {
            Size(mode.physicalWidth, mode.physicalHeight)
        }
    }

    // endregion

    // region Limpieza

    /** Libera VirtualDisplay, MediaProjection y overlay. Idempotente. */
    private fun stopMirror() {
        virtualDisplay?.release()
        virtualDisplay = null
        overlayView?.let { view ->
            try {
                overlayWindowManager?.removeView(view)
            } catch (_: Exception) {
                // La view pudo desaparecer con su display; no es fallo del spike.
            }
        }
        overlayView = null
        overlayWindowManager = null
        mediaProjection?.let { projection ->
            // Se quita el callback primero para no re-entrar por onStop.
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        }
        mediaProjection = null
    }

    /** Corta el foreground y termina el service tras un fallo de arranque. */
    private fun abort() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // endregion

    // region Notificación

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.spike_mirror_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(targetDisplayId: Int? = null): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, SpikeMirrorActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, SpikeMirrorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopAction =
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.spike_mirror_notification_stop),
                stopIntent,
            ).build()
        val text =
            if (targetDisplayId == null) {
                getString(R.string.spike_mirror_notification_starting)
            } else {
                getString(R.string.spike_mirror_notification_text, targetDisplayId)
            }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.spike_mirror_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(targetDisplayId: Int) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(targetDisplayId))
    }

    // endregion

    private fun logSpike(message: String) {
        Log.i(SpikeLabActivity.LOG_TAG, message)
    }

    companion object {
        /** Orden de arranque: requiere [EXTRA_RESULT_CODE] y [EXTRA_RESULT_DATA]. */
        const val ACTION_START = "com.flipmirror.app.spikes.mirror.START"

        /** Orden de parada: libera todo y termina el service. */
        const val ACTION_STOP = "com.flipmirror.app.spikes.mirror.STOP"

        /** Extra int: resultCode del diálogo de consentimiento de captura. */
        const val EXTRA_RESULT_CODE = "mirror_result_code"

        /** Extra Intent (parcelable): data del diálogo de consentimiento. */
        const val EXTRA_RESULT_DATA = "mirror_result_data"

        private const val CHANNEL_ID = "spike_mirror"
        private const val NOTIFICATION_ID = 1002
        private const val VIRTUAL_DISPLAY_NAME = "flipmirror-spike-mirror"
    }
}
