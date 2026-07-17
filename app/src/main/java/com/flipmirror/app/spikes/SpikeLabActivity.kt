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
import android.util.Log
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
 *
 * Además admite órdenes por intent (pensado para adb con el teléfono cerrado,
 * ver [SpikeIntentCommand]):
 * - extra int "overlay_display": intenta el overlay en ese display id llamando
 *   directamente a displayManager.getDisplay(id), sin depender de que la
 *   enumeración lo liste (One UI oculta el display exterior a apps normales).
 * - extra boolean "dump_displays": vuelca el diagnóstico completo de displays.
 * - extra string "camera_watch": "start" o "stop" para el foreground service
 *   [SpikeCameraWatchService], que al no estar exportado no puede arrancarse
 *   desde el shell en Android 16 (la orden entra por esta activity exportada).
 *
 * Todo diagnóstico y todo resultado de overlay se vuelca a logcat con el tag
 * [LOG_TAG] (una línea por dato, prefijo estable para grep) además de la UI.
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
        handleSpikeCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode singleTask: cada am start con extras reutiliza esta
        // instancia y la orden llega aquí en vez de a un onCreate nuevo.
        setIntent(intent)
        handleSpikeCommand(intent)
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

    // region Órdenes por intent (adb)

    private fun handleSpikeCommand(intent: Intent?) {
        val rawCameraWatch = intent?.getStringExtra(SpikeIntentCommand.EXTRA_CAMERA_WATCH)
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay =
                    intent?.getIntExtra(
                        SpikeIntentCommand.EXTRA_OVERLAY_DISPLAY,
                        SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                    ) ?: SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays =
                    intent?.getBooleanExtra(SpikeIntentCommand.EXTRA_DUMP_DISPLAYS, false) ?: false,
                cameraWatch = rawCameraWatch,
            )
        if (rawCameraWatch != null && command.cameraWatch == null) {
            Log.w(LOG_TAG, "watch comando=$rawCameraWatch resultado=invalido")
        }
        if (command.isEmpty) return
        val requestedDisplay = command.overlayDisplayId ?: SpikeIntentCommand.NO_OVERLAY_REQUESTED
        logSpike(
            "intent overlay_display=$requestedDisplay dump_displays=${command.dumpDisplays} " +
                "camera_watch=${rawCameraWatch ?: "ninguno"}",
        )
        if (command.dumpDisplays) {
            refreshDisplays()
        }
        command.overlayDisplayId?.let { showOverlayOnDisplay(it) }
        command.cameraWatch?.let { runCameraWatchCommand(it) }
    }

    private fun runCameraWatchCommand(action: SpikeIntentCommand.CameraWatchAction) {
        val comando = action.name.lowercase()
        try {
            when (action) {
                // A diferencia del botón, no se pide POST_NOTIFICATIONS: con el
                // teléfono cerrado nadie puede responder el diálogo y el service
                // funciona igual sin notificación visible.
                SpikeIntentCommand.CameraWatchAction.START -> startWatchService()
                SpikeIntentCommand.CameraWatchAction.STOP -> stopWatchService()
            }
            logSpike("watch comando=$comando resultado=ok")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException y similares: se registra
            // en vez de crashear, igual que el resto de la instrumentación.
            logSpike("watch comando=$comando resultado=error excepcion=$e")
        }
    }

    private fun logSpike(message: String) {
        Log.i(LOG_TAG, message)
    }

    // endregion

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
        val defaultDisplays = displayManager.displays
        val presentationDisplays =
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        // Consulta directa por id: One UI puede ocultar el display exterior a
        // la enumeración pero seguir resolviéndolo (o no: el null es hallazgo).
        val outerDisplay = displayManager.getDisplay(OUTER_DISPLAY_ID)
        val visibleDisplays =
            (defaultDisplays + presentationDisplays + listOfNotNull(outerDisplay))
                .distinctBy { it.displayId }

        logDisplayDiagnostics(defaultDisplays, presentationDisplays, outerDisplay, visibleDisplays)
        displaysInfoView.text =
            buildDisplaysUiText(defaultDisplays, presentationDisplays, outerDisplay, visibleDisplays)
        rebuildOverlayButtons(visibleDisplays)
    }

    private fun logDisplayDiagnostics(
        defaultDisplays: Array<Display>,
        presentationDisplays: Array<Display>,
        outerDisplay: Display?,
        visibleDisplays: List<Display>,
    ) {
        logSpike("diag displays_default n=${defaultDisplays.size}")
        defaultDisplays.forEach {
            logSpike("diag displays_default id=${it.displayId} nombre=${it.name}")
        }
        logSpike("diag displays_presentation n=${presentationDisplays.size}")
        presentationDisplays.forEach {
            logSpike("diag displays_presentation id=${it.displayId} nombre=${it.name}")
        }
        if (outerDisplay == null) {
            logSpike("diag getDisplay id=$OUTER_DISPLAY_ID resultado=null")
        } else {
            logSpike("diag getDisplay id=$OUTER_DISPLAY_ID resultado=ok nombre=${outerDisplay.name}")
        }
        visibleDisplays.forEach { logSpike(displayLogLine(it)) }
    }

    private fun displayLogLine(display: Display): String {
        val mode = display.mode
        return "diag display id=${display.displayId} nombre=${display.name} " +
            "estado=${stateName(display.state)} " +
            "tam=${mode.physicalWidth}x${mode.physicalHeight} " +
            "flags=${flagNames(display.flags)}"
    }

    private fun buildDisplaysUiText(
        defaultDisplays: Array<Display>,
        presentationDisplays: Array<Display>,
        outerDisplay: Display?,
        visibleDisplays: List<Display>,
    ): String {
        val outerText =
            if (outerDisplay == null) {
                getString(R.string.spike_diag_display_null)
            } else {
                outerDisplay.name
            }
        return buildString {
            appendLine(getString(R.string.spike_diag_default, summarizeDisplayList(defaultDisplays)))
            appendLine(
                getString(R.string.spike_diag_presentation, summarizeDisplayList(presentationDisplays)),
            )
            appendLine(getString(R.string.spike_diag_get_display, OUTER_DISPLAY_ID, outerText))
            appendLine()
            append(visibleDisplays.joinToString("\n\n") { describeDisplay(it) })
        }
    }

    private fun summarizeDisplayList(displays: Array<Display>): String =
        if (displays.isEmpty()) {
            getString(R.string.spike_diag_empty_list)
        } else {
            displays.joinToString(", ") { "${it.displayId}=${it.name}" }
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

    private fun rebuildOverlayButtons(displays: List<Display>) {
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
            logSpike("overlay displayId=$displayId resultado=sin_permiso")
            overlayStatusView.text = getString(R.string.spike_overlay_no_permission, displayId)
            Toast.makeText(this, R.string.spike_overlay_permission_needed, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        // Consulta directa por id, sin pasar por la enumeración: es la vía
        // para llegar al display exterior que One UI oculta a las apps.
        val display = displayManager.getDisplay(displayId)
        if (display == null) {
            logSpike("overlay displayId=$displayId resultado=display_null")
            overlayStatusView.text = getString(R.string.spike_overlay_display_null, displayId)
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
            logSpike("overlay displayId=$displayId resultado=ok")
            overlayStatusView.text = getString(R.string.spike_overlay_added, displayId)
        } catch (e: Exception) {
            // SecurityException, BadTokenException, display inválido, etc:
            // se registra y se muestra en vez de crashear.
            logSpike("overlay displayId=$displayId resultado=error excepcion=$e")
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

        /** Tag de logcat para todo el diagnóstico: adb logcat -s FlipSpike. */
        const val LOG_TAG = "FlipSpike"

        /** Id lógico del display exterior en los Samsung Flip. */
        const val OUTER_DISPLAY_ID = 1
    }
}
