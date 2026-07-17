package com.flipmirror.app.spikes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.flipmirror.app.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Laboratorio de spikes: pantalla de instrumentación para ejecutar los
 * checklists de investigación en dispositivos físicos (Z Flip 5 y 7) y en
 * emulador. Cubre tres spikes:
 *
 * 1. Displays: enumeración en vivo vía DisplayManager (¿aparece el display
 *    exterior, id 1 en Samsung?) más las FoldingFeature de Jetpack
 *    WindowManager.
 * 2. Overlay: ventanas TYPE_APPLICATION_OVERLAY sobre cada display detectado.
 * 3. Cámara en uso: AvailabilityCallback propio mientras la activity está
 *    visible, más el foreground service [SpikeCameraWatchService].
 */
class SpikeLabActivity : ComponentActivity() {
    private lateinit var displayManager: DisplayManager
    private lateinit var cameraManager: CameraManager

    private lateinit var displaysInfoView: TextView
    private lateinit var foldInfoView: TextView
    private lateinit var overlayButtonsContainer: LinearLayout
    private lateinit var overlayStatusView: TextView
    private lateinit var cameraLogView: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val activeOverlays = mutableListOf<ActiveOverlay>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private class ActiveOverlay(
        val windowManager: WindowManager,
        val view: View,
        val ticker: Runnable,
    )

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refreshDisplays()

            override fun onDisplayRemoved(displayId: Int) = refreshDisplays()

