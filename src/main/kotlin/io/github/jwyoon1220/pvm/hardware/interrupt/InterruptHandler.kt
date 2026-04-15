package io.github.jwyoon1220.pvm.hardware.interrupt

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory

/**
 * JVM-side handler for a single x86 interrupt vector.
 * Implementations intercept the interrupt and perform the action in the JVM
 * (no code is run inside the VM's memory).
 */
fun interface InterruptHandler {
    fun handle(intNum: Int, cpu: CPU, memory: Memory)
}
