package io.github.jwyoon1220.pvm.api

/**
 * JVM-side BIOS/HLE interrupt handler.
 * All implementations live in addons; no code executes inside VM memory.
 */
fun interface InterruptHandler {
    /** Called when the VM executes INT [vector]. [ctx] gives access to registers and memory. */
    fun handle(vector: Int, ctx: VmContext)
}
