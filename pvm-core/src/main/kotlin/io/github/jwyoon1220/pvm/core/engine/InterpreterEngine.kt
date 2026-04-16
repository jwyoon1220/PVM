package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.decode.DecoderPipeline
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Interpreter execution engine: fetches, decodes, and executes one x86
 * instruction per [step] call using the state-machine [DecoderPipeline].
 */
class InterpreterEngine(
    private val pipeline: DecoderPipeline = DecoderPipeline()
) : ExecutionEngine {
    override fun step(cpu: CPU, memory: MemoryBus, ports: PortIOService, interrupts: InterruptService, vmContext: VmContext): Boolean {
        if (cpu.halted) return false
        pipeline.run(cpu, memory, ports, interrupts, vmContext)
        return !cpu.halted
    }
}
