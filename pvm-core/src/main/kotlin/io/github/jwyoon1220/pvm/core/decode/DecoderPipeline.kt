package io.github.jwyoon1220.pvm.core.decode

import io.github.jwyoon1220.pvm.api.VmContext
import io.github.jwyoon1220.pvm.core.cpu.CPU
import io.github.jwyoon1220.pvm.core.io.InterruptService
import io.github.jwyoon1220.pvm.core.io.PortIOService
import io.github.jwyoon1220.pvm.core.memory.MemoryBus

class DecoderPipeline(
    private val opcodeHandler: OpcodeHandler = defaultOpcodeChain()
) {
    fun run(
        cpu: CPU,
        memory: MemoryBus,
        ports: PortIOService,
        interrupts: InterruptService,
        vmContext: VmContext,
        collectTrace: Boolean = false
    ): List<String> {
        val ctx = DecodeContext(cpu, memory, ports, interrupts, vmContext, collectTrace, opcodeHandler)
        var state: DecodeState? = PrefixState
        while (state != null) state = state.advance(ctx)
        return ctx.trace
    }
}

private fun defaultOpcodeChain(): OpcodeHandler {
    val unsupported = UnsupportedOpcodeHandler()
    val int_     = IntHandler(unsupported)
    val grp1I32  = Grp1Rm32Imm32Handler(int_)
    val grp1I8   = Grp1Rm32Imm8Handler(grp1I32)
    val hlt      = HltHandler(grp1I8)
    val ret      = RetHandler(hlt)
    val call32   = CallRel32Handler(ret)
    val jmpR8    = JmpRel8Handler(call32)
    val jcc      = JccRel8Handler(jmpR8)
    val cmpMR    = CmpRm32R32Handler(jcc)
    val cmpRM    = CmpR32Rm32Handler(cmpMR)
    val subMR    = SubRm32R32Handler(cmpRM)
    val subRM    = SubR32Rm32Handler(subMR)
    val addMR    = AddRm32R32Handler(subRM)
    val addRM    = AddR32Rm32Handler(addMR)
    val decR     = DecR32Handler(addRM)
    val incR     = IncR32Handler(decR)
    val movRI    = MovRegImmHandler(incR)
    val movMR    = MovRm32R32Handler(movRI)
    val movRM    = MovR32Rm32Handler(movMR)
    val pushI8   = PushImm8SeHandler(movRM)
    val pushI32  = PushImm32Handler(pushI8)
    val popR     = PopR32Handler(pushI32)
    val pushR    = PushR32Handler(popR)
    val nop      = NopHandler(pushR)
    return nop
}

// ─── Context ─────────────────────────────────────────────────────────────────

class DecodeContext(
    val cpu: CPU,
    val memory: MemoryBus,
    val ports: PortIOService,
    val interrupts: InterruptService,
    val vmContext: VmContext,
    private val collectTrace: Boolean,
    val opcodeHandler: OpcodeHandler
) {
    val prefixes: MutableList<Int> = mutableListOf()
    var opcode: Int = 0
    var modRM: Int? = null
    var sib: Int? = null
    var displacement: Int = 0
    var immediate: Long = 0
    lateinit var instruction: InstructionDescriptor
    val trace: MutableList<String> = mutableListOf()

    fun log(msg: String) { if (collectTrace) trace.add(msg) }

    fun fetch8(): Int  = memory.read8(cpu.eip).also { cpu.eip += 1 }
    fun fetch16(): Int = memory.read16(cpu.eip).also { cpu.eip += 2 }
    fun fetch32(): Int = memory.read32(cpu.eip).also { cpu.eip += 4 }
}

// ─── State Machine ────────────────────────────────────────────────────────────

private interface DecodeState { fun advance(ctx: DecodeContext): DecodeState? }

