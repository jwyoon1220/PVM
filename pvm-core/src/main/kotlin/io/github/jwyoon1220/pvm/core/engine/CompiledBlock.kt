package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * A JIT-compiled x86 basic block.
 *
 * [startEip] is the address of the first byte of the block.
 * [endEip] is the address of the first byte *past* the last byte of the block
 * (i.e., the half-open range `[startEip, endEip)` covers every byte that was
 * translated into this block).  The range is used for SMC cache invalidation.
 */
interface CompiledBlock {
    val startEip: Int
    val endEip: Int
    fun execute(
        cpu: CPU,
        memory: MemoryBus,
        ports: PortIOService,
        interrupts: InterruptService,
        vmContext: VmContext
    )
}
