package com.flipmirror.app.viewfinder

/**
 * Postura física del Z Flip derivada de las FoldingFeature de Jetpack
 * WindowManager. Es lógica pura (sin tipos de Android) para poder cubrirla con
 * unit tests en JVM, al estilo de [com.flipmirror.app.spikes.MirrorGeometry] y
 * [com.flipmirror.app.spikes.SpikeIntentCommand].
 *
 * El visor solo necesita esta clasificación para decidir cuándo rebindear la
 * cámara y, más adelante, cómo adaptar el encuadre; aislarla del ciclo de vida
 * de la Activity la hace testeable sin instrumentación.
 */
enum class FoldPosture {
    /**
     * Sin FoldingFeature activa: el teléfono está cerrado y el visor corre en
     * la cover screen (el escenario de producto), o el dispositivo no es
     * plegable. androidx.window no reporta ninguna bisagra en este caso.
     */
    FOLDED,

    /** Bisagra a medio abrir (flex / tent mode). */
    HALF_OPEN,

    /** Teléfono completamente abierto y plano. */
    FLAT,
    ;

    /**
     * true cuando conviene tratar el visor como la pantalla exterior casi
     * cuadrada de la cover. Hoy solo es la postura cerrada; queda como punto
     * único para la adaptación de layout de PRs futuros del Sprint 1.
     */
    val isCoverScreenLikely: Boolean
        get() = this == FOLDED

    /** Estado crudo de la bisagra, desacoplado de androidx.window.FoldingFeature.State. */
    enum class HingeState {
        FLAT,
        HALF_OPENED,
    }

    companion object {
        /**
         * Deriva la postura a partir del estado de bisagra reportado por
         * androidx.window. `null` significa que no hay ninguna FoldingFeature
         * (teléfono cerrado en la cover, o dispositivo no plegable) y se
         * interpreta como [FOLDED].
         */
        fun fromHingeState(state: HingeState?): FoldPosture =
            when (state) {
                null -> FOLDED
                HingeState.FLAT -> FLAT
                HingeState.HALF_OPENED -> HALF_OPEN
            }
    }
}
