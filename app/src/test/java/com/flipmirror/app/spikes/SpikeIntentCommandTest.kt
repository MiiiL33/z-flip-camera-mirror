package com.flipmirror.app.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeIntentCommandTest {
    @Test
    fun sinExtrasLaOrdenEstaVacia() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
            )

        assertNull(command.overlayDisplayId)
        assertFalse(command.dumpDisplays)
        assertTrue(command.isEmpty)
    }

    @Test
    fun unDisplayPositivoPideOverlayEnEseId() {
        val command = SpikeIntentCommand.fromRawExtras(overlayDisplay = 1, dumpDisplays = false)

        assertEquals(1, command.overlayDisplayId)
        assertFalse(command.isEmpty)
    }

    @Test
    fun elDisplayCeroEsUnIdValido() {
        val command = SpikeIntentCommand.fromRawExtras(overlayDisplay = 0, dumpDisplays = false)

        assertEquals(0, command.overlayDisplayId)
        assertFalse(command.isEmpty)
    }

    @Test
    fun cualquierIdNegativoSeIgnoraComoElDefault() {
        val command = SpikeIntentCommand.fromRawExtras(overlayDisplay = -5, dumpDisplays = false)

        assertNull(command.overlayDisplayId)
        assertTrue(command.isEmpty)
    }

    @Test
    fun soloDumpDisplaysTambienEsUnaOrden() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = true,
            )

        assertNull(command.overlayDisplayId)
        assertTrue(command.dumpDisplays)
        assertFalse(command.isEmpty)
    }

    @Test
    fun overlayYDumpPuedenLlegarJuntos() {
        val command = SpikeIntentCommand.fromRawExtras(overlayDisplay = 1, dumpDisplays = true)

        assertEquals(1, command.overlayDisplayId)
        assertTrue(command.dumpDisplays)
        assertFalse(command.isEmpty)
    }
}
