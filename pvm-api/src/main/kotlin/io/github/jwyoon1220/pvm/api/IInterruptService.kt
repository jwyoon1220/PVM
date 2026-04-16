package io.github.jwyoon1220.pvm.api

/**
 * Service for registering JVM-side interrupt handlers and reactive watchers.
 *
 * Watchers are notified **after** the handler finishes, giving access to the
 * post-interrupt register state via [InterruptEvent.context].
 *
 * Usage:
 * ```kotlin
 * interruptService.register(0x10) { vector, ctx -> handleVideo(ctx) }
 *
 * interruptService.addWatcher(0x10) { event ->
 *     println("INT 10h fired, AH=${event.context.ah}")
 * }
 * ```
 */
interface IInterruptService {

    /** Registers a JVM-side [handler] for the given interrupt [vector]. */
    fun register(vector: Int, handler: InterruptHandler)

    /** Registers an [InterruptWatcher] to observe every firing of [vector]. */
    fun addWatcher(vector: Int, watcher: InterruptWatcher)

    /** Convenience overload accepting a lambda. */
    fun addWatcher(vector: Int, watcher: (InterruptEvent) -> Unit) =
        addWatcher(vector, InterruptWatcher { watcher(it) })

    companion object {
        /** No-op singleton used as a safe default where no real service is available. */
        val NOOP: IInterruptService = object : IInterruptService {
            override fun register(vector: Int, handler: InterruptHandler) {}
            override fun addWatcher(vector: Int, watcher: InterruptWatcher) {}
        }
    }
}
