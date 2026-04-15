package io.github.jwyoon1220.pvm.hardware.interrupt

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory

/**
 * Routes INT instructions to registered JVM-side [InterruptHandler]s.
 * No interrupt code runs inside the VM's memory; interception happens entirely in the JVM.
 */
class InterruptDispatcher {
    private val handlers = mutableMapOf<Int, InterruptHandler>()

    fun register(intNum: Int, handler: InterruptHandler): InterruptDispatcher {
        handlers[intNum] = handler
        return this
    }

    fun dispatch(intNum: Int, cpu: CPU, memory: Memory) {
        val handler = handlers[intNum]
            ?: throw UnsupportedOperationException(
                String.format("INT %02Xh: no handler registered", intNum)
            )
        handler.handle(intNum, cpu, memory)
    }

    companion object {
        /** Returns a dispatcher pre-loaded with the standard BIOS handlers (INT 10h, INT 16h). */
        fun withBiosDefaults(): InterruptDispatcher = InterruptDispatcher()
            .register(0x10, BiosInt10Handler)
            .register(0x16, BiosInt16Handler)
    }
}
