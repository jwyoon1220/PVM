package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.IPortIOService
import io.github.jwyoon1220.pvm.api.PortIOHandler
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

class PortIOService : IPortIOService {
    private val handlers = Int2ObjectOpenHashMap<PortIOHandler>()

    override fun register(port: Int, handler: PortIOHandler) {
        handlers.put(port, handler)
    }

    override fun in8(port: Int): Int {
        return handlers.get(port)?.read(port)
            ?: throw UnsupportedOperationException(String.format("IN8 port 0x%04X: no handler", port))
    }

    override fun out8(port: Int, value: Int) {
        handlers.get(port)?.write(port, value)
            ?: throw UnsupportedOperationException(String.format("OUT8 port 0x%04X: no handler", port))
    }
}
