package io.github.jwyoon1220.pvm.core.memory

import io.github.jwyoon1220.pvm.api.IMemoryService
import io.github.jwyoon1220.pvm.api.MemoryChangedEvent
import io.github.jwyoon1220.pvm.api.MemoryWatcher

/**
 * Core implementation of [IMemoryService].
 *
 * Bridges the high-level watcher API to the low-level
 * [MemoryBus.registerWriteAccessor] mechanism, wrapping every write
 * notification into a [MemoryChangedEvent].
 *
 * Performance: notifications are dispatched directly by [MemoryBus] through
 * its off-heap write path — no additional heap allocations per write beyond
 * the event object passed to the watcher.
 */
class MemoryService(private val bus: MemoryBus) : IMemoryService {

    override fun addWatcher(range: IntRange, watcher: MemoryWatcher) {
        bus.registerWriteAccessor(range.first, range.last) { address, value, byteCount ->
            watcher.onChanged(MemoryChangedEvent(address, value, byteCount))
        }
    }
}