private object PrefixState : DecodeState {
    private val KNOWN = setOf(0x66, 0x67, 0x2E, 0x3E, 0x26, 0x64, 0x65, 0xF0, 0xF2, 0xF3)
    override fun advance(ctx: DecodeContext): DecodeState {
        while (true) {
            val b = ctx.memory.read8(ctx.cpu.eip)
            if (b !in KNOWN) break
            ctx.prefixes.add(b); ctx.cpu.eip++
        }
        ctx.log("PrefixState")
        return OpcodeState
    }
}

private object OpcodeState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState {
        ctx.opcode = ctx.fetch8()
        ctx.instruction = ctx.opcodeHandler.resolve(ctx.opcode, ctx)
        ctx.log(String.format("OpcodeState(opcode=%02X, mnemonic=%s)", ctx.opcode, ctx.instruction.name))
        return when {
            ctx.instruction.requiresModRM -> ModRmState
            ctx.instruction.immediateBytes > 0 -> ImmediateState
            else -> ExecuteState
        }
    }
}

private object ModRmState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState {
        ctx.modRM = ctx.fetch8()
        ctx.log(String.format("ModRmState(modRM=%02X)", ctx.modRM))
        val modRM = ctx.modRM!!
        val mod = (modRM shr 6) and 3; val rm = modRM and 7
        return if (mod != 3 && rm == 4) SibState else DisplacementState
    }
}

private object SibState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState {
        ctx.sib = ctx.fetch8()
        ctx.log(String.format("SibState(sib=%02X)", ctx.sib))
        return DisplacementState
    }
}

private object DisplacementState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState {
        val modRM = ctx.modRM ?: return next(ctx)
        val mod = (modRM shr 6) and 3; val rm = modRM and 7
        val size = when {
            mod == 1 -> 1
            mod == 2 -> 4
            mod == 0 && rm == 5 -> 4
            mod == 0 && rm == 4 && ctx.sib != null && (ctx.sib!! and 7) == 5 -> 4
            else -> 0
        }
        ctx.displacement = when (size) {
            1 -> ctx.fetch8().toByte().toInt()
            4 -> ctx.fetch32()
            else -> 0
        }
        ctx.log("DisplacementState(displacement=${ctx.displacement})")
        return next(ctx)
    }
    private fun next(ctx: DecodeContext) = if (ctx.instruction.immediateBytes > 0) ImmediateState else ExecuteState
}

private object ImmediateState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState {
        ctx.immediate = when (ctx.instruction.immediateBytes) {
            1 -> ctx.fetch8().toLong() and 0xFFL
            2 -> ctx.fetch16().toLong() and 0xFFFFL
            4 -> ctx.fetch32().toLong() and 0xFFFF_FFFFL
            else -> 0L
        }
        ctx.log("ImmediateState(immediate=${ctx.immediate})")
        return ExecuteState
    }
}

private object ExecuteState : DecodeState {
    override fun advance(ctx: DecodeContext): DecodeState? {
        ctx.log("ExecuteState(${ctx.instruction.name})")
        ctx.instruction.executor.execute(ctx)
        return null
    }
}

// ─── Handler Chain ────────────────────────────────────────────────────────────

interface OpcodeHandler { fun resolve(opcode: Int, ctx: DecodeContext): InstructionDescriptor }

private abstract class OpcodeHandlerLink(private val next: OpcodeHandler) : OpcodeHandler {
    final override fun resolve(opcode: Int, ctx: DecodeContext): InstructionDescriptor =
        tryResolve(opcode, ctx) ?: next.resolve(opcode, ctx)
    protected abstract fun tryResolve(opcode: Int, ctx: DecodeContext): InstructionDescriptor?
}

private fun instr(name: String, modRM: Boolean = false, imm: Int = 0, exec: InstructionExecutor) =
    InstructionDescriptor(name, modRM, imm, exec)

