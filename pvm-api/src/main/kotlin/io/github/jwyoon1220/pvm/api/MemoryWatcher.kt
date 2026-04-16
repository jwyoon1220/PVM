package io.github.jwyoon1220.pvm.api

/**
 * Listener notified when a memory write occurs in a watched address range.
 *
 * Implement this interface directly or pass a lambda — the `fun interface`
 * declaration enables Kotlin SAM conversion:
 * ```kotlin
 * memoryService.addWatcher(0xB8000..0xBFFFF) { event -> ... }
 * ```
 */
fun interface MemoryWatcher {
    fun onChanged(event: MemoryChangedEvent)
}
