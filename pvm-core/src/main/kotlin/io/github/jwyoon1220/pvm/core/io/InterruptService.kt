package io.github.jwyoon1220.pvm.core.io

import io.github.jwyoon1220.pvm.api.IInterruptService
import io.github.jwyoon1220.pvm.api.InterruptEvent
import io.github.jwyoon1220.pvm.api.InterruptHandler
import io.github.jwyoon1220.pvm.api.InterruptWatcher
import io.github.jwyoon1220.pvm.api.VmContext

/**
 * Core implementation of [IInterruptService].
 *
 * Handler dispatch and watcher notification are O(1) via direct array indexing
 * over the full x86 interrupt vector table (0x00–0xFF).
 * Watcher lists are allocated lazily so unwatched vectors carry no overhead.
 *
 * Watchers are notified **after** the handler has returned, so [InterruptEvent.context]
 * reflects the post-interrupt register state.
 */
class InterruptService : IInterruptService {

    private val handlers = arrayOfNulls<InterruptHandler>(256)
    private val watchers = arrayOfNulls<MutableList<InterruptWatcher>>(256)

    override fun register(vector: Int, handler: InterruptHandler) {
        handlers[vector] = handler
    }

    override fun addWatcher(vector: Int, watcher: InterruptWatcher) {
        val list = watchers[vector] ?: ArrayList<InterruptWatcher>(2).also { watchers[vector] = it }
        list.add(watcher)
    }

    fun dispatch(vector: Int, ctx: VmContext) {
        handlers[vector]?.handle(vector, ctx)
            ?: throw UnsupportedOperationException(String.format("INT %02Xh: no handler registered", vector))
        notifyWatchers(vector, ctx)
    }

    private fun notifyWatchers(vector: Int, ctx: VmContext) {
        val list = watchers[vector] ?: return
        val event = InterruptEvent(vector, ctx)
        for (i in list.indices) list[i].onFired(event)
    }
}
