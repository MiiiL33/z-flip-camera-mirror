package com.flipmirror.app.spikes

/**
 * Ring buffer compartido de eventos de disponibilidad de cámara.
 *
 * Lo alimentan la [SpikeLabActivity] (mientras está visible) y el
 * [SpikeCameraWatchService], de forma que el log mostrado en pantalla une
 * ambas fuentes. Es Kotlin puro para poder cubrirlo con unit tests en JVM.
 */
object CameraEventLog {
    const val MAX_EVENTS = 20

    private val events = ArrayDeque<CameraEvent>()
    private val listeners = mutableListOf<(List<CameraEvent>) -> Unit>()

    data class CameraEvent(
        val timestampMillis: Long,
        val cameraId: String,
        val available: Boolean,
        val source: String,
    )

    @Synchronized
    fun add(event: CameraEvent) {
        events.addLast(event)
        while (events.size > MAX_EVENTS) {
            events.removeFirst()
        }
        val snapshot = events.toList()
        listeners.toList().forEach { it(snapshot) }
    }

    /** Copia inmutable de los eventos, del más antiguo al más reciente. */
    @Synchronized
    fun snapshot(): List<CameraEvent> = events.toList()

    @Synchronized
    fun addListener(listener: (List<CameraEvent>) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (List<CameraEvent>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun clear() {
        events.clear()
    }
}
