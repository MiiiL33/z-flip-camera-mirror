package com.flipmirror.app.viewfinder

import com.flipmirror.app.spikes.MirrorGeometry
import com.flipmirror.app.spikes.MirrorGeometry.LetterboxFrame

/**
 * Encuadre de PRESENTACION del preview en la cover. Es lógica pura (sin tipos de
 * Android) para poder cubrirla con unit tests en JVM, al estilo de
 * [com.flipmirror.app.spikes.MirrorGeometry] y [FoldPosture].
 *
 * Punto clave del modelo de producto: la cover es un asistente de ENCUADRE (un
 * espejo para componer la selfie con las cámaras traseras y el teléfono
 * plegado), no el lugar donde se guarda la foto. Por eso este cálculo decide
 * únicamente cómo se VE el preview dentro de la cover; está por completo
 * DESACOPLADO de la captura. No es un ViewPort ni un crop de CameraX: es un
 * rectángulo de presentación a nivel de la vista. Cuando se sume ImageCapture en
 * un PR futuro, la captura seguirá siendo nativa y completa (9:16 u otra), sin
 * recortarse a lo que se muestra en la cover.
 *
 * El rectángulo resultante ([LetterboxFrame]) se expresa en píxeles con origen
 * en la esquina superior izquierda de la cover. `left`/`top` son los offsets ya
 * centrados; pueden ser negativos, lo que indica que el rectángulo desborda la
 * cover y se recorta contra sus bordes (el caso del cuadrado que llena).
 */
object PreviewFraming {
    /** Extra de intent (string) para preseleccionar el modo por adb en QA. */
    const val EXTRA_PREVIEW_FRAME = "preview_frame"

    // Proporción de la toma vertical de destino (9:16), el formato de IG Stories.
    private const val PORTRAIT_ASPECT_WIDTH = 9
    private const val PORTRAIT_ASPECT_HEIGHT = 16

    /** Modo de encuadre del preview en la cover. Solo afecta la presentación. */
    enum class Mode {
        /**
         * 1:1: cuadrado centrado que LLENA la cover casi cuadrada (recorte
         * central tipo fill). Da un encuadre cercano de la cara.
         */
        SQUARE,

        /**
         * 9:16: la toma completa que correspondería a una captura 9:16, encajada
         * dentro de la cover sin deformar. Como 9:16 es más angosto que la cover
         * casi cuadrada, quedan barras laterales (pillarbox) y se ve toda la
         * composición.
         */
        PORTRAIT_9_16,
        ;

        companion object {
            /** Modo por defecto: encuadre cercano 1:1. */
            val DEFAULT = SQUARE

            /** Valor del extra que selecciona el modo cuadrado 1:1. */
            const val EXTRA_VALUE_SQUARE = "1x1"

            /** Valor del extra que selecciona el modo vertical 9:16. */
            const val EXTRA_VALUE_PORTRAIT = "9x16"

            /**
             * Traduce el valor crudo del extra de intent. Cualquier valor
             * ausente o desconocido cae en [DEFAULT], para no romper el arranque
             * normal del visor.
             */
            fun fromExtra(raw: String?): Mode =
                when (raw) {
                    EXTRA_VALUE_SQUARE -> SQUARE
                    EXTRA_VALUE_PORTRAIT -> PORTRAIT_9_16
                    else -> DEFAULT
                }
        }

        /** El otro modo, para el toggle cuadrado <-> vertical. */
        fun toggled(): Mode =
            when (this) {
                SQUARE -> PORTRAIT_9_16
                PORTRAIT_9_16 -> SQUARE
            }
    }

    /**
     * Rectángulo de presentación del preview dentro de la cover para el [mode]
     * dado. El tamaño de la cover se pasa en píxeles.
     */
    fun presentationFrame(
        mode: Mode,
        coverWidth: Int,
        coverHeight: Int,
    ): LetterboxFrame =
        when (mode) {
            Mode.SQUARE -> squareFill(coverWidth, coverHeight)
            // El pillarbox 9:16 es exactamente el cálculo de MirrorGeometry:
            // el mayor rectángulo centrado con proporción 9:16 que CABE en la
            // cover. Se reutiliza en vez de duplicar la geometría.
            Mode.PORTRAIT_9_16 ->
                MirrorGeometry.letterbox(
                    PORTRAIT_ASPECT_WIDTH,
                    PORTRAIT_ASPECT_HEIGHT,
                    coverWidth,
                    coverHeight,
                )
        }

    /**
     * Cuadrado 1:1 centrado que LLENA la cover: el lado es la MAYOR de las dos
     * dimensiones, de modo que cubre toda la pantalla y el sobrante se recorta
     * en el eje más corto. Es la operación opuesta al letterbox (que encaja
     * hacia adentro): acá se llena hacia afuera. Los offsets pueden quedar
     * negativos, señalando ese recorte contra los bordes de la cover.
     */
    fun squareFill(
        coverWidth: Int,
        coverHeight: Int,
    ): LetterboxFrame {
        require(coverWidth > 0 && coverHeight > 0) {
            "Tamaño de cover inválido: ${coverWidth}x$coverHeight"
        }
        val side = maxOf(coverWidth, coverHeight)
        return LetterboxFrame(
            left = (coverWidth - side) / 2,
            top = (coverHeight - side) / 2,
            width = side,
            height = side,
        )
    }
}
