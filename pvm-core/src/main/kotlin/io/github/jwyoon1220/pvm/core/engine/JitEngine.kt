package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * JIT execution engine: compiles x86 basic blocks to JVM bytecode on first
 * execution and caches them for subsequent calls.
 *
 * Architecture
 * ────────────
 *  1. `step()` looks up the current EIP in [cache].
 *  2. Cache hit  → execute the pre-compiled [CompiledBlock] directly.
 *  3. Cache miss → [scanner] decodes a basic block, [compiler] translates it
 *     to JVM bytecode via ASM, the resulting class is dynamically loaded and
 *     cached, then executed.
 *
 * SMC (self-modifying code) support
 * ──────────────────────────────────
 * [JitBlockCache.registerSmcWatcher] installs a single [io.github.jwyoon1220.pvm.api.MemoryAccessor]
 * on [MemoryBus] that covers the whole physical address space.  Any write that
 * overlaps a compiled block's byte range immediately evicts that block from the
 * cache so it will be recompiled on next execution.
 *
 * Usage
 * ─────
 * Pass a `JitEngine` as the `engine` constructor argument of
 * [io.github.jwyoon1220.pvm.core.VM]:
 * ```kotlin
 * val vm = VM(engine = JitEngine())
 * ```
 * The engine self-registers its SMC watcher on the *first* call to [step].
 */
class JitEngine : ExecutionEngine {

    private val cache    = JitBlockCache()
    private val scanner  = BlockScanner()
    private val loader   = JitClassLoader()
    private val compiler = JitBlockCompiler(loader)

    @Volatile private var smcRegistered = false

    override fun step(
        cpu: CPU,
        memory: MemoryBus,
        ports: PortIOService,
        interrupts: InterruptService,
        vmContext: VmContext
    ): Boolean {
        if (cpu.halted) return false

        // Lazy SMC watcher registration (needs the MemoryBus reference).
        if (!smcRegistered) {
            cache.registerSmcWatcher(memory)
            smcRegistered = true
        }

        val eip   = cpu.eip
        val block = cache.get(eip) ?: compileAndCache(eip, memory)
        block.execute(cpu, memory, ports, interrupts, vmContext)
        return !cpu.halted
    }

    private fun compileAndCache(eip: Int, memory: MemoryBus): CompiledBlock {
        val basicBlock = scanner.scan(eip, memory)
        val compiled   = compiler.compile(basicBlock)
        cache.put(compiled)
        return compiled
    }
}
