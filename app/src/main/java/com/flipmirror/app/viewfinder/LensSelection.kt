package com.flipmirror.app.viewfinder

/**
 * Selección de lente trasera del visor: decide, a partir de un set de cámaras
 * traseras descriptas de forma neutral (id + focal + facing), cuál es la WIDE
 * (principal) y cuál la ULTRAWIDE, y expone el orden del toggle.
 *
 * Es lógica pura (sin tipos de Android) para poder cubrirla con unit tests en
 * JVM, al estilo de [PreviewFraming], [FoldPosture] y
 * [com.flipmirror.app.spikes.MirrorGeometry]. La [ViewfinderActivity] traduce
 * las CameraInfo de CameraX (leyendo la focal por interop Camera2) a los
 * [CameraDescriptor] que consume esta clase; acá no entra nada de Camera2.
 *
 * Criterio: entre las cámaras traseras, la ULTRAWIDE es la de MENOR focal
 * (campo de visión más amplio) y la WIDE (principal) es la default back del
 * sistema o, si no se conoce, la de mayor focal. Si hay una sola trasera, las
 * focales son iguales o desconocidas, degrada con gracia dejando solo la wide
 * (sin ultrawide), para que la Activity pueda deshabilitar el toggle sin
 * crashear.
 */
object LensSelection {
    /** Extra de intent (string) para preseleccionar la lente por adb en QA. */
    const val EXTRA_LENS = "lens"

    /** Lente trasera activa en el preview. Solo hay dos en el alcance del visor. */
    enum class Lens {
        /** Cámara trasera principal (angular normal). */
        WIDE,

        /** Cámara trasera ultra gran angular (campo de visión más amplio). */
        ULTRAWIDE,
        ;

        /** La otra lente, para el toggle wide <-> ultrawide. */
        fun toggled(): Lens =
            when (this) {
                WIDE -> ULTRAWIDE
                ULTRAWIDE -> WIDE
            }

        companion object {
            /** Lente por defecto: la principal (wide). */
            val DEFAULT = WIDE

            /** Valor del extra que selecciona la lente principal (wide). */
            const val EXTRA_VALUE_WIDE = "wide"

            /** Valor del extra que selecciona la ultra gran angular. */
            const val EXTRA_VALUE_ULTRAWIDE = "ultrawide"

            /**
             * Traduce el valor crudo del extra de intent. Cualquier valor
             * ausente o desconocido cae en [DEFAULT], para no romper el arranque
             * normal del visor.
             */
            fun fromExtra(raw: String?): Lens =
                when (raw) {
                    EXTRA_VALUE_WIDE -> WIDE
                    EXTRA_VALUE_ULTRAWIDE -> ULTRAWIDE
                    else -> DEFAULT
                }
        }
    }

    /**
     * Descriptor neutral de una cámara física, sin tipos de Android. La Activity
     * lo construye a partir de una CameraInfo: [id] es el camera id de Camera2,
     * [minFocalLengthMm] la menor focal reportada por
     * LENS_INFO_AVAILABLE_FOCAL_LENGTHS (o NaN si no se pudo leer) y
     * [isBackFacing] indica si mira hacia atrás.
     */
    data class CameraDescriptor(
        val id: String,
        val minFocalLengthMm: Float,
        val isBackFacing: Boolean,
    )

    /**
     * Resultado del análisis: a qué camera id mapea cada lente. Un id nulo indica
     * que esa lente no está disponible en el dispositivo.
     */
    data class LensOptions(
        val wideCameraId: String?,
        val ultrawideCameraId: String?,
    ) {
        /** true si el dispositivo expone una ultra gran angular usable. */
        val hasUltrawide: Boolean
            get() = ultrawideCameraId != null

        /** true si tiene sentido ofrecer el toggle (hay wide y ultrawide). */
        val canToggle: Boolean
            get() = wideCameraId != null && ultrawideCameraId != null

        /** camera id para la lente pedida, o null si no está disponible. */
        fun cameraIdFor(lens: Lens): String? =
            when (lens) {
                Lens.WIDE -> wideCameraId
                Lens.ULTRAWIDE -> ultrawideCameraId
            }

        /**
         * Lente realmente disponible para la pedida: si se pide ULTRAWIDE y no
         * hay, cae en WIDE. Así una preselección de ultrawide en un dispositivo
         * que no la tiene no deja al visor sin cámara.
         */
        fun effective(requested: Lens): Lens = if (requested == Lens.ULTRAWIDE && !hasUltrawide) Lens.WIDE else requested
    }

    /**
     * Clasifica el set de cámaras en wide y ultrawide.
     *
     * @param cameras descriptores de todas las cámaras enumeradas (se filtran
     *   las traseras acá dentro).
     * @param defaultBackId camera id de la default back del sistema, si se
     *   conoce; se usa para fijar la WIDE (principal). Si es null o no está entre
     *   las traseras, la WIDE se resuelve como la de mayor focal.
     */
    fun analyze(
        cameras: List<CameraDescriptor>,
        defaultBackId: String? = null,
    ): LensOptions {
        val back = cameras.filter { it.isBackFacing }
        if (back.isEmpty()) return LensOptions(null, null)

        // WIDE (principal): la default back del sistema si está entre las
        // traseras; si no se conoce, la de mayor focal (menor campo de visión),
        // y como último recurso la primera enumerada.
        val wide =
            back.firstOrNull { it.id == defaultBackId }
                ?: back.filter { !it.minFocalLengthMm.isNaN() }.maxByOrNull { it.minFocalLengthMm }
                ?: back.first()

        // ULTRAWIDE: la trasera con MENOR focal, estrictamente menor que la de la
        // wide y distinta de ella. Con una sola trasera, focales iguales o
        // desconocidas no hay candidata y se degrada a solo wide.
        val wideFocal = wide.minFocalLengthMm
        val ultrawide =
            back
                .filter {
                    it.id != wide.id &&
                        !it.minFocalLengthMm.isNaN() &&
                        !wideFocal.isNaN() &&
                        it.minFocalLengthMm < wideFocal
                }
                .minByOrNull { it.minFocalLengthMm }

        return LensOptions(wideCameraId = wide.id, ultrawideCameraId = ultrawide?.id)
    }
}
