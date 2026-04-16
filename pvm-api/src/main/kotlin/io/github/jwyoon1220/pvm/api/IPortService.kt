package io.github.jwyoon1220.pvm.api

/**
 * Unified service for I/O port handler registration, port access, and reactive watchers.
 *
 * Replaces the former `IPortIOService`.
 *
 * Usage:
 * ```kotlin
 * // Register a device handler
 * portService.register(0x3F8) { port -> ... }
 *
 * // Attach a watcher (lambda or PortWatcher implementation)
 * portService.addWatcher(0x60) { event ->
 *     println("Keyboard port ${if (event.isWrite) "write" else "read"}: ${event.value}")
 * }
 * ```
 */
interface IPortService {

    /** Registers an I/O [handler] for the given [port] number. */
    fun register(port: Int, handler: PortIOHandler)

    /** Registers a [PortWatcher] to observe every access on [port]. */
    fun addWatcher(port: Int, watcher: PortWatcher)

    /** Convenience overload accepting a lambda. */
    fun addWatcher(port: Int, watcher: (PortEvent) -> Unit) =
        addWatcher(port, PortWatcher { watcher(it) })

    /** Executes an IN instruction on [port] and returns the byte value read. */
    fun in8(port: Int): Int

    /** Executes an OUT instruction on [port] with [value]. */
    fun out8(port: Int, value: Int)

    companion object {
        /** No-op singleton — throws on actual I/O, used as a safe default. */
        val NOOP: IPortService = object : IPortService {
            override fun register(port: Int, handler: PortIOHandler) {}
            override fun addWatcher(port: Int, watcher: PortWatcher) {}
            override fun in8(port: Int): Int =
                throw UnsupportedOperationException(String.format("IN8 port 0x%04X: no handler", port))
            override fun out8(port: Int, value: Int) =
                throw UnsupportedOperationException(String.format("OUT8 port 0x%04X: no handler", port))
        }
    }
}
