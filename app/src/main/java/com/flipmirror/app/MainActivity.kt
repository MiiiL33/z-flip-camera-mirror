package com.flipmirror.app

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Spike 2: visor CameraX mínimo pensado para correr en la pantalla exterior
 * (cover screen) del Z Flip con el teléfono cerrado.
 *
 * Esta Activity no aparece sola en la cover screen: el usuario debe habilitarla
 * manualmente en Ajustes > Labs (One UI 8) o vía Good Lock > MultiStar. Una vez
 * habilitada, al cerrar el teléfono el sistema la muestra en el display exterior
 * y las cámaras traseras quedan disponibles como si fueran cámara selfie.
 */
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El visor debe funcionar con el equipo bloqueado, que es el flujo real
        // con el teléfono cerrado en la cover screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view)

        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            },
            ContextCompat.getMainExecutor(this),
        )
    }
}
