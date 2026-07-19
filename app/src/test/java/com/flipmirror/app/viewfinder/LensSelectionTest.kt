package com.flipmirror.app.viewfinder

import com.flipmirror.app.viewfinder.LensSelection.CameraDescriptor
import com.flipmirror.app.viewfinder.LensSelection.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensSelectionTest {
    // Focales de referencia del Flip 5: la principal ronda 5.4mm y la ultrawide
    // 1.8mm (menor focal = campo más amplio). Los ids son ilustrativos.
    private val wideBack = CameraDescriptor(id = "0", minFocalLengthMm = 5.4f, isBackFacing = true)
    private val ultrawideBack = CameraDescriptor(id = "3", minFocalLengthMm = 1.8f, isBackFacing = true)
    private val front = CameraDescriptor(id = "1", minFocalLengthMm = 3.0f, isBackFacing = false)

    @Test
    fun conDosTraserasDistingueWidePorMayorFocalYUltrawidePorMenor() {
        val options = LensSelection.analyze(listOf(ultrawideBack, wideBack, front))

        assertEquals("0", options.wideCameraId)
        assertEquals("3", options.ultrawideCameraId)
        assertTrue(options.hasUltrawide)
        assertTrue(options.canToggle)
        assertEquals("0", options.cameraIdFor(Lens.WIDE))
        assertEquals("3", options.cameraIdFor(Lens.ULTRAWIDE))
    }

    @Test
    fun laDefaultBackFijaLaWideAunqueOtraTraseraTengaMayorFocal() {
        // Escenario con teleobjetivo: la de mayor focal NO es la principal. La
        // default back del sistema manda para elegir la wide; la ultrawide sigue
        // siendo la de menor focal.
        val tele = CameraDescriptor(id = "5", minFocalLengthMm = 12.0f, isBackFacing = true)

        val options =
            LensSelection.analyze(
                listOf(ultrawideBack, wideBack, tele),
                defaultBackId = "0",
            )

        assertEquals("0", options.wideCameraId)
        assertEquals("3", options.ultrawideCameraId)
    }

    @Test
    fun conUnaSolaTraseraDejaSoloWideSinUltrawide() {
        val options = LensSelection.analyze(listOf(wideBack, front))

        assertEquals("0", options.wideCameraId)
        assertNull(options.ultrawideCameraId)
        assertFalse(options.hasUltrawide)
        assertFalse(options.canToggle)
        // Pedir ultrawide en un equipo que no la tiene degrada a wide.
        assertEquals(Lens.WIDE, options.effective(Lens.ULTRAWIDE))
        assertNull(options.cameraIdFor(Lens.ULTRAWIDE))
    }

    @Test
    fun conFocalesTraserasIgualesNoInventaUltrawide() {
        // Dos traseras con la misma focal no son distinguibles como wide/ultrawide:
        // se degrada a solo wide para no ofrecer un toggle sin efecto.
        val otra = CameraDescriptor(id = "9", minFocalLengthMm = 5.4f, isBackFacing = true)

        val options = LensSelection.analyze(listOf(wideBack, otra))

        assertFalse(options.hasUltrawide)
        assertFalse(options.canToggle)
        assertNull(options.ultrawideCameraId)
    }

    @Test
    fun listaVaciaNoTieneNiWideNiUltrawide() {
        val options = LensSelection.analyze(emptyList())

        assertNull(options.wideCameraId)
        assertNull(options.ultrawideCameraId)
        assertFalse(options.canToggle)
        // Sin cámaras, la efectiva de cualquier pedido nunca crashea.
        assertEquals(Lens.WIDE, options.effective(Lens.ULTRAWIDE))
        assertEquals(Lens.WIDE, options.effective(Lens.WIDE))
    }

    @Test
    fun soloCamarasDelanterasNoOfrecenTrasera() {
        val options = LensSelection.analyze(listOf(front))

        assertNull(options.wideCameraId)
        assertNull(options.ultrawideCameraId)
        assertFalse(options.canToggle)
    }

    @Test
    fun focalDesconocidaNoSeEligeComoUltrawide() {
        // Una trasera sin focal reportada (NaN) no puede clasificarse como
        // ultrawide; queda la wide conocida y sin ultrawide.
        val sinFocal = CameraDescriptor(id = "7", minFocalLengthMm = Float.NaN, isBackFacing = true)

        val options = LensSelection.analyze(listOf(wideBack, sinFocal), defaultBackId = "0")

        assertEquals("0", options.wideCameraId)
        assertNull(options.ultrawideCameraId)
    }

    @Test
    fun elExtraDeIntentSeleccionaLaLenteYCaeEnDefaultSiEsDesconocido() {
        assertEquals(Lens.WIDE, Lens.fromExtra("wide"))
        assertEquals(Lens.ULTRAWIDE, Lens.fromExtra("ultrawide"))
        assertEquals(Lens.DEFAULT, Lens.fromExtra(null))
        assertEquals(Lens.DEFAULT, Lens.fromExtra("cualquiera"))
        assertEquals(Lens.WIDE, Lens.DEFAULT)
    }

    @Test
    fun elToggleAlternaEntreLasDosLentes() {
        assertEquals(Lens.ULTRAWIDE, Lens.WIDE.toggled())
        assertEquals(Lens.WIDE, Lens.ULTRAWIDE.toggled())
    }
}
