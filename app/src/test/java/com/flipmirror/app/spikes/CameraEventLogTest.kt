package com.flipmirror.app.spikes

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CameraEventLogTest {
    @Before
    fun limpiarLog() {
        CameraEventLog.clear()
    }

    private fun evento(indice: Int): CameraEventLog.CameraEvent =
        CameraEventLog.CameraEvent(
            timestampMillis = indice.toLong(),
            cameraId = indice.toString(),
            available = indice % 2 == 0,
            source = "test",
        )

    @Test
    fun elRingBufferNoSuperaLaCapacidadMaxima() {
        repeat(CameraEventLog.MAX_EVENTS + 7) { CameraEventLog.add(evento(it)) }

        assertEquals(CameraEventLog.MAX_EVENTS, CameraEventLog.snapshot().size)
    }

    @Test
    fun alDesbordarseDescartaLosMasAntiguosYConservaElOrdenDeInsercion() {
        repeat(CameraEventLog.MAX_EVENTS + 7) { CameraEventLog.add(evento(it)) }

        val ids = CameraEventLog.snapshot().map { it.cameraId }
        val esperados = (7 until CameraEventLog.MAX_EVENTS + 7).map { it.toString() }
        assertEquals(esperados, ids)
    }

    @Test
    fun elListenerRecibeElSnapshotConElEventoNuevo() {
        var recibido: List<CameraEventLog.CameraEvent> = emptyList()
        val listener: (List<CameraEventLog.CameraEvent>) -> Unit = { recibido = it }

        CameraEventLog.addListener(listener)
        try {
            CameraEventLog.add(evento(1))
        } finally {
            CameraEventLog.removeListener(listener)
        }

        assertEquals(listOf("1"), recibido.map { it.cameraId })
    }
}
