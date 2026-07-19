package com.flipmirror.app.viewfinder

/**
 * Corrección de ORIENTACION del preview en la cover. Es lógica pura (sin tipos
 * de Android) para poder cubrirla con unit tests en JVM, al estilo de
 * [PreviewFraming], [LensSelection] y [FoldPosture].
 *
 * Motivación (confirmada por diagnóstico en el Flip 5, SM-F731B, plegado sobre
 * la cover): la lente WIDE (camera id=0) y la ULTRAWIDE (camera id=2) declaran
 * EXACTAMENTE lo mismo a Camera2: el mismo SENSOR_ORIENTATION (90) y el mismo
 * LENS_POSE_ROTATION. Aún así, el módulo ultrawide entrega el buffer girado 180
 * grados: en pantalla la wide se ve derecha y la ultrawide de cabeza. Como
 * CameraX calcula su transformación a partir de esos datos (idénticos entre
 * ambas lentes), NO puede compensar la diferencia por sí solo, ni siquiera
 * fijando el targetRotation del display: para el framework las dos cámaras están
 * montadas igual. Es una peculiaridad de hardware que ninguna característica de
 * Camera2 expone.
 *
 * Por eso la corrección del quirk no puede derivarse de una "diferencia de
 * SENSOR_ORIENTATION entre lentes" (esa diferencia es 0 en el Flip 5): se aplica
 * como una rotación extra a la vista del preview. La regla es data-driven, no un
 * simple "si es ultrawide, rotá 180":
 *
 *  - La WIDE es la referencia del quirk y sale derecha: no lleva corrección de
 *    quirk (0 grados).
 *  - La ULTRAWIDE solo se corrige por quirk cuando su SENSOR_ORIENTATION es
 *    INDISTINGUIBLE del de la wide (mismo valor). Ese es justamente el caso en
 *    que CameraX no tiene con qué diferenciarlas y el buffer llega invertido: el
 *    residuo entre dos sensores traseros montados en oposición pero declarados
 *    iguales es exactamente 180.
 *  - Si un dispositivo declarara orientaciones DISTINTAS para wide y ultrawide,
 *    CameraX ya las trata por separado y las deja derechas: en ese caso no se
 *    aplica corrección de quirk (0), para no invertir una ultrawide que ya venía
 *    bien.
 *  - Ante orientaciones desconocidas se es conservador y no se corrige por quirk
 *    (0).
 *
 * Sobre esa corrección de quirk (que deja ambas lentes derechas y alineadas
 * entre sí) se suma una media vuelta base de la cover: con el teléfono plegado
 * sobre la cover la cámara debe apuntar desde arriba para la selfie, así que las
 * dos lentes se muestran a 180 grados del upright del framework. Ambas siguen
 * consistentes entre sí; solo cambia el valor final de la corrección, no el
 * mecanismo con que se aplica. Decisión de producto del 2026-07-19.
 *
 * Todo esto es independiente del toggle de encuadre (que solo cambia el tamaño
 * de la vista) y del rebind por plegado.
 */
object PreviewOrientation {
    // Residuo de rotación entre dos sensores traseros montados en oposición pero
    // que declaran el mismo SENSOR_ORIENTATION (el caso de la ultrawide del
    // Flip 5). Media vuelta.
    private const val OPPOSED_SENSOR_CORRECTION = 180

    // Media vuelta base de uso de la cover: con el teléfono plegado la cámara
    // debe apuntar desde arriba para la selfie, así que ambas lentes se muestran
    // a 180 grados del upright del framework. Se suma (módulo 360) a la
    // corrección de quirk por lente. Decisión de producto del 2026-07-19.
    private const val COVER_BASE_ROTATION_DEGREES = 180

    /**
     * Grados de rotación extra (0/90/180/270, sentido horario) a aplicar a la
     * PreviewView para la lente [activeLens] en la cover.
     *
     * Es la suma, módulo 360, de la corrección de quirk por lente (la que deja
     * ambas lentes alineadas entre sí) y la media vuelta base de la cover
     * ([COVER_BASE_ROTATION_DEGREES]).
     *
     * @param activeLens lente actualmente bindeada.
     * @param wideSensorOrientation SENSOR_ORIENTATION de la wide, o null si no se
     *   pudo leer.
     * @param ultrawideSensorOrientation SENSOR_ORIENTATION de la ultrawide, o
     *   null si no se pudo leer.
     */
    fun coverCorrectionDegrees(
        activeLens: LensSelection.Lens,
        wideSensorOrientation: Int?,
        ultrawideSensorOrientation: Int?,
    ): Int {
        val quirkCorrection =
            lensQuirkCorrectionDegrees(
                activeLens,
                wideSensorOrientation,
                ultrawideSensorOrientation,
            )
        return (quirkCorrection + COVER_BASE_ROTATION_DEGREES) % 360
    }

    /**
     * Corrección del quirk de hardware por lente, sin la media vuelta base de la
     * cover. Deja ambas lentes derechas y alineadas entre sí (la wide como
     * referencia); ver el detalle en la doc de la clase.
     */
    private fun lensQuirkCorrectionDegrees(
        activeLens: LensSelection.Lens,
        wideSensorOrientation: Int?,
        ultrawideSensorOrientation: Int?,
    ): Int {
        // La wide es la referencia derecha: no lleva corrección de quirk.
        if (activeLens != LensSelection.Lens.ULTRAWIDE) return 0

        // Solo corregimos la ultrawide cuando su orientación es indistinguible de
        // la de la wide: ahí es donde CameraX no puede compensar el montaje
        // opuesto del sensor y el preview llega invertido.
        val wide = wideSensorOrientation ?: return 0
        val ultrawide = ultrawideSensorOrientation ?: return 0
        return if (wide == ultrawide) OPPOSED_SENSOR_CORRECTION else 0
    }
}
