package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.IPortService
import io.github.jwyoon1220.pvm.api.PortEvent
import io.github.jwyoon1220.pvm.api.PortIOHandler
import io.github.jwyoon1220.pvm.api.PortWatcher

/**
 * Core implementation of [IPortService].
 *
 * Both handler dispatch and watcher notification are O(1) via direct array
 * indexing over the full 16-bit I/O port space (0x0000–0xFFFF).
 * Watcher lists are allocated lazily so unmonitored ports carry no overhead.
 */
class PortIOService : IPortService {

    private val handlers = arrayOfNulls<PortIOHandler>(65536)
    private val watchers = arrayOfNulls<MutableList<PortWatcher>>(65536)

    override fun register(port: Int, handler: PortIOHandler) {
        handlers[port] = handler
    }

    override fun addWatcher(port: Int, watcher: PortWatcher) {
        val list = watchers[port] ?: ArrayList<PortWatcher>(2).also { watchers[port] = it }
        list.add(watcher)
    }

    override fun in8(port: Int): Int {
        val value = handlers[port]?.read(port)
            ?: throw UnsupportedOperationException(String.format("IN8 port 0x%04X: no handler", port))
        notifyWatchers(port, value, isWrite = false)
        return value
    }

    override fun out8(port: Int, value: Int) {
        handlers[port]?.write(port, value)
            ?: throw UnsupportedOperationException(String.format("OUT8 port 0x%04X: no handler", port))
        notifyWatchers(port, value, isWrite = true)
    }

    private fun notifyWatchers(port: Int, value: Int, isWrite: Boolean) {
        val list = watchers[port] ?: return
        val event = PortEvent(port, value, isWrite)
        for (i in list.indices) list[i].onEvent(event)
    }
}
