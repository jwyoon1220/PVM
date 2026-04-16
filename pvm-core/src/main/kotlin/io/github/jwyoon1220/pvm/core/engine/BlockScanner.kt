package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.core.memory.MemoryBus

/**
 * Decodes a single x86 basic block from [MemoryBus] starting at [startEip].
 *
 * A basic block ends at the first terminating instruction (JMP, JCC, CALL,
 * RET, HLT, INT).  The decoder mirrors the opcode set supported by
 * [io.github.jwyoon1220.pvm.core.decode.DecoderPipeline] so compiled blocks
 * produce identical behaviour to the interpreter.
 */
internal class BlockScanner {

    data class BasicBlock(
        val insns: List<DecodedInsn>,
        val startEip: Int,
        val endEip: Int           // = last insn endEip
    )

    fun scan(startEip: Int, memory: MemoryBus): BasicBlock {
        val insns = mutableListOf<DecodedInsn>()
        var pos = startEip
        while (true) {
            val insn = decodeOne(pos, memory)
            insns.add(insn)
            pos = insn.endEip
            if (insn.isTerminator) break
        }
        return BasicBlock(insns, startEip, pos)
    }

    // ── low-level helpers ────────────────────────────────────────────────────

    private fun r8(memory: MemoryBus, pos: Int): Int = memory.read8(pos)
    private fun r32(memory: MemoryBus, pos: Int): Int = memory.read32(pos)

    private data class ModRMResult(val byte: Int, val sib: Int, val disp: Int, val end: Int)

    private fun readModRM(memory: MemoryBus, pos: Int): ModRMResult {
        var p = pos
        val modRM = r8(memory, p++)
        val mod = (modRM shr 6) and 3
        val rm  = modRM and 7
        val sib = if (mod != 3 && rm == 4) r8(memory, p++) else -1
        val dispSize = when {
            mod == 1 -> 1
            mod == 2 -> 4
            mod == 0 && rm == 5 -> 4
            mod == 0 && rm == 4 && sib != -1 && (sib and 7) == 5 -> 4
            else -> 0
        }
        val disp = when (dispSize) {
            1 -> { val d = r8(memory, p++).toByte().toInt(); d }
            4 -> { val d = r32(memory, p); p += 4; d }
            else -> 0
        }
        return ModRMResult(modRM, sib, disp, p)
    }

    // ── main decode dispatcher ───────────────────────────────────────────────

    private val KNOWN_PREFIXES = setOf(0x66, 0x67, 0x2E, 0x3E, 0x26, 0x64, 0x65, 0xF0, 0xF2, 0xF3)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun decodeOne(eip: Int, memory: MemoryBus): DecodedInsn {
        var pos = eip
        val prefixes = mutableListOf<Int>()
        while (true) {
            val b = r8(memory, pos)
            if (b !in KNOWN_PREFIXES) break
            prefixes.add(b); pos++
        }
        val is16 = 0x66 in prefixes
        val op = r8(memory, pos++)
        val s = eip

        return when {
            op == 0x90 -> DecodedInsn.Nop(s, pos)

            op in 0x50..0x57 -> DecodedInsn.PushR32(s, pos, op - 0x50)
            op in 0x58..0x5F -> DecodedInsn.PopR32(s, pos, op - 0x58)

            op == 0x68 -> { val imm = r32(memory, pos); pos += 4; DecodedInsn.PushImm32(s, pos, imm) }
            op == 0x6A -> { val imm = r8(memory, pos++).toByte().toInt(); DecodedInsn.PushImm8Se(s, pos, imm) }

            op == 0x01 -> { val m = readModRM(memory, pos); DecodedInsn.AddRm32R32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x03 -> { val m = readModRM(memory, pos); DecodedInsn.AddR32Rm32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x29 -> { val m = readModRM(memory, pos); DecodedInsn.SubRm32R32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x2B -> { val m = readModRM(memory, pos); DecodedInsn.SubR32Rm32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x39 -> { val m = readModRM(memory, pos); DecodedInsn.CmpRm32R32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x3B -> { val m = readModRM(memory, pos); DecodedInsn.CmpR32Rm32(s, m.end, m.byte, m.sib, m.disp) }

            op in 0x40..0x47 -> DecodedInsn.IncR32(s, pos, op - 0x40)
            op in 0x48..0x4F -> DecodedInsn.DecR32(s, pos, op - 0x48)

            op == 0x89 -> { val m = readModRM(memory, pos); DecodedInsn.MovRm32R32(s, m.end, m.byte, m.sib, m.disp) }
            op == 0x8B -> { val m = readModRM(memory, pos); DecodedInsn.MovR32Rm32(s, m.end, m.byte, m.sib, m.disp) }

            op in 0xB0..0xB7 -> {
                val imm = r8(memory, pos++)
                DecodedInsn.MovR8Imm8(s, pos, op - 0xB0, imm)
            }
            op in 0xB8..0xBF -> {
                if (is16) {
                    val imm = memory.read16(pos); pos += 2
                    DecodedInsn.MovR16Imm16(s, pos, op - 0xB8, imm)
                } else {
                    val imm = r32(memory, pos); pos += 4
                    DecodedInsn.MovR32Imm32(s, pos, op - 0xB8, imm)
                }
            }

            op == 0x81 -> {
                val m = readModRM(memory, pos); pos = m.end
                val imm = r32(memory, pos); pos += 4
                DecodedInsn.Grp1Rm32Imm32(s, pos, m.byte, m.sib, m.disp, imm)
            }
            op == 0x83 -> {
                val m = readModRM(memory, pos); pos = m.end
                val imm = r8(memory, pos++).toByte().toInt()
                DecodedInsn.Grp1Rm32Imm8(s, pos, m.byte, m.sib, m.disp, imm)
            }

            op in 0x70..0x7F -> {
                val rel = r8(memory, pos++).toByte().toInt()
                val fallThru = pos
                DecodedInsn.Jcc(s, pos, op, pos + rel, fallThru)
            }
            op == 0xEB -> {
                val rel = r8(memory, pos++).toByte().toInt()
                DecodedInsn.JmpRel8(s, pos, pos + rel)
            }
            op == 0xE9 -> {
                val rel = r32(memory, pos); pos += 4
                DecodedInsn.JmpRel32(s, pos, pos + rel)
            }
            op == 0xE8 -> {
                val rel = r32(memory, pos); pos += 4
                DecodedInsn.CallRel32(s, pos, rel, pos + rel)
            }
            op == 0xC3 -> DecodedInsn.Ret(s, pos)
            op == 0xF4 -> DecodedInsn.Hlt(s, pos)
            op == 0xCD -> { val vec = r8(memory, pos++); DecodedInsn.IntInsn(s, pos, vec) }

            else -> throw UnsupportedOperationException(
                String.format("BlockScanner: unsupported opcode %02Xh at EIP=%08Xh", op, eip)
            )
        }
    }
}
