package com.flipmirror.app.viewfinder

import com.flipmirror.app.viewfinder.LensSelection.Lens
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOrientationTest {
    @Test
    fun laWideNuncaSeCorrigeEsLaReferenciaDerecha() {
        // Aunque las orientaciones sean iguales, la wide es la lente de
        // referencia y siempre se ve derecha: cero corrección.
        assertEquals(
            0,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.WIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConMismaOrientacionQueLaWideSeCorrige180() {
        // Caso real del Flip 5: ambas traseras declaran SENSOR_ORIENTATION=90,
        // pero la ultrawide entrega el buffer girado 180. CameraX no puede
        // distinguirlas, así que se rota la vista media vuelta.
        assertEquals(
            180,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConOrientacionDistintaNoSeCorrigeCameraxYaLaTrata() {
        // Si el dispositivo declara orientaciones distintas, CameraX las trata
        // por separado y la ultrawide ya sale derecha: no se corrige, para no
        // invertirla.
        assertEquals(
            0,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 270,
            ),
        )
    }

    @Test
    fun sinOrientacionesConocidasEsConservadorYNoCorrige() {
        assertEquals(
            0,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = null,
                ultrawideSensorOrientation = 90,
            ),
        )
        assertEquals(
            0,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = null,
            ),
        )
    }

    @Test
    fun laCorreccionSiempreEsUnMultiploDe90Valido() {
        // Contrato con la PreviewView: solo 0/90/180/270. Hoy solo devolvemos
        // 0 o 180, pero el rango se fija para que futuras variantes lo respeten.
        val valores =
            listOf(
                PreviewOrientation.coverCorrectionDegrees(Lens.WIDE, 90, 90),
                PreviewOrientation.coverCorrectionDegrees(Lens.ULTRAWIDE, 90, 90),
                PreviewOrientation.coverCorrectionDegrees(Lens.ULTRAWIDE, 90, 270),
            )
        valores.forEach { assertEquals(0, it % 90) }
    }
}
