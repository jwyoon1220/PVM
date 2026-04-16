package io.github.jwyoon1220.pvm.core

import io.github.jwyoon1220.pvm.api.AddonContext
import io.github.jwyoon1220.pvm.api.VmAddon
import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.engine.ExecutionEngine
import io.github.jwyoon1220.pvm.core.engine.InterpreterEngine
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Top-level VM orchestrator.
 */
class Vm(
    val memory: MemoryBus = MemoryBus(),
    val cpu: CPU = CPU(),
    val ports: PortIOService = PortIOService(),
    val interrupts: InterruptService = InterruptService(),
    private val engine: ExecutionEngine = InterpreterEngine()
) : AutoCloseable {

    private val addons = mutableListOf<VmAddon>()

    /** Bridge between VmContext (API) and the CPU + MemoryBus (core). */
    val vmContext: VmContext = object : VmContext {
        override var eax get() = cpu.eax; set(v) { cpu.eax = v }
        override var ebx get() = cpu.ebx; set(v) { cpu.ebx = v }
        override var ecx get() = cpu.ecx; set(v) { cpu.ecx = v }
        override var edx get() = cpu.edx; set(v) { cpu.edx = v }
        override var esi get() = cpu.esi; set(v) { cpu.esi = v }
        override var edi get() = cpu.edi; set(v) { cpu.edi = v }
        override var esp get() = cpu.esp; set(v) { cpu.esp = v }
        override var ebp get() = cpu.ebp; set(v) { cpu.ebp = v }
        override var eip get() = cpu.eip; set(v) { cpu.eip = v }
        override var eflags get() = cpu.eflags; set(v) { cpu.eflags = v }
        override var ax get() = cpu.ax; set(v) { cpu.ax = v }
        override var al get() = cpu.al; set(v) { cpu.al = v }
        override var ah get() = cpu.ah; set(v) { cpu.ah = v }
        override var bx get() = cpu.bx; set(v) { cpu.bx = v }
        override var bl get() = cpu.bl; set(v) { cpu.bl = v }
        override var bh get() = cpu.bh; set(v) { cpu.bh = v }
        override var cx get() = cpu.cx; set(v) { cpu.cx = v }
        override var cl get() = cpu.cl; set(v) { cpu.cl = v }
        override var ch get() = cpu.ch; set(v) { cpu.ch = v }
        override var dx get() = cpu.dx; set(v) { cpu.dx = v }
        override var dl get() = cpu.dl; set(v) { cpu.dl = v }
        override var dh get() = cpu.dh; set(v) { cpu.dh = v }
        override var halted get() = cpu.halted; set(v) { cpu.halted = v }
        override fun read8(address: Int)  = memory.read8(address)
        override fun read16(address: Int) = memory.read16(address)
        override fun read32(address: Int) = memory.read32(address)
        override fun write8(address: Int, value: Int)  = memory.write8(address, value)
        override fun write16(address: Int, value: Int) = memory.write16(address, value)
        override fun write32(address: Int, value: Int) = memory.write32(address, value)
    }

    fun registerAddon(addon: VmAddon) { addons.add(addon); addon.onInit() }

    fun loadAt(address: Int, data: ByteArray) = memory.load(address, data)

    fun run() {
        val ctx = AddonContext(ports, interrupts)
        addons.forEach { it.onLoad() }
        addons.forEach { it.onEnable(ctx) }
        try {
            while (engine.step(cpu, memory, ports, interrupts, vmContext)) { /* tight loop */ }
        } finally {
            addons.forEach { it.onDisable() }
        }
    }

    override fun close() = memory.close()
}
