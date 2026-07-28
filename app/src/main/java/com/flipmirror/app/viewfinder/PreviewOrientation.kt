package com.flipmirror.app.viewfinder

/**
 * Corrección de ORIENTACION del preview. Es lógica pura (sin tipos de Android)
 * para poder cubrirla con unit tests en JVM, al estilo de [PreviewFraming],
 * [LensSelection] y [FoldPosture].
 *
 * Motivación (confirmada por QA en el Flip 5, SM-F731B): la orientación correcta
 * del preview depende de DOS cosas que se combinan.
 *
 * 1. La PANTALLA donde se renderiza. La cover (display externo) y la pantalla
 *    principal tienen el "arriba" invertido entre sí. El framework deja la wide
 *    derecha en ambas, pero el quirk de la ultrawide (ver punto 2) solo aparece
 *    en la cover.
 *
 * 2. El quirk de hardware de la ULTRAWIDE. En el Flip 5 la wide (camera id=0) y
 *    la ultrawide (camera id=2) declaran EXACTAMENTE lo mismo a Camera2: el mismo
 *    SENSOR_ORIENTATION (90) y el mismo LENS_POSE_ROTATION. Aún así, el módulo
 *    ultrawide entrega el buffer girado 180 grados. Como CameraX calcula su
 *    transformación a partir de esos datos (idénticos entre ambas lentes), NO
 *    puede compensar la diferencia por sí solo, ni siquiera fijando el
 *    targetRotation del display: para el framework las dos cámaras están montadas
 *    igual. Es una peculiaridad de hardware que ninguna característica de Camera2
 *    expone.
 *
 * Tabla empírica que hay que lograr (derivada de la prueba en dispositivo):
 *
 *  - (cover, WIDE)      -> 0
 *  - (cover, ULTRAWIDE) -> 180
 *  - (main,  WIDE)      -> 0
 *  - (main,  ULTRAWIDE) -> 0
 *
 * En palabras: la WIDE siempre sale derecha, corrección 0 en cualquier pantalla.
 * La ULTRAWIDE necesita la media vuelta del quirk SOLO cuando el preview se
 * renderiza en la COVER; en la pantalla principal ya sale derecha (0). No hay
 * ninguna rotación base fija de la cover: la corrección es 0 salvo el caso puntual
 * de la ultrawide con quirk en la cover.
 *
 * La regla del quirk es data-driven, no un simple "si es ultrawide, rotá 180":
 *
 *  - La WIDE es la referencia y sale derecha: no lleva corrección (0) en ninguna
 *    pantalla.
 *  - La ULTRAWIDE solo se corrige por quirk cuando (a) el preview está en la
 *    cover Y (b) su SENSOR_ORIENTATION es INDISTINGUIBLE del de la wide (mismo
 *    valor). Ese es justamente el caso en que CameraX no tiene con qué
 *    diferenciarlas y el buffer llega invertido: el residuo entre dos sensores
 *    traseros montados en oposición pero declarados iguales es exactamente 180.
 *  - En la pantalla principal la ultrawide ya sale derecha: no se aplica el quirk
 *    (0), para no invertir un preview que ya venía bien.
 *  - Si un dispositivo declarara orientaciones DISTINTAS para wide y ultrawide,
 *    CameraX ya las trata por separado y las deja derechas: en ese caso no se
 *    aplica corrección de quirk (0), ni siquiera en la cover.
 *  - Ante orientaciones desconocidas se es conservador y no se corrige por quirk
 *    (0).
 *
 * Todo esto es independiente del toggle de encuadre (que solo cambia el tamaño de
 * la vista) y del rebind por plegado; la corrección se recalcula en cada bind con
 * el estado ACTUAL de pantalla y lente.
 */
object PreviewOrientation {
    // Residuo de rotación entre dos sensores traseros montados en oposición pero
    // que declaran el mismo SENSOR_ORIENTATION (el caso de la ultrawide del
    // Flip 5). Media vuelta. Solo se aplica cuando el preview está en la cover.
    private const val OPPOSED_SENSOR_CORRECTION = 180

    /**
     * Grados de rotación extra (0/90/180/270, sentido horario) a aplicar a la
     * PreviewView para la lente [activeLens] según la pantalla donde se renderiza.
     *
     * @param activeLens lente actualmente bindeada.
     * @param isCoverScreen true si el preview se está renderizando en la cover
     *   (display externo del Flip); false si está en la pantalla principal. Es el
     *   estado que decide si el quirk de la ultrawide necesita corrección.
     * @param wideSensorOrientation SENSOR_ORIENTATION de la wide, o null si no se
     *   pudo leer.
     * @param ultrawideSensorOrientation SENSOR_ORIENTATION de la ultrawide, o
     *   null si no se pudo leer.
     */
    fun correctionDegrees(
        activeLens: LensSelection.Lens,
        isCoverScreen: Boolean,
        wideSensorOrientation: Int?,
        ultrawideSensorOrientation: Int?,
    ): Int {
        // La wide es la referencia derecha: no lleva corrección en ninguna
        // pantalla.
        if (activeLens != LensSelection.Lens.ULTRAWIDE) return 0

        // El quirk de la ultrawide solo se manifiesta en la cover. En la pantalla
        // principal ya sale derecha, así que no se toca.
        if (!isCoverScreen) return 0

        // Solo corregimos la ultrawide cuando su orientación es indistinguible de
        // la de la wide: ahí es donde CameraX no puede compensar el montaje
        // opuesto del sensor y el preview llega invertido. Ante orientaciones
        // desconocidas o distintas se es conservador y no se corrige (0).
        val wide = wideSensorOrientation ?: return 0
        val ultrawide = ultrawideSensorOrientation ?: return 0
        return if (wide == ultrawide) OPPOSED_SENSOR_CORRECTION else 0
    }
}
