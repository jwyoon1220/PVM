package io.github.jwyoon1220.pvm.api

/**
 * Event fired after a software interrupt handler has finished executing.
 *
 * The [context] reference points to the live VM state, allowing watchers to
 * inspect or modify registers at the point when the interrupt returns.
 *
 * @param vector   Interrupt vector number (0x00–0xFF).
 * @param context  Live VM register/memory context at interrupt-return time.
 */
data class InterruptEvent(val vector: Int, val context: VmContext)
