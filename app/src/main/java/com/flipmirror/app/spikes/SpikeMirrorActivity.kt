package com.flipmirror.app.spikes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.flipmirror.app.R

/**
 * Spike 3b: lanzador del espejo de pantalla. Pide el consentimiento de
 * MediaProjection (diálogo del sistema) y arranca [SpikeMirrorService] con el
 * resultCode más data para que este monte la captura sobre el display 1.
 *
 * Admite arranque por adb con el extra boolean "auto_start": dispara el mismo
 * flujo que el botón de iniciar. Ojo: el diálogo de consentimiento del sistema
 * SIEMPRE exige un toque humano (no es automatizable por adb) y en Android 14+
 * el consentimiento es por sesión: cada arranque del espejo vuelve a pedirlo.
 */
class SpikeMirrorActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusView: TextView

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.spike_notifications_denied, Toast.LENGTH_LONG).show()
            }
            // Seguimos igual: sin notificación visible el espejo funciona.
            launchCaptureConsent()
        }

    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                logSpike("mirror consent resultado=ok")
                setStatus(getString(R.string.spike_mirror_status_started))
                startMirrorService(result.resultCode, data)
            } else {
                logSpike("mirror consent resultado=denegado resultCode=${result.resultCode}")
                setStatus(getString(R.string.spike_mirror_status_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Igual que el visor real: usable con el equipo bloqueado o cerrado.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        projectionManager = getSystemService(MediaProjectionManager::class.java)
        setContentView(buildUi())
        setStatus(getString(R.string.spike_mirror_status_idle))
        handleAutoStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode singleTask: los am start repetidos llegan aquí.
        setIntent(intent)
        handleAutoStart(intent)
    }

    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            logSpike("mirror intent auto_start=true")
            onStartMirrorClicked()
        }
    }

    // region UI

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
            }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.spike_mirror_title)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, 0, 0, dp(8))
            },
        )
        root.addView(button(getString(R.string.spike_mirror_start)) { onStartMirrorClicked() })
        root.addView(button(getString(R.string.spike_mirror_stop)) { onStopMirrorClicked() })
        statusView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(8))
            }
        root.addView(statusView)
        return ScrollView(this).apply { addView(root) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun button(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // endregion

    // region Flujo de captura

    private fun onStartMirrorClicked() {
        val notificationsGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (notificationsGranted) {
            launchCaptureConsent()
        } else {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchCaptureConsent() {
        setStatus(getString(R.string.spike_mirror_status_waiting_consent))
        logSpike("mirror consent solicitado")
        captureConsent.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startMirrorService(
        resultCode: Int,
        data: Intent,
    ) {
        val serviceIntent =
            Intent(this, SpikeMirrorService::class.java).apply {
                action = SpikeMirrorService.ACTION_START
                putExtra(SpikeMirrorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(SpikeMirrorService.EXTRA_RESULT_DATA, data)
            }
        startForegroundService(serviceIntent)
    }

    private fun onStopMirrorClicked() {
        logSpike("mirror stop solicitado_desde_activity")
        startService(
            Intent(this, SpikeMirrorService::class.java).setAction(SpikeMirrorService.ACTION_STOP),
        )
        setStatus(getString(R.string.spike_mirror_status_stopped))
    }

    // endregion

    private fun logSpike(message: String) {
        Log.i(SpikeLabActivity.LOG_TAG, message)
    }

    companion object {
        /**
         * Extra boolean para adb: si true, lanza el flujo de inicio al abrir.
         * El diálogo de consentimiento del sistema igual pide un toque humano.
         */
        const val EXTRA_AUTO_START = "auto_start"
    }
}