private class NopHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x90) instr("NOP", exec = NopExecutor) else null
}
private class PushR32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op in 0x50..0x57) instr("PUSH r32", exec = PushR32Executor) else null
}
private class PopR32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op in 0x58..0x5F) instr("POP r32", exec = PopR32Executor) else null
}
private class PushImm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x68) instr("PUSH imm32", imm = 4, exec = PushImm32Executor) else null
}
private class PushImm8SeHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x6A) instr("PUSH imm8", imm = 1, exec = PushImm8SeExecutor) else null
}
private class AddRm32R32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x01) instr("ADD r/m32, r32", modRM = true, exec = AddRm32R32Executor) else null
}
private class AddR32Rm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x03) instr("ADD r32, r/m32", modRM = true, exec = AddR32Rm32Executor) else null
}
private class SubRm32R32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x29) instr("SUB r/m32, r32", modRM = true, exec = SubRm32R32Executor) else null
}
private class SubR32Rm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x2B) instr("SUB r32, r/m32", modRM = true, exec = SubR32Rm32Executor) else null
}
private class CmpRm32R32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x39) instr("CMP r/m32, r32", modRM = true, exec = CmpRm32R32Executor) else null
}
private class CmpR32Rm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x3B) instr("CMP r32, r/m32", modRM = true, exec = CmpR32Rm32Executor) else null
}
private class IncR32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op in 0x40..0x47) instr("INC r32", exec = IncR32Executor) else null
}
private class DecR32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op in 0x48..0x4F) instr("DEC r32", exec = DecR32Executor) else null
}
private class MovRm32R32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x89) instr("MOV r/m32, r32", modRM = true, exec = MovRm32R32Executor) else null
}
private class MovR32Rm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x8B) instr("MOV r32, r/m32", modRM = true, exec = MovR32Rm32Executor) else null
}
private class MovRegImmHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext): InstructionDescriptor? {
        if (op in 0xB0..0xB7) return instr("MOV reg8, imm8", imm = 1, exec = MovReg8Imm8Executor)
        if (op in 0xB8..0xBF) {
            val is16 = 0x66 in ctx.prefixes
            return if (is16) instr("MOV reg16, imm16", imm = 2, exec = MovReg16Imm16Executor)
                   else      instr("MOV reg32, imm32", imm = 4, exec = MovReg32Imm32Executor)
        }
        return null
    }
}
private class JccRel8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext): InstructionDescriptor? {
        if (op !in 0x70..0x7F) return null
        val name = when(op) {
            0x70->"JO rel8"; 0x71->"JNO rel8"; 0x72->"JB rel8";  0x73->"JAE rel8"
            0x74->"JE rel8"; 0x75->"JNE rel8"; 0x76->"JBE rel8"; 0x77->"JA rel8"
            0x78->"JS rel8"; 0x79->"JNS rel8"; 0x7A->"JP rel8";  0x7B->"JNP rel8"
            0x7C->"JL rel8"; 0x7D->"JGE rel8"; 0x7E->"JLE rel8"; else->"JG rel8"
        }
        return instr(name, imm = 1, exec = JccRel8Executor)
    }
}
private class JmpRel8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0xEB) instr("JMP rel8", imm = 1, exec = JmpRel8Executor) else null
}
private class CallRel32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0xE8) instr("CALL rel32", imm = 4, exec = CallRel32Executor) else null
}
private class RetHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0xC3) instr("RET", exec = RetExecutor) else null
}
private class HltHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0xF4) instr("HLT", exec = HltExecutor) else null
}
private class Grp1Rm32Imm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x81) instr("Grp1 r/m32, imm32", modRM = true, imm = 4, exec = Grp1Rm32Imm32Executor) else null
}
private class Grp1Rm32Imm8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0x83) instr("Grp1 r/m32, imm8", modRM = true, imm = 1, exec = Grp1Rm32Imm8Executor) else null
}
private class IntHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = if (op == 0xCD) instr("INT imm8", imm = 1, exec = IntExecutor) else null
}
private class UnsupportedOpcodeHandler : OpcodeHandler {
    override fun resolve(opcode: Int, ctx: DecodeContext): InstructionDescriptor =
        throw UnsupportedOperationException(String.format("Unsupported opcode: %02Xh at EIP=%08Xh", opcode, ctx.cpu.eip - 1))
}

