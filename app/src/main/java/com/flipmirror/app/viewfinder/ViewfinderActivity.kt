package com.flipmirror.app.viewfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
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
 * Suma también el cambio de lente trasera WIDE (principal) <-> ULTRAWIDE. Las
 * lentes se enumeran desde las CameraInfo del provider, distinguiéndolas por su
 * focal (interop Camera2) con la lógica pura de [LensSelection]; al togglear se
 * rebindea el mismo Preview con el CameraSelector de la lente elegida. El cambio
 * de lente y el toggle de encuadre son independientes y coexisten: el encuadre
 * es presentación de la vista y la lente es qué cámara física alimenta el
 * surface. Si el dispositivo no expone ultrawide, el toggle de lente se
 * deshabilita (degradación con gracia). Además corrige la ORIENTACION del
 * preview de la ultrawide: en el Flip 5 esa lente entrega el buffer girado 180
 * pese a declarar el mismo SENSOR_ORIENTATION que la wide, algo que CameraX no
 * puede compensar solo. La corrección es SENSIBLE A LA PANTALLA: ese quirk solo
 * se manifiesta en la cover, así que la ultrawide se rota 180 ahí y 0 en la
 * pantalla principal; la wide sale derecha (0) en ambas. La lógica pura
 * [PreviewOrientation] decide los grados a partir de la pantalla actual (cover o
 * principal) y las orientaciones reales de ambas lentes, y se recalcula en cada
 * bind: al togglear lente y al plegar/desplegar (que salta de display).
 *
 * Fuera de alcance en este PR (llega en un PR siguiente del Sprint 1): captura de
 * foto (ImageCapture). El binding se arma con un [UseCaseGroup] justamente para
 * poder sumar ese caso de uso después sin reescribir esta clase.
 */
class ViewfinderActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var frameToggle: Button
    private lateinit var lensToggle: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var hasCameraPermission = false
    private var currentPosture: FoldPosture? = null

    // Modo de encuadre del preview. Solo cambia la presentación en la cover; no
    // toca la captura. Puede preseleccionarse por un extra de intent (QA por adb).
    private var previewMode: PreviewFraming.Mode = PreviewFraming.Mode.DEFAULT

    // Lentes traseras detectadas (qué camera id es wide y cuál ultrawide). Se
    // resuelve al tener el provider listo; arranca vacío para que el visor no
    // crashee si la enumeración falla.
    private var lensOptions: LensSelection.LensOptions = LensSelection.LensOptions(null, null)

    // SENSOR_ORIENTATION (grados) por camera id, capturado al enumerar. Alimenta
    // la corrección de orientación del preview ([PreviewOrientation]): en el
    // Flip 5 la wide y la ultrawide declaran el mismo valor pese a que la
    // ultrawide entrega el buffer girado 180, y CameraX no puede compensarlo solo.
    private val sensorOrientations = mutableMapOf<String, Int>()

    // Lente pedida por el usuario o el intent (puede ser ULTRAWIDE aún antes de
    // saber si el equipo la tiene) y lente realmente bindeada (recortada a lo
    // disponible). Separarlas permite honrar una preselección de ultrawide una
    // vez que la enumeración confirma que existe.
    private var requestedLens: LensSelection.Lens = LensSelection.Lens.DEFAULT
    private var activeLens: LensSelection.Lens = LensSelection.Lens.DEFAULT

    // Corrección de orientación (grados horarios) vigente para la lente activa,
    // decidida por [PreviewOrientation] en cada bind. Se guarda para poder
    // REAPLICARLA tras el reencuadre (resize del preview) y tras cada rebind de
    // lente/postura, de modo que ni el toggle 1:1<->9:16 ni el plegado dejen la
    // ultrawide invertida.
    private var previewCorrectionDegrees = 0

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
        // Modo COMPATIBLE (TextureView) en vez del PERFORMANCE (SurfaceView) por
        // defecto. Es la clave para que la corrección de orientación de la
        // ultrawide se APLIQUE al contenido que se ve, no solo a la caja de la
        // vista. En PERFORMANCE el preview vive en un surface aparte que compone
        // el hardware, y rotar la View (previewView.rotation) NO gira ese
        // surface: por eso la ultrawide seguía de cabeza pese a la corrección de
        // 180. Con TextureView el preview se dibuja dentro de la jerarquía de
        // vistas, así que la rotación de la View sí gira el contenido renderizado
        // de forma confiable. Se fija antes de proveer el surface (antes del
        // primer bind), que es cuando el modo debe estar decidido.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        statusView = findViewById(R.id.viewfinder_status)
        frameToggle = findViewById(R.id.viewfinder_frame_toggle)
        lensToggle = findViewById(R.id.viewfinder_lens_toggle)

        applyControlsInsets(findViewById(R.id.viewfinder_controls))

        frameToggle.setOnClickListener { togglePreviewMode() }
        lensToggle.setOnClickListener { toggleLens() }

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

        // Preselección de la lente por adb, ej.:
        //   ... --es lens ultrawide
        // Se guarda como pedido; se aplica cuando la enumeración confirma qué
        // lentes hay. Un valor ausente o desconocido arranca en la wide.
        requestedLens = LensSelection.Lens.fromExtra(intent?.getStringExtra(LensSelection.EXTRA_LENS))
        updateLensToggle()

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
        val frameRaw = intent.getStringExtra(PreviewFraming.EXTRA_PREVIEW_FRAME)
        if (frameRaw != null) {
            setPreviewMode(PreviewFraming.Mode.fromExtra(frameRaw))
        }
        val lensRaw = intent.getStringExtra(LensSelection.EXTRA_LENS)
        if (lensRaw != null) {
            setLens(LensSelection.Lens.fromExtra(lensRaw))
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
                    val provider = future.get()
                    cameraProvider = provider
                    // Enumeramos las lentes traseras una vez, con el provider ya
                    // listo, y recortamos la lente pedida a lo realmente
                    // disponible antes del primer bind.
                    lensOptions = detectLensOptions(provider)
                    activeLens = lensOptions.effective(requestedLens)
                    updateLensToggle()
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
            // La rotación del display donde renderiza la Activity (la cover,
            // display 1, con el equipo plegado). Se pasa como targetRotation del
            // Preview para que CameraX + PreviewView calculen la transformación
            // correcta POR cámara, en vez de asumir el display por defecto.
            val displayRotation = display?.rotation ?: Surface.ROTATION_0
            val preview =
                Preview.Builder()
                    .setTargetRotation(displayRotation)
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
            val camera =
                provider.bindToLifecycle(
                    this,
                    selectorForLens(activeLens),
                    useCases,
                )
            val sensorRotation = camera.cameraInfo.sensorRotationDegrees

            // Pantalla donde renderiza la Activity AHORA. La cover del Flip es un
            // display externo (id 1); la principal es el default (id 0). Al plegar
            // o desplegar el visor salta de display y este bind lo recalcula, así
            // que la corrección siempre usa la pantalla vigente. Es la fuente
            // directa y sin carreras de "en qué pantalla está el preview".
            val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
            val isCoverScreen = currentDisplayId != Display.DEFAULT_DISPLAY

            // Corrección de orientación del preview. Depende de la PANTALLA y de la
            // lente: la wide sale derecha en ambas (0); la ultrawide del Flip 5
            // entrega el buffer girado 180 (declara el mismo SENSOR_ORIENTATION que
            // la wide, algo que CameraX no puede ver) y ese quirk solo se manifiesta
            // en la cover, donde hay que rotarla 180; en la principal ya sale
            // derecha (0). [PreviewOrientation] decide los grados a partir de la
            // pantalla actual y las orientaciones reales de ambas lentes.
            val correction =
                PreviewOrientation.correctionDegrees(
                    activeLens,
                    isCoverScreen,
                    lensOptions.wideCameraId?.let { sensorOrientations[it] },
                    lensOptions.ultrawideCameraId?.let { sensorOrientations[it] },
                )
            previewCorrectionDegrees = correction
            applyPreviewCorrection()

            Log.i(
                LOG_TAG,
                "bind lente=$activeLens id=${lensOptions.cameraIdFor(activeLens)} " +
                    "sensorRotationDegrees=$sensorRotation displayRotation=$displayRotation " +
                    "displayId=$currentDisplayId coverScreen=$isCoverScreen " +
                    "correccionPreview=$correction",
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

    /** Alterna la lente wide <-> ultrawide. No hace nada si no hay ultrawide. */
    private fun toggleLens() {
        if (!lensOptions.canToggle) return
        applyRequestedLens(activeLens.toggled())
    }

    /** Fija la lente pedida (usado por el extra de intent en QA por adb). */
    private fun setLens(lens: LensSelection.Lens) = applyRequestedLens(lens)

    /**
     * Aplica la lente pedida: la recorta a lo disponible ([LensOptions.effective]),
     * actualiza el botón y rebindea la cámara solo si la lente activa cambió.
     * Preview y encuadre no se tocan: el toggle de lente es independiente del de
     * encuadre y ambos coexisten.
     */
    private fun applyRequestedLens(lens: LensSelection.Lens) {
        requestedLens = lens
        val effective = lensOptions.effective(lens)
        val changed = effective != activeLens
        activeLens = effective
        updateLensToggle()
        Log.i(
            LOG_TAG,
            "lente pedida=$requestedLens activa=$activeLens id=${lensOptions.cameraIdFor(activeLens)}",
        )
        if (changed) bindCamera()
    }

    /**
     * Etiqueta y estado del botón de lente. Muestra la lente activa (W / UW) y se
     * deshabilita (semitransparente) cuando el dispositivo no expone ultrawide,
     * degradando con gracia a solo wide.
     */
    private fun updateLensToggle() {
        val canToggle = lensOptions.canToggle
        lensToggle.isEnabled = canToggle
        lensToggle.alpha = if (canToggle) 1f else DISABLED_ALPHA
        lensToggle.setText(
            when (activeLens) {
                LensSelection.Lens.WIDE -> R.string.viewfinder_lens_wide
                LensSelection.Lens.ULTRAWIDE -> R.string.viewfinder_lens_ultrawide
            },
        )
    }

    /**
     * Enumera las cámaras del provider, las describe de forma neutral (id + focal
     * + facing, leyendo la focal por interop Camera2) y delega la clasificación
     * wide/ultrawide en la lógica pura [LensSelection]. Loguea cada cámara y el
     * resultado para poder confirmar por adb a qué id bindea cada lente.
     */
    private fun detectLensOptions(provider: ProcessCameraProvider): LensSelection.LensOptions {
        val infos = provider.availableCameraInfos
        // Se repuebla la orientación de sensor por cámara en cada enumeración
        // (describeCamera la registra) para no arrastrar datos viejos.
        sensorOrientations.clear()
        val descriptors = infos.mapNotNull { describeCamera(it) }
        val defaultBackId =
            try {
                CameraSelector.DEFAULT_BACK_CAMERA
                    .filter(infos)
                    .firstOrNull()
                    ?.let { Camera2CameraInfo.from(it).cameraId }
            } catch (e: IllegalArgumentException) {
                // Sin cámara trasera default: se resuelve la wide por focal.
                Log.w(LOG_TAG, "sin default back camera", e)
                null
            }
        val options = LensSelection.analyze(descriptors, defaultBackId)
        Log.i(
            LOG_TAG,
            "lentes traseras: wide=${options.wideCameraId} ultrawide=${options.ultrawideCameraId} " +
                "defaultBack=$defaultBackId canToggle=${options.canToggle}",
        )
        return options
    }

    /**
     * Traduce una [CameraInfo] de CameraX a un [LensSelection.CameraDescriptor]
     * neutral. Aísla el interop Camera2 acá, para que la lógica de selección
     * quede libre de tipos de Android. De paso registra el SENSOR_ORIENTATION de
     * la cámara en [sensorOrientations], que alimenta la corrección de
     * orientación del preview. Devuelve null si la cámara no se pudo describir,
     * para no romper la enumeración.
     */
    private fun describeCamera(info: CameraInfo): LensSelection.CameraDescriptor? =
        try {
            val camera2Info = Camera2CameraInfo.from(info)
            val id = camera2Info.cameraId
            val facing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
            val focals =
                camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                )
            val minFocal = focals?.minOrNull() ?: Float.NaN
            val sensorOrientation =
                camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
            if (sensorOrientation != null) sensorOrientations[id] = sensorOrientation
            Log.i(
                LOG_TAG,
                "camara id=$id facing=$facing focalMin=$minFocal " +
                    "sensorOrientation=$sensorOrientation focals=${focals?.joinToString()}",
            )
            LensSelection.CameraDescriptor(id = id, minFocalLengthMm = minFocal, isBackFacing = isBack)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo describir la cámara", e)
            null
        }

    /**
     * CameraSelector para la lente dada. Si se conoce su camera id, filtra a esa
     * cámara física; si no (lente no disponible), cae en la default back para no
     * quedar sin cámara.
     */
    private fun selectorForLens(lens: LensSelection.Lens): CameraSelector {
        val id = lensOptions.cameraIdFor(lens)
        return if (id != null) selectorForCameraId(id) else CameraSelector.DEFAULT_BACK_CAMERA
    }

    /** CameraSelector que se queda con la cámara física de camera id [cameraId]. */
    private fun selectorForCameraId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
            }
            .build()

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
        // El reencuadre solo cambia el TAMAÑO de la vista; la rotación de
        // corrección es una propiedad aparte que no debería perderse al tocar los
        // layoutParams. Aun así la reafirmamos acá para blindar el orden: tras
        // cualquier resize del preview la lente activa queda derecha en ambos
        // encuadres (1:1 y 9:16). La corrección vigente es 0 o 180, siempre en
        // torno al centro, así el bounding box no cambia y la imagen no se
        // deforma ni se recorta distinto.
        applyPreviewCorrection()
    }

    /**
     * Aplica la corrección de orientación vigente ([previewCorrectionDegrees]) a
     * la PreviewView. Se llama tras cada bind (que la recalcula) y tras cada
     * reencuadre, para garantizar que ni el resize del preview ni el rebind de
     * lente/postura pierdan la rotación de la ultrawide. Con la PreviewView en
     * modo COMPATIBLE (TextureView) esta rotación de vista sí gira el contenido
     * renderizado, no solo la caja.
     */
    private fun applyPreviewCorrection() {
        previewView.rotation = previewCorrectionDegrees.toFloat()
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

        // Opacidad del botón de lente cuando el equipo no tiene ultrawide y el
        // toggle queda deshabilitado.
        const val DISABLED_ALPHA = 0.4f

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
