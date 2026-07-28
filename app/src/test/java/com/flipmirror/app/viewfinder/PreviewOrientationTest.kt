package com.flipmirror.app.viewfinder

import com.flipmirror.app.viewfinder.LensSelection.Lens
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOrientationTest {
    // Semántica: la corrección es sensible a la pantalla. La wide sale derecha (0)
    // en cualquier pantalla. La ultrawide del Flip 5 tiene un quirk (entrega el
    // buffer girado 180 declarando el mismo SENSOR_ORIENTATION que la wide) que
    // solo se manifiesta en la cover: ahí lleva 180, en la principal 0.
    //
    // Tabla objetivo confirmada en dispositivo:
    //   (cover, WIDE) -> 0   (cover, ULTRAWIDE) -> 180
    //   (main,  WIDE) -> 0   (main,  ULTRAWIDE) -> 0

    @Test
    fun laWideSaleDerechaEnLaCover() {
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.WIDE,
                isCoverScreen = true,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laWideSaleDerechaEnLaPantallaPrincipal() {
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.WIDE,
                isCoverScreen = false,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConQuirkSeCorrige180EnLaCover() {
        // Caso real del Flip 5: ambas traseras declaran SENSOR_ORIENTATION=90 y la
        // ultrawide entrega el buffer girado 180. En la cover se rota 180.
        assertEquals(
            180,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.ULTRAWIDE,
                isCoverScreen = true,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConQuirkNoSeCorrigeEnLaPantallaPrincipal() {
        // Mismo quirk declarado, pero en la principal la ultrawide ya sale
        // derecha: no se aplica corrección (0).
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.ULTRAWIDE,
                isCoverScreen = false,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConOrientacionDistintaNoTieneQuirkNiEnLaCover() {
        // Si el dispositivo declara orientaciones distintas, CameraX ya trata la
        // ultrawide por separado y la deja derecha: sin quirk (0), ni en la cover.
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.ULTRAWIDE,
                isCoverScreen = true,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 270,
            ),
        )
    }

    @Test
    fun sinOrientacionesConocidasNoHayQuirkNiEnLaCover() {
        // Conservador ante orientaciones desconocidas: sin quirk (0), aun en la
        // cover, para no invertir un preview que podría venir bien.
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.ULTRAWIDE,
                isCoverScreen = true,
                wideSensorOrientation = null,
                ultrawideSensorOrientation = 90,
            ),
        )
        assertEquals(
            0,
            PreviewOrientation.correctionDegrees(
                activeLens = Lens.ULTRAWIDE,
                isCoverScreen = true,
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
                PreviewOrientation.correctionDegrees(Lens.WIDE, true, 90, 90),
                PreviewOrientation.correctionDegrees(Lens.WIDE, false, 90, 90),
                PreviewOrientation.correctionDegrees(Lens.ULTRAWIDE, true, 90, 90),
                PreviewOrientation.correctionDegrees(Lens.ULTRAWIDE, false, 90, 90),
                PreviewOrientation.correctionDegrees(Lens.ULTRAWIDE, true, 90, 270),
            )
        valores.forEach { assertEquals(0, it % 90) }
    }
}