// ─── Descriptor & Executor ────────────────────────────────────────────────────

data class InstructionDescriptor(val name: String, val requiresModRM: Boolean, val immediateBytes: Int, val executor: InstructionExecutor)
interface InstructionExecutor { fun execute(ctx: DecodeContext) }

// ─── r/m32 helpers ────────────────────────────────────────────────────────────

private fun ea(ctx: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 3; val rm = modRM and 7
    if (rm == 4) {
        val sib = ctx.sib ?: error("SIB required")
        val scale = 1 shl ((sib shr 6) and 3)
        val idx = (sib shr 3) and 7; val base = sib and 7
        val idxVal = if (idx == 4) 0 else Reg32.of(idx).read(ctx.cpu)
        val baseVal = if (base == 5 && mod == 0) 0 else Reg32.of(base).read(ctx.cpu)
        return baseVal + idxVal * scale + ctx.displacement
    }
    val baseVal = if (rm == 5 && mod == 0) 0 else Reg32.of(rm).read(ctx.cpu)
    return baseVal + ctx.displacement
}

private fun readRM32(ctx: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 3; val rm = modRM and 7
    return if (mod == 3) Reg32.of(rm).read(ctx.cpu) else ctx.memory.read32(ea(ctx, modRM))
}

private fun writeRM32(ctx: DecodeContext, modRM: Int, v: Int) {
    val mod = (modRM shr 6) and 3; val rm = modRM and 7
    if (mod == 3) Reg32.of(rm).write(ctx.cpu, v) else ctx.memory.write32(ea(ctx, modRM), v)
}

// ─── EFLAGS helpers ───────────────────────────────────────────────────────────

private const val ARITH_CLEAR  = -0x8C2   // clears CF, ZF, SF, OF
private const val INCDEC_CLEAR = -0x8C1   // clears ZF, SF, OF; leaves CF

private fun setArithFlags(cpu: CPU, result: Int, a: Int, b: Int, add: Boolean) {
    val cf: Int; val of: Int
    if (add) {
        cf = ((a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) ushr 32).toInt() and 1
        of = (((a xor result) and (b xor result)) ushr 31) and 1
    } else {
        cf = if (Integer.compareUnsigned(a, b) < 0) 1 else 0
        of = (((a xor b) and (a xor result)) ushr 31) and 1
    }
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}

private fun setIncDecFlags(cpu: CPU, result: Int, old: Int, inc: Boolean) {
    val of = if (inc) (((old xor result) and (1 xor result)) ushr 31) and 1
             else    (((old xor 1) and (old xor result)) ushr 31) and 1
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and INCDEC_CLEAR) or (zf shl 6) or (sf shl 7) or (of shl 11)
}

// ─── Executors ────────────────────────────────────────────────────────────────

private object NopExecutor : InstructionExecutor { override fun execute(ctx: DecodeContext) {} }

private object PushR32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val v = Reg32.of(ctx.opcode - 0x50).read(ctx.cpu); ctx.cpu.esp -= 4; ctx.memory.write32(ctx.cpu.esp, v)
    }
}
private object PopR32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val v = ctx.memory.read32(ctx.cpu.esp); ctx.cpu.esp += 4; Reg32.of(ctx.opcode - 0x58).write(ctx.cpu, v)
    }
}
private object PushImm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { ctx.cpu.esp -= 4; ctx.memory.write32(ctx.cpu.esp, ctx.immediate.toInt()) }
}
private object PushImm8SeExecutor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { ctx.cpu.esp -= 4; ctx.memory.write32(ctx.cpu.esp, ctx.immediate.toByte().toInt()) }
}

