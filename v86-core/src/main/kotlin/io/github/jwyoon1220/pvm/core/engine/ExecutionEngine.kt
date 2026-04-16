package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Strategy interface for executing x86 instructions.
 * The default [InterpreterEngine] decodes one instruction at a time.
 */
interface ExecutionEngine {
    /**
     * Execute the next instruction at [CPU.eip].
     * Returns `true` if execution should continue, `false` if halted.
     */
    fun step(cpu: CPU, memory: MemoryBus, ports: PortIOService, interrupts: InterruptService, vmContext: VmContext): Boolean
}
