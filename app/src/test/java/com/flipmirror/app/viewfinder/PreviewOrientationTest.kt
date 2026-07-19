package com.flipmirror.app.viewfinder

import com.flipmirror.app.viewfinder.LensSelection.Lens
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOrientationTest {
    // Recordatorio de la semántica: corrección_final = (quirk + 180 base) % 360.
    // La media vuelta base de la cover deja ambas lentes a 180 del upright del
    // framework, y siguen alineadas entre sí.

    @Test
    fun laWideLlevaSoloLaMediaVueltaBaseDeLaCover() {
        // La wide no tiene quirk (0), así que queda con la media vuelta base:
        // (0 + 180) % 360 = 180. Apunta desde arriba para la selfie en la cover.
        assertEquals(
            180,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.WIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConMismaOrientacionQuedaAlineadaConLaWide() {
        // Caso real del Flip 5: ambas traseras declaran SENSOR_ORIENTATION=90 y
        // la ultrawide entrega el buffer girado 180 (quirk=180). Con la base:
        // (180 + 180) % 360 = 0, que la deja a 180 del upright igual que la wide.
        assertEquals(
            0,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 90,
            ),
        )
    }

    @Test
    fun laUltrawideConOrientacionDistintaNoTieneQuirkSoloLaBase() {
        // Si el dispositivo declara orientaciones distintas, CameraX ya trata la
        // ultrawide por separado y no hay quirk (0). Queda con la media vuelta
        // base: (0 + 180) % 360 = 180.
        assertEquals(
            180,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = 90,
                ultrawideSensorOrientation = 270,
            ),
        )
    }

    @Test
    fun sinOrientacionesConocidasNoHayQuirkSoloLaBase() {
        // Conservador ante orientaciones desconocidas: sin quirk (0), solo la
        // media vuelta base: (0 + 180) % 360 = 180.
        assertEquals(
            180,
            PreviewOrientation.coverCorrectionDegrees(
                activeLens = Lens.ULTRAWIDE,
                wideSensorOrientation = null,
                ultrawideSensorOrientation = 90,
            ),
        )
        assertEquals(
            180,
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
