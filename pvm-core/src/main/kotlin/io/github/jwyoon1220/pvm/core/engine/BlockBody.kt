package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Internal interface implemented by ASM-generated classes.
 * Each generated class holds the compiled body of one x86 basic block.
 * [CompiledBlock] wraps this together with [startEip]/[endEip] metadata.
 */
internal interface BlockBody {
    fun execute(
        cpu: CPU,
        memory: MemoryBus,
        ports: PortIOService,
        interrupts: InterruptService,
        vmContext: VmContext
    )
}

/** Combines a [BlockBody] (dynamically loaded) with its address metadata. */
internal class CompiledBlockImpl(
    override val startEip: Int,
    override val endEip: Int,
    private val body: BlockBody
) : CompiledBlock {
    override fun execute(
        cpu: CPU,
        memory: MemoryBus,
        ports: PortIOService,
        interrupts: InterruptService,
        vmContext: VmContext
    ) = body.execute(cpu, memory, ports, interrupts, vmContext)
}
