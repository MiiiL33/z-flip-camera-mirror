package com.flipmirror.app.viewfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.flipmirror.app.R
import kotlinx.coroutines.launch

/**
 * Visor de cámara de producto (Sprint 1). Es el cimiento del visor: preview de
 * CameraX bindeada al lifecycle, pensada para renderizar en la cover screen del
 * Z Flip con el teléfono cerrado (habilitada por el usuario vía Good Lock +
 * MultiStar, como validó el Spike 1).
 *
 * Reutiliza el patrón del Spike 2 ([com.flipmirror.app.MainActivity]) de
 * showWhenLocked/turnScreenOn para funcionar con el equipo bloqueado, y observa
 * las FoldingFeature de Jetpack WindowManager (patrón del laboratorio de spikes,
 * [com.flipmirror.app.spikes.SpikeLabActivity]) para reaccionar a los cambios de
 * postura sin quedar en negro ni crashear.
 *
 * Suma el toggle de encuadre del preview (1:1 / 9:16). Es un asistente de
 * ENCUADRE: el toggle solo cambia cómo se VE el preview en la cover, y está
 * desacoplado de la captura. El encuadre es una decisión de PRESENTACIÓN a nivel
 * de la vista ([PreviewFraming] redimensiona la [PreviewView] dentro de la
 * cover), no un ViewPort ni un crop de CameraX. Así, cuando se sume ImageCapture
 * en un PR futuro, la captura seguirá siendo nativa y completa (9:16 u otra),
 * sin recortarse a lo que muestra la cover.
 *
 * Fuera de alcance en este PR (llegan en PRs siguientes del Sprint 1): cambio de
 * lente wide/ultrawide y captura de foto (ImageCapture). El binding se arma con
 * un [UseCaseGroup] justamente para poder sumar ese caso de uso después sin
 * reescribir esta clase.
 */
class ViewfinderActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var frameToggle: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var hasCameraPermission = false
    private var currentPosture: FoldPosture? = null

    // Modo de encuadre del preview. Solo cambia la presentación en la cover; no
    // toca la captura. Puede preseleccionarse por un extra de intent (QA por adb).
    private var previewMode: PreviewFraming.Mode = PreviewFraming.Mode.DEFAULT

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
            if (granted) {
                initCameraProvider()
            } else {
                showStatus(getString(R.string.viewfinder_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El visor debe funcionar con el equipo bloqueado, que es el flujo real
        // con el teléfono cerrado en la cover screen (mismo patrón del Spike 2).
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Dibujamos de borde a borde y dejamos que el contenido pueda entrar
        // bajo el recorte de las cámaras. Así el preview llena toda la cover;
        // los controles, en cambio, se mantienen dentro del área segura vía los
        // WindowInsets que aplicamos más abajo.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes =
            window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

        setContentView(R.layout.activity_viewfinder)
        previewView = findViewById(R.id.viewfinder_preview)
        statusView = findViewById(R.id.viewfinder_status)
        frameToggle = findViewById(R.id.viewfinder_frame_toggle)

        applyControlsInsets(findViewById(R.id.viewfinder_controls))

        frameToggle.setOnClickListener { togglePreviewMode() }

        // El tamaño real de la cover se conoce recién tras el layout, y puede
        // cambiar al saltar de display (plegar/desplegar). Reaplicamos el
        // encuadre cada vez que el contenedor se redimensiona.
        val frameContainer = previewView.parent as View
        frameContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewFraming()
        }

        // Preselección del modo por adb para QA determinista, ej.:
        // am start --display 1 -n .../.viewfinder.ViewfinderActivity \
        //   --es preview_frame 9x16
        // Un valor ausente o desconocido arranca en el modo por defecto (1:1).
        setPreviewMode(PreviewFraming.Mode.fromExtra(intent?.getStringExtra(PreviewFraming.EXTRA_PREVIEW_FRAME)))

        observeFoldPosture()
        ensureCameraPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Un `am start` repetido con el visor ya vivo llega acá en vez de a
        // onCreate. Aplicamos el modo solo si el intent trae el extra, para que
        // QA por adb pueda alternar el encuadre sin force-stop y sin pisar el
        // modo elegido a mano cuando el intent no lo especifica.
        setIntent(intent)
        val raw = intent.getStringExtra(PreviewFraming.EXTRA_PREVIEW_FRAME)
        if (raw != null) {
            setPreviewMode(PreviewFraming.Mode.fromExtra(raw))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Declaramos configChanges en el manifest, así que el sistema no recrea
        // la Activity al plegar/desplegar, rotar o cambiar de display; se nos
        // avisa acá. Rebindeamos para reengancharnos al surface/display actual
        // y no quedar en negro.
        bindCamera()
        // El display pudo cambiar de tamaño (cover <-> principal): reajustamos
        // el encuadre. El listener de layout lo reaplica igual cuando el
        // contenedor se relayouta; esto solo acelera el caso inmediato.
        applyPreviewFraming()
    }

    /**
     * Empuja los controles (hoy solo el botón de encuadre) al área segura de la
     * cover. Combina los insets de las barras del sistema (incluida la de
     * navegación, abajo a la izquierda) con los del recorte de las cámaras
     * (abajo a la derecha) y los aplica como padding del contenedor. El preview,
     * que es hermano y no recibe este padding, sigue llenando toda la cover. Se
     * loguean los insets detectados para poder verificarlos por adb en QA.
     */
    private fun applyControlsInsets(controls: View) {
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            Log.i(LOG_TAG, "insets seguros=$safe cutout=$cutout systemBars=$bars")
            insets
        }
    }

    private fun ensureCameraPermission() {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            hasCameraPermission = true
            initCameraProvider()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initCameraProvider() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    bindCamera()
                } catch (e: Exception) {
                    // El provider puede fallar si no hay servicio de cámara
                    // disponible; se reporta en vez de crashear.
                    Log.e(LOG_TAG, "no se pudo obtener el ProcessCameraProvider", e)
                    showStatus(getString(R.string.viewfinder_camera_unavailable))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /**
     * (Re)vincula el caso de uso de preview al lifecycle. Es idempotente: se
     * llama al conceder el permiso, al tener listo el provider y en cada cambio
     * de postura o configuración/display, para no quedar en negro.
     */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (!hasCameraPermission) return
        try {
            val preview =
                Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // UseCaseGroup con un solo caso de uso hoy: deja el binding listo
            // para sumar ImageCapture y un ViewPort (toggle 1:1 / 9:16) en PRs
            // futuros del Sprint 1 sin reescribir este método.
            val useCases =
                UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                useCases,
            )
            hideStatus()
        } catch (e: Exception) {
            // Cámara no disponible, en uso por otra app, o combinación de casos
            // de uso no soportada: se muestra el estado en vez de crashear.
            Log.e(LOG_TAG, "fallo al vincular la cámara", e)
            showStatus(getString(R.string.viewfinder_camera_unavailable))
        }
    }

    private fun observeFoldPosture() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@ViewfinderActivity)
                    .windowLayoutInfo(this@ViewfinderActivity)
                    .collect { layoutInfo ->
                        val hinge =
                            layoutInfo.displayFeatures
                                .filterIsInstance<FoldingFeature>()
                                .firstOrNull()
                        onPostureChanged(FoldPosture.fromHingeState(hinge.toHingeState()))
                    }
            }
        }
    }

    private fun onPostureChanged(posture: FoldPosture) {
        if (posture == currentPosture) return
        currentPosture = posture
        Log.i(LOG_TAG, "postura=$posture coverScreen=${posture.isCoverScreenLikely}")
        // Al cambiar de postura el visor puede saltar entre displays (cover y
        // principal). Rebindeamos para reengancharnos al surface actual.
        bindCamera()
    }

    private fun togglePreviewMode() = setPreviewMode(previewMode.toggled())

    /**
     * Fija el modo de encuadre del preview: solo cambia la presentación en la
     * cover, nunca la captura. Actualiza la etiqueta del botón y reajusta el
     * rectángulo del preview. Loguea el modo para poder confirmar el toggle por
     * adb en QA.
     */
    private fun setPreviewMode(mode: PreviewFraming.Mode) {
        previewMode = mode
        updateFrameToggleLabel()
        applyPreviewFraming()
        Log.i(LOG_TAG, "encuadre preview=$mode")
    }

    /** El texto del botón ES el indicador discreto del modo activo. */
    private fun updateFrameToggleLabel() {
        frameToggle.setText(
            when (previewMode) {
                PreviewFraming.Mode.SQUARE -> R.string.viewfinder_frame_square
                PreviewFraming.Mode.PORTRAIT_9_16 -> R.string.viewfinder_frame_portrait
            },
        )
    }

    /**
     * Redimensiona la [PreviewView] al rectángulo de presentación del modo
     * actual, calculado con [PreviewFraming] sobre el tamaño real de la cover.
     * Al ir centrada, el 9:16 deja barras laterales (pillarbox) del fondo negro
     * y el 1:1 desborda y el padre lo recorta, llenando la cover. No rebindea la
     * cámara: solo cambia cómo se ve el mismo surface, desacoplado de la captura.
     */
    private fun applyPreviewFraming() {
        val container = previewView.parent as? View ?: return
        val coverWidth = container.width
        val coverHeight = container.height
        if (coverWidth <= 0 || coverHeight <= 0) return

        val frame = PreviewFraming.presentationFrame(previewMode, coverWidth, coverHeight)
        val params = previewView.layoutParams as FrameLayout.LayoutParams
        if (params.width != frame.width ||
            params.height != frame.height ||
            params.gravity != Gravity.CENTER
        ) {
            params.width = frame.width
            params.height = frame.height
            params.gravity = Gravity.CENTER
            previewView.layoutParams = params
        }
    }

    private fun showStatus(message: String) {
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusView.visibility = View.GONE
    }

    private companion object {
        const val LOG_TAG = "FlipViewfinder"

        /**
         * Traduce la FoldingFeature de androidx.window al estado crudo de la
         * lógica pura. Vive acá (no en [FoldPosture]) para mantener esa clase
         * libre de tipos de Android.
         */
        private fun FoldingFeature?.toHingeState(): FoldPosture.HingeState? =
            when (this?.state) {
                FoldingFeature.State.FLAT -> FoldPosture.HingeState.FLAT
                FoldingFeature.State.HALF_OPENED -> FoldPosture.HingeState.HALF_OPENED
                else -> null
            }
    }
}