private object AddRm32R32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val src = Reg32.of((m shr 3) and 7).read(ctx.cpu); val dst = readRM32(ctx, m)
        val r = dst + src; setArithFlags(ctx.cpu, r, dst, src, true); writeRM32(ctx, m, r)
    }
}
private object AddR32Rm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val ri = (m shr 3) and 7; val src = readRM32(ctx, m); val dst = Reg32.of(ri).read(ctx.cpu)
        val r = dst + src; setArithFlags(ctx.cpu, r, dst, src, true); Reg32.of(ri).write(ctx.cpu, r)
    }
}
private object SubRm32R32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val src = Reg32.of((m shr 3) and 7).read(ctx.cpu); val dst = readRM32(ctx, m)
        val r = dst - src; setArithFlags(ctx.cpu, r, dst, src, false); writeRM32(ctx, m, r)
    }
}
private object SubR32Rm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val ri = (m shr 3) and 7; val src = readRM32(ctx, m); val dst = Reg32.of(ri).read(ctx.cpu)
        val r = dst - src; setArithFlags(ctx.cpu, r, dst, src, false); Reg32.of(ri).write(ctx.cpu, r)
    }
}
private object CmpRm32R32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val src = Reg32.of((m shr 3) and 7).read(ctx.cpu); val dst = readRM32(ctx, m)
        setArithFlags(ctx.cpu, dst - src, dst, src, false)
    }
}
private object CmpR32Rm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val ri = (m shr 3) and 7; val src = readRM32(ctx, m); val dst = Reg32.of(ri).read(ctx.cpu)
        setArithFlags(ctx.cpu, dst - src, dst, src, false)
    }
}
private object IncR32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val reg = Reg32.of(ctx.opcode - 0x40); val old = reg.read(ctx.cpu); val r = old + 1
        setIncDecFlags(ctx.cpu, r, old, true); reg.write(ctx.cpu, r)
    }
}
private object DecR32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val reg = Reg32.of(ctx.opcode - 0x48); val old = reg.read(ctx.cpu); val r = old - 1
        setIncDecFlags(ctx.cpu, r, old, false); reg.write(ctx.cpu, r)
    }
}
private object MovRm32R32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; writeRM32(ctx, m, Reg32.of((m shr 3) and 7).read(ctx.cpu))
    }
}
private object MovR32Rm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, readRM32(ctx, m))
    }
}
private object MovReg32Imm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { Reg32.of(ctx.opcode - 0xB8).write(ctx.cpu, ctx.immediate.toInt()) }
}
private object MovReg16Imm16Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val r = Reg32.of(ctx.opcode - 0xB8); val cur = r.read(ctx.cpu)
        r.write(ctx.cpu, (cur and -0x10000) or (ctx.immediate.toInt() and 0xFFFF))
    }
}
private object MovReg8Imm8Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { Reg8.of(ctx.opcode - 0xB0).write(ctx.cpu, ctx.immediate.toInt() and 0xFF) }
}
private object JccRel8Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val rel = ctx.immediate.toByte().toInt(); val f = ctx.cpu.eflags
        val cf = f and 1; val zf = (f shr 6) and 1; val sf = (f shr 7) and 1
        val pf = (f shr 2) and 1; val of = (f shr 11) and 1
        val taken = when (ctx.opcode) {
            0x70 -> of==1; 0x71 -> of==0; 0x72 -> cf==1; 0x73 -> cf==0
            0x74 -> zf==1; 0x75 -> zf==0; 0x76 -> cf==1||zf==1; 0x77 -> cf==0&&zf==0
            0x78 -> sf==1; 0x79 -> sf==0; 0x7A -> pf==1; 0x7B -> pf==0
            0x7C -> sf!=of; 0x7D -> sf==of; 0x7E -> zf==1||sf!=of; else -> zf==0&&sf==of
        }
        if (taken) ctx.cpu.eip += rel
    }
}
private object JmpRel8Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { ctx.cpu.eip += ctx.immediate.toByte().toInt() }
}
private object CallRel32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        ctx.cpu.esp -= 4; ctx.memory.write32(ctx.cpu.esp, ctx.cpu.eip); ctx.cpu.eip += ctx.immediate.toInt()
    }
}
private object RetExecutor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        ctx.cpu.eip = ctx.memory.read32(ctx.cpu.esp); ctx.cpu.esp += 4
    }
}
private object HltExecutor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) { ctx.cpu.halted = true }
}
private object IntExecutor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        ctx.interrupts.dispatch(ctx.immediate.toInt() and 0xFF, ctx.vmContext)
    }
}
private object Grp1Rm32Imm32Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val reg = (m shr 3) and 7; val imm = ctx.immediate.toInt(); val src = readRM32(ctx, m)
        when (reg) {
            0 -> { val r = src+imm; setArithFlags(ctx.cpu,r,src,imm,true);  writeRM32(ctx,m,r) }
            5 -> { val r = src-imm; setArithFlags(ctx.cpu,r,src,imm,false); writeRM32(ctx,m,r) }
            7 -> setArithFlags(ctx.cpu, src-imm, src, imm, false)
            else -> throw UnsupportedOperationException("Grp1(81h) /reg=$reg not implemented")
        }
    }
}
private object Grp1Rm32Imm8Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val reg = (m shr 3) and 7; val imm = ctx.immediate.toByte().toInt(); val src = readRM32(ctx, m)
        when (reg) {
            0 -> { val r = src+imm; setArithFlags(ctx.cpu,r,src,imm,true);  writeRM32(ctx,m,r) }
            5 -> { val r = src-imm; setArithFlags(ctx.cpu,r,src,imm,false); writeRM32(ctx,m,r) }
            7 -> setArithFlags(ctx.cpu, src-imm, src, imm, false)
            else -> throw UnsupportedOperationException("Grp1(83h) /reg=$reg not implemented")
        }
    }
}

