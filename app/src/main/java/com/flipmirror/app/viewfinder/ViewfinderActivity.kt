package com.flipmirror.app.viewfinder

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
 * Fuera de alcance en este PR (llegan en PRs siguientes del Sprint 1): cambio de
 * lente wide/ultrawide, captura de foto (ImageCapture) y el toggle de encuadre
 * 1:1 / 9:16. El binding se arma con un [UseCaseGroup] justamente para poder
 * sumar esos casos de uso y un ViewPort después sin reescribir esta clase.
 */
class ViewfinderActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var hasCameraPermission = false
    private var currentPosture: FoldPosture? = null

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

        setContentView(R.layout.activity_viewfinder)
        previewView = findViewById(R.id.viewfinder_preview)
        statusView = findViewById(R.id.viewfinder_status)

        observeFoldPosture()
        ensureCameraPermission()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Declaramos configChanges en el manifest, así que el sistema no recrea
        // la Activity al plegar/desplegar, rotar o cambiar de display; se nos
        // avisa acá. Rebindeamos para reengancharnos al surface/display actual
        // y no quedar en negro.
        bindCamera()
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
