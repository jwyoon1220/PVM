package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.MemoryAccessor
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

/**
 * Thread-unsafe cache that maps EIP addresses to [CompiledBlock]s.
 *
 * Two maps are maintained:
 * - [byEip] — O(1) lookup from a block's *start* EIP to its [CompiledBlock].
 * - [byAddress] — O(1) lookup from *any* byte address covered by a block to
 *   that block.  Used by the SMC write watcher to invalidate in O(1).
 *
 * SMC (self-modifying code) detection is wired via [registerSmcWatcher]:
 * any write to an address that belongs to a compiled block evicts that block.
 */
internal class JitBlockCache {

    private val byEip     = Int2ObjectOpenHashMap<CompiledBlock>()
    private val byAddress = Int2ObjectOpenHashMap<CompiledBlock>()

    // ── lookup ───────────────────────────────────────────────────────────────

    /** Returns the [CompiledBlock] whose [CompiledBlock.startEip] equals [eip], or null. */
    fun get(eip: Int): CompiledBlock? = byEip.getOrDefault(eip, null)

    // ── insertion ────────────────────────────────────────────────────────────

    /** Caches [block] and registers every byte in its EIP range for SMC tracking. */
    fun put(block: CompiledBlock) {
        byEip.put(block.startEip, block)
        for (addr in block.startEip until block.endEip) {
            byAddress.put(addr, block)
        }
    }

    // ── eviction ─────────────────────────────────────────────────────────────

    /**
     * Removes the block (if any) that covers [address].
     * All byte-address entries for that block's range are also removed.
     */
    fun invalidateAt(address: Int) {
        val block = byAddress.getOrDefault(address, null) ?: return
        byEip.remove(block.startEip)
        for (addr in block.startEip until block.endEip) {
            byAddress.remove(addr)
        }
    }

    // ── SMC watcher ──────────────────────────────────────────────────────────

    /**
     * Registers a single [MemoryAccessor] with [memory] that covers the entire
     * physical address space.  Any write into a compiled block's byte range will
     * automatically evict that block from the cache.
     */
    fun registerSmcWatcher(memory: MemoryBus) {
        memory.registerWriteAccessor(0, memory.physicalSize - 1, object : MemoryAccessor {
            override fun onWrite(address: Int, value: Int, byteCount: Int) {
                for (offset in 0 until byteCount) invalidateAt(address + offset)
            }
        })
    }
}
