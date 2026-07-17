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
                cameraWatch = null,
            )

        assertNull(command.overlayDisplayId)
        assertFalse(command.dumpDisplays)
        assertNull(command.cameraWatch)
        assertTrue(command.isEmpty)
    }

    @Test
    fun unDisplayPositivoPideOverlayEnEseId() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = 1,
                dumpDisplays = false,
                cameraWatch = null,
            )

        assertEquals(1, command.overlayDisplayId)
        assertFalse(command.isEmpty)
    }

    @Test
    fun elDisplayCeroEsUnIdValido() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = 0,
                dumpDisplays = false,
                cameraWatch = null,
            )

        assertEquals(0, command.overlayDisplayId)
        assertFalse(command.isEmpty)
    }

    @Test
    fun cualquierIdNegativoSeIgnoraComoElDefault() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = -5,
                dumpDisplays = false,
                cameraWatch = null,
            )

        assertNull(command.overlayDisplayId)
        assertTrue(command.isEmpty)
    }

    @Test
    fun soloDumpDisplaysTambienEsUnaOrden() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = true,
                cameraWatch = null,
            )

        assertNull(command.overlayDisplayId)
        assertTrue(command.dumpDisplays)
        assertFalse(command.isEmpty)
    }

    @Test
    fun overlayYDumpPuedenLlegarJuntos() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = 1,
                dumpDisplays = true,
                cameraWatch = null,
            )

        assertEquals(1, command.overlayDisplayId)
        assertTrue(command.dumpDisplays)
        assertFalse(command.isEmpty)
    }

    @Test
    fun cameraWatchStartPideArrancarElService() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
                cameraWatch = SpikeIntentCommand.CAMERA_WATCH_START,
            )

        assertEquals(SpikeIntentCommand.CameraWatchAction.START, command.cameraWatch)
        assertFalse(command.isEmpty)
    }

    @Test
    fun cameraWatchStopPideDetenerElService() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
                cameraWatch = SpikeIntentCommand.CAMERA_WATCH_STOP,
            )

        assertEquals(SpikeIntentCommand.CameraWatchAction.STOP, command.cameraWatch)
        assertFalse(command.isEmpty)
    }

    @Test
    fun cameraWatchAusenteNoEsUnaOrden() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
                cameraWatch = null,
            )

        assertNull(command.cameraWatch)
        assertTrue(command.isEmpty)
    }

    @Test
    fun cameraWatchBasuraSeIgnoraComoAusente() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
                cameraWatch = "reiniciar",
            )

        assertNull(command.cameraWatch)
        assertTrue(command.isEmpty)
    }

    @Test
    fun cameraWatchDistingueMayusculas() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = SpikeIntentCommand.NO_OVERLAY_REQUESTED,
                dumpDisplays = false,
                cameraWatch = "START",
            )

        assertNull(command.cameraWatch)
        assertTrue(command.isEmpty)
    }

    @Test
    fun cameraWatchYOverlayPuedenLlegarJuntos() {
        val command =
            SpikeIntentCommand.fromRawExtras(
                overlayDisplay = 1,
                dumpDisplays = false,
                cameraWatch = SpikeIntentCommand.CAMERA_WATCH_START,
            )

        assertEquals(1, command.overlayDisplayId)
        assertEquals(SpikeIntentCommand.CameraWatchAction.START, command.cameraWatch)
        assertFalse(command.isEmpty)
    }
}