// ─── Register Banks ───────────────────────────────────────────────────────────

private enum class Reg32 {
    EAX, ECX, EDX, EBX, ESP, EBP, ESI, EDI;
    fun read(cpu: CPU): Int = when(this) {
        EAX->cpu.eax; ECX->cpu.ecx; EDX->cpu.edx; EBX->cpu.ebx
        ESP->cpu.esp; EBP->cpu.ebp; ESI->cpu.esi; EDI->cpu.edi
    }
    fun write(cpu: CPU, v: Int) = when(this) {
        EAX->{ cpu.eax=v }; ECX->{ cpu.ecx=v }; EDX->{ cpu.edx=v }; EBX->{ cpu.ebx=v }
        ESP->{ cpu.esp=v }; EBP->{ cpu.ebp=v }; ESI->{ cpu.esi=v }; EDI->{ cpu.edi=v }
    }
    companion object { fun of(i: Int) = entries[i and 7] }
}

private enum class Reg8 {
    AL, CL, DL, BL, AH, CH, DH, BH;
    fun read(cpu: CPU): Int = when(this) {
        AL->cpu.al; CL->cpu.cl; DL->cpu.dl; BL->cpu.bl
        AH->cpu.ah; CH->cpu.ch; DH->cpu.dh; BH->cpu.bh
    }
    fun write(cpu: CPU, v: Int) = when(this) {
        AL->{ cpu.al=v }; CL->{ cpu.cl=v }; DL->{ cpu.dl=v }; BL->{ cpu.bl=v }
        AH->{ cpu.ah=v }; CH->{ cpu.ch=v }; DH->{ cpu.dh=v }; BH->{ cpu.bh=v }
    }
    companion object { fun of(i: Int) = entries[i and 7] }
}
