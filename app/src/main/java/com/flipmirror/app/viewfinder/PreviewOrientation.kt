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
 * Por eso la corrección no puede derivarse de una "diferencia de
 * SENSOR_ORIENTATION entre lentes" (esa diferencia es 0 en el Flip 5): se aplica
 * como una rotación extra a la vista del preview. La regla es data-driven, no un
 * simple "si es ultrawide, rotá 180":
 *
 *  - La WIDE es la referencia y se ve derecha: nunca se corrige (0 grados).
 *  - La ULTRAWIDE solo se corrige cuando su SENSOR_ORIENTATION es INDISTINGUIBLE
 *    del de la wide (mismo valor). Ese es justamente el caso en que CameraX no
 *    tiene con qué diferenciarlas y el buffer llega invertido: el residuo entre
 *    dos sensores traseros montados en oposición pero declarados iguales es
 *    exactamente 180.
 *  - Si un dispositivo declarara orientaciones DISTINTAS para wide y ultrawide,
 *    CameraX ya las trata por separado y las deja derechas: en ese caso no se
 *    aplica corrección (0), para no invertir una ultrawide que ya venía bien.
 *  - Ante orientaciones desconocidas se es conservador y no se corrige (0).
 *
 * El objetivo es que ambas lentes se vean derechas en la cover, de forma
 * independiente del toggle de encuadre (que solo cambia el tamaño de la vista) y
 * del rebind por plegado.
 */
object PreviewOrientation {
    // Residuo de rotación entre dos sensores traseros montados en oposición pero
    // que declaran el mismo SENSOR_ORIENTATION (el caso de la ultrawide del
    // Flip 5). Media vuelta.
    private const val OPPOSED_SENSOR_CORRECTION = 180

    /**
     * Grados de rotación extra (0/90/180/270, sentido horario) a aplicar a la
     * PreviewView para que la lente [activeLens] se vea derecha en la cover.
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
        // La wide es la referencia derecha: nunca se corrige.
        if (activeLens != LensSelection.Lens.ULTRAWIDE) return 0

        // Solo corregimos la ultrawide cuando su orientación es indistinguible de
        // la de la wide: ahí es donde CameraX no puede compensar el montaje
        // opuesto del sensor y el preview llega invertido.
        val wide = wideSensorOrientation ?: return 0
        val ultrawide = ultrawideSensorOrientation ?: return 0
        return if (wide == ultrawide) OPPOSED_SENSOR_CORRECTION else 0
    }
}
