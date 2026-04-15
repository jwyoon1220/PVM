package io.github.jwyoon1220.pvm.hardware

import kotlin.test.Test
import kotlin.test.assertEquals

class DecoderStateFlowTest {
    @Test
    fun `MOV EAX, EBX+ECX times 4+0x10 is decoded by state chain`() {
        val memory = Memory(1024)
        val cpu = CPU()
        val decoder = Decoder(cpu, memory)

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

        val trace = decoder.stepWithTrace()

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
    }
}

