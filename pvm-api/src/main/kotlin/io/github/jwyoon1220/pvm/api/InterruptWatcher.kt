package io.github.jwyoon1220.pvm.api

/**
 * Listener notified after a software interrupt handler has executed.
 *
 * SAM-convertible:
 * ```kotlin
 * interruptService.addWatcher(0x10) { event -> println("INT 10h, AH=${event.context.ah}") }
 * ```
 */
fun interface InterruptWatcher {
    fun onFired(event: InterruptEvent)
}
