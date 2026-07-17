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
) {
    /** true cuando el intent no traía ninguna orden que ejecutar. */
    val isEmpty: Boolean
        get() = overlayDisplayId == null && !dumpDisplays

    companion object {
        /** Extra int: display id donde intentar el overlay (default -1, sin orden). */
        const val EXTRA_OVERLAY_DISPLAY = "overlay_display"

        /** Extra boolean: si true, vuelca el diagnóstico completo de displays. */
        const val EXTRA_DUMP_DISPLAYS = "dump_displays"

        /** Valor por defecto del extra de overlay: no se pidió overlay. */
        const val NO_OVERLAY_REQUESTED = -1

        /**
         * Interpreta los valores crudos de los extras. Cualquier id de display
         * negativo (incluido el default -1) se trata como "sin orden de overlay".
         */
        fun fromRawExtras(
            overlayDisplay: Int,
            dumpDisplays: Boolean,
        ): SpikeIntentCommand =
            SpikeIntentCommand(
                overlayDisplayId = overlayDisplay.takeIf { it >= 0 },
                dumpDisplays = dumpDisplays,
            )
    }
}
