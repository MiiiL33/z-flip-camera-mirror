package com.flipmirror.app.viewfinder

import com.flipmirror.app.spikes.MirrorGeometry.LetterboxFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFramingTest {
    @Test
    fun cuadradoLlenaLaCoverDelFlip5RecortandoElEjeMasLargo() {
        // Cover del Flip 5: 720x748 (más alta que ancha). El cuadrado toma el
        // lado mayor (748) para llenar, así que desborda 28px de ancho: se
        // recorta 14px por lado (left negativo).
        val frame = PreviewFraming.presentationFrame(PreviewFraming.Mode.SQUARE, 720, 748)

        assertEquals(LetterboxFrame(-14, 0, 748, 748), frame)
    }

    @Test
    fun cuadradoLlenaLaCoverDelFlip7RecortandoLaAltura() {
        // Cover del Flip 7: 1048x948 (más ancha que alta). El lado mayor es 1048;
        // desborda 100px de alto, recortando 50px arriba y abajo (top negativo).
        val frame = PreviewFraming.presentationFrame(PreviewFraming.Mode.SQUARE, 1048, 948)

        assertEquals(LetterboxFrame(0, -50, 1048, 1048), frame)
    }

    @Test
    fun cuadradoSobreCoverExactamenteCuadradaLlenaSinRecorte() {
        val frame = PreviewFraming.squareFill(800, 800)

        assertEquals(LetterboxFrame(0, 0, 800, 800), frame)
    }

    @Test
    fun verticalDejaBarrasLateralesEnLaCoverDelFlip5() {
        // 9:16 es más angosto que la cover casi cuadrada: pillarbox. Reutiliza
        // MirrorGeometry.letterbox(9, 16, 720, 748): escala limitada por la
        // altura, ancho útil 9 * 748 / 16 = 420.75 -> 421, centrado (left 149).
        val frame = PreviewFraming.presentationFrame(PreviewFraming.Mode.PORTRAIT_9_16, 720, 748)

        assertEquals(LetterboxFrame(149, 0, 421, 748), frame)
        // Barras laterales reales (pillarbox), sin barras arriba/abajo.
        assertTrue("debe haber barra lateral", frame.left > 0)
        assertEquals("la altura llena la cover", 748, frame.height)
        assertEquals("sin barra superior", 0, frame.top)
    }

    @Test
    fun verticalDejaBarrasLateralesEnLaCoverDelFlip7() {
        // letterbox(9, 16, 1048, 948): ancho útil 9 * 948 / 16 = 533.25 -> 533.
        val frame = PreviewFraming.presentationFrame(PreviewFraming.Mode.PORTRAIT_9_16, 1048, 948)

        assertEquals(LetterboxFrame(257, 0, 533, 948), frame)
        assertTrue("debe haber barra lateral", frame.left > 0)
    }

    @Test
    fun elExtraDeIntentSeleccionaElModoYCaeEnElDefaultSiEsDesconocido() {
        assertEquals(PreviewFraming.Mode.SQUARE, PreviewFraming.Mode.fromExtra("1x1"))
        assertEquals(PreviewFraming.Mode.PORTRAIT_9_16, PreviewFraming.Mode.fromExtra("9x16"))
        // Ausente o basura: el visor arranca en el modo por defecto (cuadrado).
        assertEquals(PreviewFraming.Mode.DEFAULT, PreviewFraming.Mode.fromExtra(null))
        assertEquals(PreviewFraming.Mode.DEFAULT, PreviewFraming.Mode.fromExtra("cualquiera"))
        assertEquals(PreviewFraming.Mode.SQUARE, PreviewFraming.Mode.DEFAULT)
    }

    @Test
    fun elToggleAlternaEntreLosDosModos() {
        assertEquals(PreviewFraming.Mode.PORTRAIT_9_16, PreviewFraming.Mode.SQUARE.toggled())
        assertEquals(PreviewFraming.Mode.SQUARE, PreviewFraming.Mode.PORTRAIT_9_16.toggled())
    }

    @Test
    fun tamanosNoPositivosLanzanExcepcion() {
        assertThrows(IllegalArgumentException::class.java) {
            PreviewFraming.squareFill(0, 748)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewFraming.presentationFrame(PreviewFraming.Mode.SQUARE, 720, 0)
        }
    }
}
