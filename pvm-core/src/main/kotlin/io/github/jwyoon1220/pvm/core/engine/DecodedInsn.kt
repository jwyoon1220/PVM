package io.github.jwyoon1220.pvm.core.engine

/**
 * Sealed class hierarchy that represents a single decoded x86 instruction.
 *
 * [startEip] is the address of the first byte of the instruction.
 * [endEip] is the address of the first byte of the *next* instruction.
 *
 * The last instruction in every basic block is always a [Terminator].
 */
sealed class DecodedInsn(val startEip: Int, val endEip: Int) {

    /** True for instructions that end a basic block (branches, RET, HLT, INT). */
    open val isTerminator: Boolean get() = false

    // ── Non-terminating instructions ─────────────────────────────────────────

    class Nop(s: Int, e: Int) : DecodedInsn(s, e)

    class PushR32(s: Int, e: Int, val reg: Int) : DecodedInsn(s, e)
    class PopR32 (s: Int, e: Int, val reg: Int) : DecodedInsn(s, e)
    class PushImm32(s: Int, e: Int, val imm: Int) : DecodedInsn(s, e)
    class PushImm8Se(s: Int, e: Int, val imm: Int) : DecodedInsn(s, e)

    class MovR32Imm32(s: Int, e: Int, val reg: Int, val imm: Int) : DecodedInsn(s, e)
    class MovR16Imm16(s: Int, e: Int, val reg: Int, val imm: Int) : DecodedInsn(s, e)
    class MovR8Imm8 (s: Int, e: Int, val reg: Int, val imm: Int) : DecodedInsn(s, e)

    /** [sib] is -1 when there is no SIB byte. */
    class MovRm32R32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class MovR32Rm32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)

    class AddRm32R32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class AddR32Rm32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class SubRm32R32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class SubR32Rm32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class CmpRm32R32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)
    class CmpR32Rm32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int) : DecodedInsn(s, e)

    class IncR32(s: Int, e: Int, val reg: Int) : DecodedInsn(s, e)
    class DecR32(s: Int, e: Int, val reg: Int) : DecodedInsn(s, e)

    class Grp1Rm32Imm32(s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int, val imm: Int) : DecodedInsn(s, e)
    class Grp1Rm32Imm8 (s: Int, e: Int, val modRM: Int, val sib: Int, val disp: Int, val imm: Int) : DecodedInsn(s, e)

    // ── Terminating instructions ──────────────────────────────────────────────

    sealed class Terminator(s: Int, e: Int) : DecodedInsn(s, e) {
        override val isTerminator: Boolean get() = true
    }

    /** Conditional branch; [targetEip] is the taken address. */
    class Jcc(s: Int, e: Int, val opcode: Int, val targetEip: Int, val fallThruEip: Int) : Terminator(s, e)

    class JmpRel8(s: Int, e: Int, val targetEip: Int) : Terminator(s, e)
    class JmpRel32(s: Int, e: Int, val targetEip: Int) : Terminator(s, e)
    class CallRel32(s: Int, e: Int, val rel: Int, val targetEip: Int) : Terminator(s, e)
    class Ret(s: Int, e: Int) : Terminator(s, e)
    class Hlt(s: Int, e: Int) : Terminator(s, e)
    class IntInsn(s: Int, e: Int, val vector: Int) : Terminator(s, e)
}
