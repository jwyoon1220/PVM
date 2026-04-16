package io.github.jwyoon1220.pvm.api

/**
 * Service for attaching reactive watchers to physical memory regions.
 *
 * Watchers are notified for every write that overlaps the registered range.
 * Use a lambda (SAM) or implement [MemoryWatcher] directly:
 * ```kotlin
 * memoryService.addWatcher(0xB8000..0xBFFFF) { event ->
 *     println("VRAM write @0x${event.address.toString(16)}: ${event.value}")
 * }
 * ```
 */
interface IMemoryService {

    /** Registers a [MemoryWatcher] for the given inclusive address [range]. */
    fun addWatcher(range: IntRange, watcher: MemoryWatcher)

    /** Convenience overload accepting a lambda. */
    fun addWatcher(range: IntRange, watcher: (MemoryChangedEvent) -> Unit) =
        addWatcher(range, MemoryWatcher { watcher(it) })

    companion object {
        /** No-op singleton for contexts that do not provide a real memory service. */
        val NOOP: IMemoryService = object : IMemoryService {
            override fun addWatcher(range: IntRange, watcher: MemoryWatcher) {}
        }
    }
}
