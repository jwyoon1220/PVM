package io.github.jwyoon1220.pvm.core

import io.github.jwyoon1220.pvm.api.VmInput
import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.engine.ExecutionEngine
import io.github.jwyoon1220.pvm.core.engine.InterpreterEngine
import io.github.jwyoon1220.pvm.core.io.TerminalInput
import io.github.jwyoon1220.pvm.core.io.TerminalOutput
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Fluent builder for [VM] instances.
 *
 * Example:
 * ```kotlin
 * val vm = VMBuilder()
 *     .memorySize(1024 * 1024)
 *     .input(TerminalInput())
 *     .output(TerminalOutput())
 *     .build()
 *
 * vm.use {
 *     // Attach watchers before addons are enabled
 *     vm.vmContext.memoryService.addWatcher(0xB8000..0xBFFFF) { e ->
 *         println("VRAM write @0x${e.address.toString(16)}: 0x${e.value.toString(16)}")
 *     }
 *     vm.vmContext.portService.addWatcher(0x60) { e ->
 *         println("Keyboard port ${if (e.isWrite) "write" else "read"}: ${e.value}")
 *     }
 *     vm.vmContext.interruptService.addWatcher(0x10) { e ->
 *         println("INT 10h fired, AH=0x${e.context.ah.toString(16)}")
 *     }
 *
 *     vm.registerAddon(BiosVideoAddon())
 *     vm.loadAt(0x7C00, program)
 *     vm.cpu.eip = 0x7C00
 *     vm.run()
 * }
 * ```
 */
class VMBuilder {

    private var memorySize: Int = 1024 * 1024
    private var engine: ExecutionEngine = InterpreterEngine()
    private var input: VmInput = TerminalInput()
    private var output: VmOutput = TerminalOutput()

    /** Sets the physical memory size in bytes (default: 1 MiB). */
    fun memorySize(size: Int) = apply { memorySize = size }

    /** Sets the execution engine (interpreter or JIT). */
    fun engine(engine: ExecutionEngine) = apply { this.engine = engine }

    /** Sets the keyboard/stdin input source. */
    fun input(input: VmInput) = apply { this.input = input }

    /** Sets the display/stdout output sink. */
    fun output(output: VmOutput) = apply { this.output = output }

    /** Builds and returns a fully-configured [VM] instance. */
    fun build(): VM = VM(
        memory = MemoryBus(memorySize),
        engine = engine,
        vmInput = input,
        vmOutput = output
    )
}
