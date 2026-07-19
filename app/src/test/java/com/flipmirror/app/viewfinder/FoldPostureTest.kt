package com.flipmirror.app.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldPostureTest {
    @Test
    fun sinFoldingFeatureSeInterpretaComoTelefonoCerrado() {
        // El escenario de producto: teléfono cerrado, visor en la cover screen.
        // androidx.window no reporta bisagra, y eso se mapea a FOLDED.
        assertEquals(FoldPosture.FOLDED, FoldPosture.fromHingeState(null))
    }

    @Test
    fun bisagraPlanaSeMapeaAFlat() {
        assertEquals(
            FoldPosture.FLAT,
            FoldPosture.fromHingeState(FoldPosture.HingeState.FLAT),
        )
    }

    @Test
    fun bisagraAMedioAbrirSeMapeaAHalfOpen() {
        assertEquals(
            FoldPosture.HALF_OPEN,
            FoldPosture.fromHingeState(FoldPosture.HingeState.HALF_OPENED),
        )
    }

    @Test
    fun soloLaPosturaCerradaSeTrataComoCoverScreen() {
        assertTrue(FoldPosture.FOLDED.isCoverScreenLikely)
        assertFalse(FoldPosture.HALF_OPEN.isCoverScreenLikely)
        assertFalse(FoldPosture.FLAT.isCoverScreenLikely)
    }
}
