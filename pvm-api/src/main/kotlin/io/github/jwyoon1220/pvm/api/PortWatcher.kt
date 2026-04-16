package io.github.jwyoon1220.pvm.api

/**
 * Listener notified after every IN/OUT access on a watched I/O port.
 *
 * SAM-convertible:
 * ```kotlin
 * portService.addWatcher(0x60) { event -> println("KB port: ${event.value}") }
 * ```
 */
fun interface PortWatcher {
    fun onEvent(event: PortEvent)
}