            override fun onDisplayChanged(displayId: Int) = refreshDisplays()
        }

    private val activityCameraCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) = logCameraEvent(cameraId, available = true)

            override fun onCameraUnavailable(cameraId: String) = logCameraEvent(cameraId, available = false)
        }

    private val cameraLogListener: (List<CameraEventLog.CameraEvent>) -> Unit = { events ->
        runOnUiThread { renderCameraLog(events) }
    }

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.spike_notifications_denied, Toast.LENGTH_LONG).show()
            }
            // Arrancamos igual: el service funciona, solo que sin notificación visible.
            startWatchService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Igual que el visor real: debe poder usarse con el equipo bloqueado.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        displayManager = getSystemService(DisplayManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)

        setContentView(buildUi())
        observeFoldingFeatures()
        refreshDisplays()
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, uiHandler)
        cameraManager.registerAvailabilityCallback(activityCameraCallback, uiHandler)
        CameraEventLog.addListener(cameraLogListener)
        renderCameraLog(CameraEventLog.snapshot())
        refreshDisplays()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        cameraManager.unregisterAvailabilityCallback(activityCameraCallback)
        CameraEventLog.removeListener(cameraLogListener)
        super.onStop()
    }

    override fun onDestroy() {
        removeAllOverlays()
        super.onDestroy()
    }

    // region UI

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
            }

        // Sección Displays
        root.addView(sectionTitle(getString(R.string.spike_section_displays)))
        displaysInfoView = monoTextView()
        root.addView(displaysInfoView)
        foldInfoView = monoTextView()
        foldInfoView.text = getString(R.string.spike_fold_waiting)
        root.addView(foldInfoView)
        root.addView(button(getString(R.string.spike_refresh)) { refreshDisplays() })

        // Sección Overlay
        root.addView(sectionTitle(getString(R.string.spike_section_overlay)))
        overlayButtonsContainer =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(overlayButtonsContainer)
        root.addView(button(getString(R.string.spike_remove_overlays)) { removeAllOverlays() })
        overlayStatusView = monoTextView()
        overlayStatusView.text = getString(R.string.spike_overlay_none)
        root.addView(overlayStatusView)

        // Sección Cámara en uso
        root.addView(sectionTitle(getString(R.string.spike_section_camera)))
        val watchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        watchRow.addView(button(getString(R.string.spike_watch_start)) { onStartWatchClicked() })
        watchRow.addView(button(getString(R.string.spike_watch_stop)) { stopWatchService() })
        root.addView(watchRow)
        cameraLogView = monoTextView()
        cameraLogView.text = getString(R.string.spike_camera_log_empty)
        root.addView(cameraLogView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sectionTitle(title: String): TextView =
        TextView(this).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(16), 0, dp(8))
        }

    private fun monoTextView(): TextView =
        TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    // endregion

    // region Spike 1: displays y plegado

    private fun refreshDisplays() {
        val displays = displayManager.displays
        displaysInfoView.text = displays.joinToString("\n\n") { describeDisplay(it) }
        rebuildOverlayButtons(displays)
    }

    private fun describeDisplay(display: Display): String {
        val mode = display.mode
        return buildString {
            appendLine("id=${display.displayId} name=${display.name}")
            appendLine("estado=${stateName(display.state)} rotacion=${rotationName(display.rotation)}")
            appendLine("modo=${mode.physicalWidth}x${mode.physicalHeight} @${mode.refreshRate}Hz")
            append("flags=${flagNames(display.flags)}")
        }
    }

    private fun stateName(state: Int): String =
        when (state) {
            Display.STATE_OFF -> "OFF"
            Display.STATE_ON -> "ON"
            Display.STATE_DOZE -> "DOZE"
            Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
            Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
            else -> "DESCONOCIDO($state)"
        }

    private fun rotationName(rotation: Int): String =
        when (rotation) {
            Surface.ROTATION_0 -> "0"
            Surface.ROTATION_90 -> "90"
            Surface.ROTATION_180 -> "180"
            Surface.ROTATION_270 -> "270"
            else -> rotation.toString()
        }

    private fun flagNames(flags: Int): String {
        val names = mutableListOf<String>()
        if (flags and Display.FLAG_PRESENTATION != 0) names.add("PRESENTATION")
        if (flags and Display.FLAG_PRIVATE != 0) names.add("PRIVATE")
        if (flags and Display.FLAG_SECURE != 0) names.add("SECURE")
        if (flags and Display.FLAG_ROUND != 0) names.add("ROUND")
        if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) names.add("PROTECTED_BUFFERS")
        // El valor crudo en hex permite ver bits ocultos del SDK (REAR, TRUSTED)
        // que Samsung usa en el display exterior.
        val known = if (names.isEmpty()) "ninguno" else names.joinToString("|")
        return "$known (0x${Integer.toHexString(flags)})"
    }

    private fun observeFoldingFeatures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@SpikeLabActivity)
                    .windowLayoutInfo(this@SpikeLabActivity)
                    .collect { layoutInfo ->
                        val folds = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>()
                        foldInfoView.text =
                            if (folds.isEmpty()) {
                                getString(R.string.spike_fold_none)
                            } else {
                                folds.joinToString("\n") { describeFold(it) }
                            }
                    }
            }
        }
    }

    private fun describeFold(fold: FoldingFeature): String =
        "pliegue estado=${fold.state} orientacion=${fold.orientation} " +
            "separa=${fold.isSeparating} oclusion=${fold.occlusionType} bounds=${fold.bounds}"

    // endregion

    // region Spike 2: overlays por display

    private fun rebuildOverlayButtons(displays: Array<Display>) {
        overlayButtonsContainer.removeAllViews()
        displays.forEach { display ->
            overlayButtonsContainer.addView(
                button(getString(R.string.spike_overlay_on_display, display.displayId)) {
                    showOverlayOnDisplay(display.displayId)
                },
            )
        }
    }

    private fun showOverlayOnDisplay(displayId: Int) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.spike_overlay_permission_needed, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            overlayStatusView.text = getString(R.string.spike_display_gone, displayId)
            return
        }
        try {
            val windowContext =
                createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val windowManager = windowContext.getSystemService(WindowManager::class.java)
            val overlayView =
                TextView(windowContext).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#AA3F51B5"))
                    gravity = Gravity.CENTER
                    val pad = dp(24)
                    setPadding(pad, pad, pad, pad)
                }
            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                )
            params.gravity = Gravity.CENTER
            windowManager.addView(overlayView, params)

            // Contador vivo: demuestra que el overlay sigue renderizando.
            val startedAt = System.currentTimeMillis()
            val ticker =
                object : Runnable {
                    override fun run() {
                        val seconds = (System.currentTimeMillis() - startedAt) / 1000
                        overlayView.text = getString(R.string.spike_overlay_text, displayId, seconds)
                        uiHandler.postDelayed(this, 1_000L)
                    }
                }
            uiHandler.post(ticker)
            activeOverlays.add(ActiveOverlay(windowManager, overlayView, ticker))
            overlayStatusView.text = getString(R.string.spike_overlay_added, displayId)
        } catch (e: Exception) {
            // SecurityException, display inválido, etc: se muestra en vez de crashear.
            overlayStatusView.text = getString(R.string.spike_overlay_error, displayId, e.toString())
        }
    }

    private fun removeAllOverlays() {
        activeOverlays.forEach { overlay ->
            uiHandler.removeCallbacks(overlay.ticker)
            try {
                overlay.windowManager.removeView(overlay.view)
            } catch (_: Exception) {
                // La view pudo desaparecer con su display, no es un fallo del spike.
            }
        }
        val removed = activeOverlays.size
        activeOverlays.clear()
        overlayStatusView.text =
            if (removed == 0) {
                getString(R.string.spike_overlay_none)
            } else {
                getString(R.string.spike_overlays_removed, removed)
            }
    }

    // endregion

    // region Spike 3: cámara en uso

    private fun logCameraEvent(
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

    private fun renderCameraLog(events: List<CameraEventLog.CameraEvent>) {
        if (events.isEmpty()) {
            cameraLogView.text = getString(R.string.spike_camera_log_empty)
            return
        }
        // Los más recientes arriba, que es lo cómodo mirando el teléfono.
        cameraLogView.text =
            events.asReversed().joinToString("\n") { event ->
                val time = timeFormat.format(Date(event.timestampMillis))
                val state =
                    if (event.available) {
                        getString(R.string.spike_camera_event_available)
                    } else {
                        getString(R.string.spike_camera_event_unavailable)
                    }
                "$time [${event.source}] cam ${event.cameraId}: $state"
            }
    }

    private fun onStartWatchClicked() {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            startWatchService()
        } else {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startWatchService() {
        startForegroundService(Intent(this, SpikeCameraWatchService::class.java))
    }

    private fun stopWatchService() {
        stopService(Intent(this, SpikeCameraWatchService::class.java))
    }

    // endregion

    companion object {
        /** Etiqueta de origen con la que la activity firma sus eventos en el log. */
        const val SOURCE = "act"
    }
}
