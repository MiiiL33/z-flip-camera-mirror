package com.flipmirror.app.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MirrorGeometryTest {
    @Test
    fun pantallaPrincipalDelFlip5EnLaCoverQuedaCentradaSinDeformar() {
        // Caso real del spike: fuente 1080x2640 (principal) sobre 748x720 (cover).
        val frame = MirrorGeometry.letterbox(1080, 2640, 748, 720)

        // Limita la altura: 1080 * 720 / 2640 = 294.54, redondeado a 295.
        assertEquals(295, frame.width)
        assertEquals(720, frame.height)
        assertEquals(226, frame.left)
        assertEquals(0, frame.top)
    }

    @Test
    fun fuenteApaisadaSobreLaCoverDejaBandasArribaYAbajo() {
        // Principal rotada a landscape: 2640x1080 sobre 748x720.
        val frame = MirrorGeometry.letterbox(2640, 1080, 748, 720)

        // Limita el ancho: 1080 * 748 / 2640 = 306 exacto.
        assertEquals(748, frame.width)
        assertEquals(306, frame.height)
        assertEquals(0, frame.left)
        assertEquals(207, frame.top)
    }

    @Test
    fun mismaProporcionLlenaElDestinoCompleto() {
        val frame = MirrorGeometry.letterbox(1080, 2640, 540, 1320)

        assertEquals(MirrorGeometry.LetterboxFrame(0, 0, 540, 1320), frame)
    }

    @Test
    fun fuenteMasChicaQueElDestinoEscalaHaciaArriba() {
        val frame = MirrorGeometry.letterbox(100, 100, 748, 720)

        // El cuadrado crece hasta la dimensión limitante (la altura).
        assertEquals(MirrorGeometry.LetterboxFrame(14, 0, 720, 720), frame)
    }

    @Test
    fun fuenteExtremadamenteAlargadaNuncaColapsaACero() {
        val frame = MirrorGeometry.letterbox(1, 10000, 748, 720)

        assertEquals(1, frame.width)
        assertEquals(720, frame.height)
    }

    @Test
    fun tamanosNoPositivosLanzanExcepcion() {
        assertThrows(IllegalArgumentException::class.java) {
            MirrorGeometry.letterbox(0, 2640, 748, 720)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MirrorGeometry.letterbox(1080, 2640, 748, 0)
        }
    }
}
