package io.github.jwyoon1220.pvm.core.decode

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.api.VmInput
import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.VM
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecoderPipelineTest {

    private fun makeVm(memSize: Int = 1024 * 1024): VM = VM(memory = MemoryBus(memSize))

    private fun makeVmContext(cpu: CPU, memory: MemoryBus): VmContext = object : VmContext {
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
        override val input: VmInput = object : VmInput {
            override fun read() = -1
            override fun hasInput() = false
        }
        override val output: VmOutput = VmOutput { /* discard */ }
        override fun read8(a: Int)  = memory.read8(a)
        override fun read16(a: Int) = memory.read16(a)
        override fun read32(a: Int) = memory.read32(a)
        override fun write8(a: Int, v: Int)  = memory.write8(a, v)
        override fun write16(a: Int, v: Int) = memory.write16(a, v)
        override fun write32(a: Int, v: Int) = memory.write32(a, v)
    }

    @Test
    fun `MOV EAX, EBX+ECX times 4+0x10 is decoded by state chain`() {
        val memory = MemoryBus(1024)
        val cpu = CPU()
        val ports = PortIOService()
        val interrupts = InterruptService()
        val pipeline = DecoderPipeline()

        cpu.eip = 0
        cpu.ebx = 0x100
        cpu.ecx = 0x3

        val targetAddress = cpu.ebx + (cpu.ecx * 4) + 0x10
        memory.write32(targetAddress, 0x1234_5678)

        // 8B 44 8B 10 -> MOV EAX, [EBX + ECX*4 + 0x10]
        memory.write8(0, 0x8B)
        memory.write8(1, 0x44)
        memory.write8(2, 0x8B)
        memory.write8(3, 0x10)

        val vmCtx = makeVmContext(cpu, memory)
        val trace = pipeline.run(cpu, memory, ports, interrupts, vmCtx, collectTrace = true)

        assertEquals(0x1234_5678, cpu.eax)
        assertEquals(4, cpu.eip)
        assertEquals(
            listOf(
                "PrefixState",
                "OpcodeState(opcode=8B, mnemonic=MOV r32, r/m32)",
                "ModRmState(modRM=44)",
                "SibState(sib=8B)",
                "DisplacementState(displacement=16)",
                "ExecuteState(MOV r32, r/m32)"
            ),
            trace
        )
        memory.close()
    }

    @Test
    fun `HLT sets cpu halted flag`() {
        val vm = makeVm()
        vm.memory.write8(0, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertTrue(vm.cpu.halted)
        vm.close()
    }

    @Test
    fun `PUSH and POP round-trip preserves value`() {
        val vm = makeVm()
        vm.cpu.esp = 0x1000
        vm.cpu.eax = 0xDEADBEEF.toInt()
        vm.memory.write8(0, 0x50); vm.memory.write8(1, 0x5B); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(0xDEADBEEF.toInt(), vm.cpu.ebx)
        assertEquals(0x1000, vm.cpu.esp)
        vm.close()
    }

    @Test
    fun `ADD EAX ECX updates EAX and ZF`() {
        val vm = makeVm()
        vm.cpu.eax = 5; vm.cpu.ecx = 10
        vm.memory.write8(0, 0x03); vm.memory.write8(1, 0xC1); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(15, vm.cpu.eax)
        assertEquals(0, (vm.cpu.eflags shr 6) and 1)
        vm.close()
    }

    @Test
    fun `CMP sets ZF when operands are equal`() {
        val vm = makeVm()
        vm.cpu.eax = 42; vm.cpu.ecx = 42
        vm.memory.write8(0, 0x3B); vm.memory.write8(1, 0xC1); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(42, vm.cpu.eax)
        assertEquals(1, (vm.cpu.eflags shr 6) and 1)
        vm.close()
    }

    @Test
    fun `JE jumps when ZF=1`() {
        val vm = makeVm()
        var addr = 0
        vm.memory.write8(addr++, 0x3B); vm.memory.write8(addr++, 0xC0)
        vm.memory.write8(addr++, 0x74); vm.memory.write8(addr++, 0x05)
        vm.memory.write8(addr++, 0xB9)
        vm.memory.write8(addr++, 99);  vm.memory.write8(addr++, 0)
        vm.memory.write8(addr++, 0);   vm.memory.write8(addr++, 0)
        vm.memory.write8(addr++, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(0, vm.cpu.ecx)
        vm.close()
    }

    @Test
    fun `INC increments without touching CF`() {
        val vm = makeVm()
        vm.cpu.eax = Int.MAX_VALUE; vm.cpu.ecx = 1
        vm.memory.write8(0, 0x03); vm.memory.write8(1, 0xC1)
        vm.memory.write8(2, 0x41)
        vm.memory.write8(3, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(2, vm.cpu.ecx)
        assertEquals(0, vm.cpu.eflags and 1)
        vm.close()
    }

    @Test
    fun `CALL and RET execute subroutine`() {
        val vm = makeVm()
        vm.cpu.esp = 0x1000
        vm.memory.write8(0, 0xB8); vm.memory.write32(1, 42)
        vm.memory.write8(5, 0xE8); vm.memory.write32(6, 5)
        vm.memory.write8(10, 0xF4)
        vm.memory.write8(11, 0x90); vm.memory.write8(12, 0x90)
        vm.memory.write8(13, 0x90); vm.memory.write8(14, 0x90)
        vm.memory.write8(15, 0xBB); vm.memory.write32(16, 99)
        vm.memory.write8(20, 0xC3)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(42, vm.cpu.eax)
        assertEquals(99, vm.cpu.ebx)
        vm.close()
    }

    @Test
    fun `MOV reg16 imm16 with 0x66 prefix updates lower 16 bits only`() {
        val vm = makeVm()
        vm.cpu.eax = 0xDEAD_0000.toInt()
        vm.memory.write8(0, 0x66); vm.memory.write8(1, 0xB8)
        vm.memory.write8(2, 0x34); vm.memory.write8(3, 0x12)
        vm.memory.write8(4, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(0xDEAD_1234.toInt(), vm.cpu.eax)
        vm.close()
    }

    @Test
    fun `MOV reg8 imm8 sets AH correctly`() {
        val vm = makeVm()
        vm.cpu.eax = 0
        vm.memory.write8(0, 0xB4); vm.memory.write8(1, 0x0E)
        vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.run()
        assertEquals(0x0E, vm.cpu.ah)
        assertEquals(0, vm.cpu.al)
        vm.close()
    }
}
