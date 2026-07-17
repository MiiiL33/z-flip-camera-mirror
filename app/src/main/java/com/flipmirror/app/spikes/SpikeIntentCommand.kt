package com.flipmirror.app.spikes

/**
 * Orden recibida por intent en [SpikeLabActivity], pensada para disparar los
 * spikes desde adb con el teléfono cerrado (sin poder tocar la pantalla).
 *
 * El parsing de los valores crudos de los extras vive aquí como función pura
 * para poder cubrirlo con unit tests sin framework de Android.
 */
data class SpikeIntentCommand(
    /** Display destino del overlay, o null si no se pidió overlay. */
    val overlayDisplayId: Int?,
    /** true si se pidió el volcado de diagnóstico de displays. */
    val dumpDisplays: Boolean,
    /** Acción sobre el service de vigilancia, o null si no se pidió (o el valor era inválido). */
    val cameraWatch: CameraWatchAction?,
) {
    /** true cuando el intent no traía ninguna orden que ejecutar. */
    val isEmpty: Boolean
        get() = overlayDisplayId == null && !dumpDisplays && cameraWatch == null

    /** Acción pedida por intent sobre el service de vigilancia de cámara. */
    enum class CameraWatchAction {
        START,
        STOP,
    }

    companion object {
        /** Extra int: display id donde intentar el overlay (default -1, sin orden). */
        const val EXTRA_OVERLAY_DISPLAY = "overlay_display"

        /** Extra boolean: si true, vuelca el diagnóstico completo de displays. */
        const val EXTRA_DUMP_DISPLAYS = "dump_displays"

        /** Extra string: "start" o "stop" para el service de vigilancia de cámara. */
        const val EXTRA_CAMERA_WATCH = "camera_watch"

        /** Valor por defecto del extra de overlay: no se pidió overlay. */
        const val NO_OVERLAY_REQUESTED = -1

        /** Valor del extra camera_watch que arranca el service de vigilancia. */
        const val CAMERA_WATCH_START = "start"

        /** Valor del extra camera_watch que detiene el service de vigilancia. */
        const val CAMERA_WATCH_STOP = "stop"

        /**
         * Interpreta los valores crudos de los extras. Cualquier id de display
         * negativo (incluido el default -1) se trata como "sin orden de overlay"
         * y cualquier valor de camera_watch distinto de "start"/"stop" (o su
         * ausencia) como "sin orden para el service".
         */
        fun fromRawExtras(
            overlayDisplay: Int,
            dumpDisplays: Boolean,
            cameraWatch: String?,
        ): SpikeIntentCommand =
            SpikeIntentCommand(
                overlayDisplayId = overlayDisplay.takeIf { it >= 0 },
                dumpDisplays = dumpDisplays,
                cameraWatch = parseCameraWatch(cameraWatch),
            )

        /** Traduce el valor crudo del extra camera_watch, null si no es una orden válida. */
        fun parseCameraWatch(raw: String?): CameraWatchAction? =
            when (raw) {
                CAMERA_WATCH_START -> CameraWatchAction.START
                CAMERA_WATCH_STOP -> CameraWatchAction.STOP
                else -> null
            }
    }
}
