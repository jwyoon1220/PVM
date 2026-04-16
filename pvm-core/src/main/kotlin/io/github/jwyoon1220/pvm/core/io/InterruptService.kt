package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.IInterruptService
import io.github.jwyoon1220.pvm.api.InterruptHandler
import io.github.jwyoon1220.pvm.api.VmContext
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

class InterruptService : IInterruptService {
    private val handlers = Int2ObjectOpenHashMap<InterruptHandler>()

    override fun register(vector: Int, handler: InterruptHandler) {
        handlers.put(vector, handler)
    }

    fun dispatch(vector: Int, ctx: VmContext) {
        handlers.get(vector)?.handle(vector, ctx)
            ?: throw UnsupportedOperationException(String.format("INT %02Xh: no handler registered", vector))
    }
}
