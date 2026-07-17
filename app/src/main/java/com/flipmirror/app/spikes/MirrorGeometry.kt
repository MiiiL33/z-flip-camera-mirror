package com.flipmirror.app.spikes

import kotlin.math.roundToInt

/**
 * Geometría pura del espejo: cálculo del rectángulo donde renderizar la
 * captura de la pantalla principal dentro del display destino sin deformar
 * la imagen (letterbox o pillarbox según proporciones).
 *
 * Es Kotlin puro, sin clases de Android, para poder cubrirlo con unit tests
 * en JVM (igual que [SpikeIntentCommand] y [CameraEventLog]).
 */
object MirrorGeometry {
    /**
     * Rectángulo destino dentro del display, en píxeles, con origen en la
     * esquina superior izquierda del display.
     */
    data class LetterboxFrame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * Calcula el rectángulo centrado más grande que cabe en el destino
     * manteniendo la proporción de la fuente. Ejemplo real: pantalla
     * principal del Flip 5 (1080x2640) sobre la cover (748x720) produce una
     * franja vertical centrada con bandas negras a los lados.
     */
    fun letterbox(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): LetterboxFrame {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Tamaño de fuente inválido: ${sourceWidth}x$sourceHeight"
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "Tamaño de destino inválido: ${targetWidth}x$targetHeight"
        }
        val scale =
            minOf(
                targetWidth.toDouble() / sourceWidth,
                targetHeight.toDouble() / sourceHeight,
            )
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, targetWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, targetHeight)
        return LetterboxFrame(
            left = (targetWidth - width) / 2,
            top = (targetHeight - height) / 2,
            width = width,
            height = height,
        )
    }
}
