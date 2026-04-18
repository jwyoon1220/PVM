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
    val unsupported   = UnsupportedOpcodeHandler()
    val twoByteOp     = TwoByteOpcodeHandler(unsupported)
    val grp5          = Grp5Handler(twoByteOp)
    val grp4          = Grp4Handler(grp5)
    val grp3          = Grp3Handler(grp4)
    val flagsManip    = FlagsManipHandler(grp3)
    val stringOp      = StringOpHandler(flagsManip)
    val inOut         = InOutHandler(stringOp)
    val jmpRel32      = JmpRel32Handler(inOut)
    val int_          = IntHandler(jmpRel32)
    val grp2          = Grp2Handler(int_)
    val movRm8Imm8H   = MovRm8Imm8Handler(grp2)
    val movRm32Imm32H = MovRm32Imm32Handler(movRm8Imm8H)
    val imulImmH      = ImulImmHandler(movRm32Imm32H)
    val retImm        = RetImmHandler(imulImmH)
    val grp1I32       = Grp1Rm32Imm32Handler(retImm)
    val grp1I8        = Grp1Rm32Imm8Handler(grp1I32)
    val grp1Rm8Imm8   = Grp1Rm8Imm8Handler(grp1I8)
    val hlt           = HltHandler(grp1Rm8Imm8)
    val ret           = RetHandler(hlt)
    val misc16        = Misc16Handler(ret)
    val xchgEax       = XchgEaxHandler(misc16)
    val call32        = CallRel32Handler(xchgEax)
    val jmpR8         = JmpRel8Handler(call32)
    val jcc           = JccRel8Handler(jmpR8)
    val testXchg      = TestXchgHandler(jcc)
    val lea           = LeaHandler(testXchg)
    val movRm8H       = MovRm8Handler(lea)
    val cmpMR         = CmpRm32R32Handler(movRm8H)
    val cmpRM         = CmpR32Rm32Handler(cmpMR)
    val subMR         = SubRm32R32Handler(cmpRM)
    val subRM         = SubR32Rm32Handler(subMR)
    val addMR         = AddRm32R32Handler(subRM)
    val addRM         = AddR32Rm32Handler(addMR)
    val arithRm32     = ArithRm32Handler(addRM)
    val arithAccImm   = ArithAccImmHandler(arithRm32)
    val arithRm8      = ArithRm8Handler(arithAccImm)
    val decR          = DecR32Handler(arithRm8)
    val incR          = IncR32Handler(decR)
    val movRI         = MovRegImmHandler(incR)
    val movMR         = MovRm32R32Handler(movRI)
    val movRM         = MovR32Rm32Handler(movMR)
    val pushI8        = PushImm8SeHandler(movRM)
    val pushI32       = PushImm32Handler(pushI8)
    val popR          = PopR32Handler(pushI32)
    val pushR         = PushR32Handler(popR)
    val nop           = NopHandler(pushR)
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
    var subOpcode: Int = 0
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
private class Grp1Rm8Imm8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0x80) instr("Grp1 r/m8, imm8", modRM = true, imm = 1, exec = Grp1Rm8Imm8Executor) else null
}
private class ArithRm8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x00 -> instr("ADD r/m8, r8",  modRM = true, exec = AddRm8R8Executor)
        0x02 -> instr("ADD r8, r/m8",  modRM = true, exec = AddR8Rm8Executor)
        0x08 -> instr("OR r/m8, r8",   modRM = true, exec = OrRm8R8Executor)
        0x0A -> instr("OR r8, r/m8",   modRM = true, exec = OrR8Rm8Executor)
        0x10 -> instr("ADC r/m8, r8",  modRM = true, exec = AdcRm8R8Executor)
        0x12 -> instr("ADC r8, r/m8",  modRM = true, exec = AdcR8Rm8Executor)
        0x18 -> instr("SBB r/m8, r8",  modRM = true, exec = SbbRm8R8Executor)
        0x1A -> instr("SBB r8, r/m8",  modRM = true, exec = SbbR8Rm8Executor)
        0x20 -> instr("AND r/m8, r8",  modRM = true, exec = AndRm8R8Executor)
        0x22 -> instr("AND r8, r/m8",  modRM = true, exec = AndR8Rm8Executor)
        0x28 -> instr("SUB r/m8, r8",  modRM = true, exec = SubRm8R8Executor)
        0x2A -> instr("SUB r8, r/m8",  modRM = true, exec = SubR8Rm8Executor)
        0x30 -> instr("XOR r/m8, r8",  modRM = true, exec = XorRm8R8Executor)
        0x32 -> instr("XOR r8, r/m8",  modRM = true, exec = XorR8Rm8Executor)
        0x38 -> instr("CMP r/m8, r8",  modRM = true, exec = CmpRm8R8Executor)
        0x3A -> instr("CMP r8, r/m8",  modRM = true, exec = CmpR8Rm8Executor)
        else -> null
    }
}
private class ArithRm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x09 -> instr("OR r/m32, r32",  modRM = true, exec = OrRm32R32Executor)
        0x0B -> instr("OR r32, r/m32",  modRM = true, exec = OrR32Rm32Executor)
        0x11 -> instr("ADC r/m32, r32", modRM = true, exec = AdcRm32R32Executor)
        0x13 -> instr("ADC r32, r/m32", modRM = true, exec = AdcR32Rm32Executor)
        0x19 -> instr("SBB r/m32, r32", modRM = true, exec = SbbRm32R32Executor)
        0x1B -> instr("SBB r32, r/m32", modRM = true, exec = SbbR32Rm32Executor)
        0x21 -> instr("AND r/m32, r32", modRM = true, exec = AndRm32R32Executor)
        0x23 -> instr("AND r32, r/m32", modRM = true, exec = AndR32Rm32Executor)
        0x31 -> instr("XOR r/m32, r32", modRM = true, exec = XorRm32R32Executor)
        0x33 -> instr("XOR r32, r/m32", modRM = true, exec = XorR32Rm32Executor)
        else -> null
    }
}
private class ArithAccImmHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x04 -> instr("ADD AL, imm8",   imm = 1, exec = AddAlImm8Executor)
        0x05 -> instr("ADD EAX, imm32", imm = 4, exec = AddEaxImm32Executor)
        0x0C -> instr("OR AL, imm8",    imm = 1, exec = OrAlImm8Executor)
        0x0D -> instr("OR EAX, imm32",  imm = 4, exec = OrEaxImm32Executor)
        0x14 -> instr("ADC AL, imm8",   imm = 1, exec = AdcAlImm8Executor)
        0x15 -> instr("ADC EAX, imm32", imm = 4, exec = AdcEaxImm32Executor)
        0x1C -> instr("SBB AL, imm8",   imm = 1, exec = SbbAlImm8Executor)
        0x1D -> instr("SBB EAX, imm32", imm = 4, exec = SbbEaxImm32Executor)
        0x24 -> instr("AND AL, imm8",   imm = 1, exec = AndAlImm8Executor)
        0x25 -> instr("AND EAX, imm32", imm = 4, exec = AndEaxImm32Executor)
        0x2C -> instr("SUB AL, imm8",   imm = 1, exec = SubAlImm8Executor)
        0x2D -> instr("SUB EAX, imm32", imm = 4, exec = SubEaxImm32Executor)
        0x34 -> instr("XOR AL, imm8",   imm = 1, exec = XorAlImm8Executor)
        0x35 -> instr("XOR EAX, imm32", imm = 4, exec = XorEaxImm32Executor)
        0x3C -> instr("CMP AL, imm8",   imm = 1, exec = CmpAlImm8Executor)
        0x3D -> instr("CMP EAX, imm32", imm = 4, exec = CmpEaxImm32Executor)
        else -> null
    }
}
private class TestXchgHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x84 -> instr("TEST r/m8, r8",   modRM = true, exec = TestRm8R8Executor)
        0x85 -> instr("TEST r/m32, r32", modRM = true, exec = TestRm32R32Executor)
        0x86 -> instr("XCHG r8, r/m8",   modRM = true, exec = XchgR8Rm8Executor)
        0x87 -> instr("XCHG r32, r/m32", modRM = true, exec = XchgR32Rm32Executor)
        else -> null
    }
}
private class LeaHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0x8D) instr("LEA r32, m", modRM = true, exec = LeaR32MExecutor) else null
}
private class MovRm8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x88 -> instr("MOV r/m8, r8", modRM = true, exec = MovRm8R8Executor)
        0x8A -> instr("MOV r8, r/m8", modRM = true, exec = MovR8Rm8Executor)
        else -> null
    }
}
private class MovRm8Imm8Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0xC6) instr("MOV r/m8, imm8", modRM = true, imm = 1, exec = MovRm8Imm8Executor) else null
}
private class MovRm32Imm32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext): InstructionDescriptor? {
        if (op != 0xC7) return null
        val is16 = 0x66 in ctx.prefixes
        return if (is16) instr("MOV r/m16, imm16", modRM = true, imm = 2, exec = MovRm16Imm16Executor)
               else      instr("MOV r/m32, imm32", modRM = true, imm = 4, exec = MovRm32Imm32Executor)
    }
}
private class ImulImmHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x69 -> instr("IMUL r32, r/m32, imm32", modRM = true, imm = 4, exec = ImulR32Rm32Imm32Executor)
        0x6B -> instr("IMUL r32, r/m32, imm8",  modRM = true, imm = 1, exec = ImulR32Rm32Imm8Executor)
        else -> null
    }
}
private class RetImmHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0xC2) instr("RET imm16", imm = 2, exec = RetImmExecutor) else null
}
private class XchgEaxHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op in 0x91..0x97) instr("XCHG EAX, r32", exec = XchgEaxR32Executor) else null
}
private class Misc16Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0x98 -> instr("CBW/CWDE", exec = CbwCwdeExecutor)
        0x99 -> instr("CWD/CDQ",  exec = CwdCdqExecutor)
        0x9C -> instr("PUSHF",    exec = PushfExecutor)
        0x9D -> instr("POPF",     exec = PopfExecutor)
        0x9E -> instr("SAHF",     exec = SahfExecutor)
        0x9F -> instr("LAHF",     exec = LahfExecutor)
        else -> null
    }
}
private class Grp2Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0xC0 -> instr("Grp2 r/m8, imm8",  modRM = true, imm = 1, exec = Grp2Executor)
        0xC1 -> instr("Grp2 r/m32, imm8", modRM = true, imm = 1, exec = Grp2Executor)
        0xD0 -> instr("Grp2 r/m8, 1",     modRM = true,           exec = Grp2Executor)
        0xD1 -> instr("Grp2 r/m32, 1",    modRM = true,           exec = Grp2Executor)
        0xD2 -> instr("Grp2 r/m8, CL",    modRM = true,           exec = Grp2Executor)
        0xD3 -> instr("Grp2 r/m32, CL",   modRM = true,           exec = Grp2Executor)
        else -> null
    }
}
private class JmpRel32Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0xE9) instr("JMP rel32", imm = 4, exec = JmpRel32Executor) else null
}
private class InOutHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0xE4 -> instr("IN AL, imm8",    imm = 1, exec = InOutExecutor)
        0xE5 -> instr("IN EAX, imm8",   imm = 1, exec = InOutExecutor)
        0xE6 -> instr("OUT imm8, AL",   imm = 1, exec = InOutExecutor)
        0xE7 -> instr("OUT imm8, EAX",  imm = 1, exec = InOutExecutor)
        0xEC -> instr("IN AL, DX",               exec = InOutExecutor)
        0xED -> instr("IN EAX, DX",              exec = InOutExecutor)
        0xEE -> instr("OUT DX, AL",              exec = InOutExecutor)
        0xEF -> instr("OUT DX, EAX",             exec = InOutExecutor)
        else -> null
    }
}
private class FlagsManipHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0xFA -> instr("CLI", exec = CliExecutor)
        0xFB -> instr("STI", exec = StiExecutor)
        0xFC -> instr("CLD", exec = CldExecutor)
        0xFD -> instr("STD", exec = StdExecutor)
        else -> null
    }
}
private class StringOpHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) = when (op) {
        0xA4 -> instr("MOVSB", exec = StringOpExecutor)
        0xA5 -> instr("MOVSD", exec = StringOpExecutor)
        0xA6 -> instr("CMPSB", exec = StringOpExecutor)
        0xA7 -> instr("CMPSD", exec = StringOpExecutor)
        0xAA -> instr("STOSB", exec = StringOpExecutor)
        0xAB -> instr("STOSD", exec = StringOpExecutor)
        0xAC -> instr("LODSB", exec = StringOpExecutor)
        0xAD -> instr("LODSD", exec = StringOpExecutor)
        0xAE -> instr("SCASB", exec = StringOpExecutor)
        0xAF -> instr("SCASD", exec = StringOpExecutor)
        else -> null
    }
}
private class Grp3Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext): InstructionDescriptor? {
        if (op != 0xF6 && op != 0xF7) return null
        val modrm = ctx.memory.read8(ctx.cpu.eip)  // peek, don't advance EIP
        val reg   = (modrm shr 3) and 7
        val is8   = op == 0xF6
        val immBytes = if (reg == 0) (if (is8) 1 else 4) else 0
        return instr(if (is8) "Grp3 r/m8" else "Grp3 r/m32", modRM = true, imm = immBytes,
                     exec = if (is8) Grp3Rm8Executor else Grp3Rm32Executor)
    }
}
private class Grp4Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0xFE) instr("Grp4 r/m8", modRM = true, exec = Grp4Executor) else null
}
private class Grp5Handler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext) =
        if (op == 0xFF) instr("Grp5 r/m32", modRM = true, exec = Grp5Executor) else null
}
private class TwoByteOpcodeHandler(n: OpcodeHandler) : OpcodeHandlerLink(n) {
    override fun tryResolve(op: Int, ctx: DecodeContext): InstructionDescriptor? {
        if (op != 0x0F) return null
        val op2 = ctx.fetch8()
        ctx.subOpcode = op2
        return when {
            op2 in 0x80..0x8F -> {
                val name = when (op2) {
                    0x80->"JO rel32"; 0x81->"JNO rel32"; 0x82->"JB rel32";  0x83->"JAE rel32"
                    0x84->"JE rel32"; 0x85->"JNE rel32"; 0x86->"JBE rel32"; 0x87->"JA rel32"
                    0x88->"JS rel32"; 0x89->"JNS rel32"; 0x8A->"JP rel32";  0x8B->"JNP rel32"
                    0x8C->"JL rel32"; 0x8D->"JGE rel32"; 0x8E->"JLE rel32"; else->"JG rel32"
                }
                instr(name, imm = 4, exec = JccRel32Executor)
            }
            op2 == 0xAF -> instr("IMUL r32, r/m32", modRM = true, exec = ImulR32Rm32Executor)
            op2 == 0xB6 -> instr("MOVZX r32, r/m8",  modRM = true, exec = MovzxR32Rm8Executor)
            op2 == 0xB7 -> instr("MOVZX r32, r/m16", modRM = true, exec = MovzxR32Rm16Executor)
            op2 == 0xBE -> instr("MOVSX r32, r/m8",  modRM = true, exec = MovsxR32Rm8Executor)
            op2 == 0xBF -> instr("MOVSX r32, r/m16", modRM = true, exec = MovsxR32Rm16Executor)
            else -> throw UnsupportedOperationException(
                String.format("Unsupported 0F opcode: 0F %02Xh at EIP=%08Xh", op2, ctx.cpu.eip - 2))
        }
    }
}
private class UnsupportedOpcodeHandler : OpcodeHandler {
    override fun resolve(opcode: Int, ctx: DecodeContext): InstructionDescriptor =
        throw UnsupportedOperationException(String.format("Unsupported opcode: %02Xh at EIP=%08Xh", opcode, ctx.cpu.eip - 1))
}

// ─── Descriptor & Executor ────────────────────────────────────────────────────

data class InstructionDescriptor(val name: String, val requiresModRM: Boolean, val immediateBytes: Int, val executor: InstructionExecutor)
fun interface InstructionExecutor { fun execute(ctx: DecodeContext) }

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
private fun readRM8(ctx: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 3
    return if (mod == 3) Reg8.of(modRM and 7).read(ctx.cpu) else ctx.memory.read8(ea(ctx, modRM))
}
private fun writeRM8(ctx: DecodeContext, modRM: Int, v: Int) {
    val mod = (modRM shr 6) and 3
    if (mod == 3) Reg8.of(modRM and 7).write(ctx.cpu, v and 0xFF) else ctx.memory.write8(ea(ctx, modRM), v and 0xFF)
}
private fun readRM16(ctx: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 3
    return if (mod == 3) Reg32.of(modRM and 7).read(ctx.cpu) and 0xFFFF else ctx.memory.read16(ea(ctx, modRM))
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
private fun setLogicFlags32(cpu: CPU, result: Int) {
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or (zf shl 6) or (sf shl 7)
}
private fun setLogicFlags8(cpu: CPU, result: Int) {
    val r8 = result and 0xFF; val zf = if (r8 == 0) 1 else 0; val sf = (r8 shr 7) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or (zf shl 6) or (sf shl 7)
}
private fun setArithFlags8(cpu: CPU, result: Int, a: Int, b: Int, add: Boolean) {
    val r8 = result and 0xFF; val a8 = a and 0xFF; val b8 = b and 0xFF
    val cf: Int; val of: Int
    if (add) {
        cf = (a8 + b8) ushr 8 and 1
        of = (((a8 xor r8) and (b8 xor r8)) ushr 7) and 1
    } else {
        cf = if (Integer.compareUnsigned(a8, b8) < 0) 1 else 0
        of = (((a8 xor b8) and (a8 xor r8)) ushr 7) and 1
    }
    val zf = if (r8 == 0) 1 else 0; val sf = (r8 shr 7) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun setAdcFlags32(cpu: CPU, result: Int, a: Int, b: Int, cfIn: Int) {
    val sum = (a.toLong() and 0xFFFFFFFFL) + (b.toLong() and 0xFFFFFFFFL) + cfIn
    val cf = (sum ushr 32).toInt() and 1
    val of = (((a xor result) and (b xor result)) ushr 31) and 1
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun setSbbFlags32(cpu: CPU, result: Int, a: Int, b: Int, cfIn: Int) {
    val diff = (a.toLong() and 0xFFFFFFFFL) - (b.toLong() and 0xFFFFFFFFL) - cfIn
    val cf = if (diff < 0L) 1 else 0
    val of = (((a xor b) and (a xor result)) ushr 31) and 1
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun setAdcFlags8(cpu: CPU, result: Int, a: Int, b: Int, cfIn: Int) {
    val r8 = result and 0xFF; val a8 = a and 0xFF; val b8 = b and 0xFF
    val sum = a8 + b8 + cfIn; val cf = sum ushr 8 and 1
    val of = (((a8 xor r8) and (b8 xor r8)) ushr 7) and 1
    val zf = if (r8 == 0) 1 else 0; val sf = (r8 shr 7) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun setSbbFlags8(cpu: CPU, result: Int, a: Int, b: Int, cfIn: Int) {
    val r8 = result and 0xFF; val a8 = a and 0xFF; val b8 = b and 0xFF
    val diff = a8 - b8 - cfIn; val cf = if (diff < 0) 1 else 0
    val of = (((a8 xor b8) and (a8 xor r8)) ushr 7) and 1
    val zf = if (r8 == 0) 1 else 0; val sf = (r8 shr 7) and 1
    cpu.eflags = (cpu.eflags and ARITH_CLEAR) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun setIncDecFlags8(cpu: CPU, result: Int, old: Int, inc: Boolean) {
    val r8 = result and 0xFF; val o8 = old and 0xFF
    val of = if (inc) (((o8 xor r8) and (1 xor r8)) ushr 7) and 1
             else    (((o8 xor 1) and (o8 xor r8)) ushr 7) and 1
    val zf = if (r8 == 0) 1 else 0; val sf = (r8 shr 7) and 1
    cpu.eflags = (cpu.eflags and INCDEC_CLEAR) or (zf shl 6) or (sf shl 7) or (of shl 11)
}
private fun cfBit(cpu: CPU) = cpu.eflags and 1
private fun dfBit(cpu: CPU) = (cpu.eflags shr 10) and 1

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
        val m = ctx.modRM!!; val reg = (m shr 3) and 7; val imm = ctx.immediate.toInt()
        val src = readRM32(ctx, m); val cf = cfBit(ctx.cpu)
        when (reg) {
            0 -> { val r = src+imm;     setArithFlags(ctx.cpu,r,src,imm,true);   writeRM32(ctx,m,r) }
            1 -> { val r = src or imm;  setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            2 -> { val r = src+imm+cf;  setAdcFlags32(ctx.cpu,r,src,imm,cf);     writeRM32(ctx,m,r) }
            3 -> { val r = src-imm-cf;  setSbbFlags32(ctx.cpu,r,src,imm,cf);     writeRM32(ctx,m,r) }
            4 -> { val r = src and imm; setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            5 -> { val r = src-imm;     setArithFlags(ctx.cpu,r,src,imm,false);  writeRM32(ctx,m,r) }
            6 -> { val r = src xor imm; setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            7 -> setArithFlags(ctx.cpu, src-imm, src, imm, false)
        }
    }
}
private object Grp1Rm32Imm8Executor : InstructionExecutor {
    override fun execute(ctx: DecodeContext) {
        val m = ctx.modRM!!; val reg = (m shr 3) and 7; val imm = ctx.immediate.toByte().toInt()
        val src = readRM32(ctx, m); val cf = cfBit(ctx.cpu)
        when (reg) {
            0 -> { val r = src+imm;     setArithFlags(ctx.cpu,r,src,imm,true);   writeRM32(ctx,m,r) }
            1 -> { val r = src or imm;  setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            2 -> { val r = src+imm+cf;  setAdcFlags32(ctx.cpu,r,src,imm,cf);     writeRM32(ctx,m,r) }
            3 -> { val r = src-imm-cf;  setSbbFlags32(ctx.cpu,r,src,imm,cf);     writeRM32(ctx,m,r) }
            4 -> { val r = src and imm; setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            5 -> { val r = src-imm;     setArithFlags(ctx.cpu,r,src,imm,false);  writeRM32(ctx,m,r) }
            6 -> { val r = src xor imm; setLogicFlags32(ctx.cpu,r);               writeRM32(ctx,m,r) }
            7 -> setArithFlags(ctx.cpu, src-imm, src, imm, false)
        }
    }
}

// ─── Grp1 r/m8, imm8 executor ────────────────────────────────────────────────
private val Grp1Rm8Imm8Executor = InstructionExecutor { ctx ->
    val m = ctx.modRM!!; val reg = (m shr 3) and 7
    val imm = ctx.immediate.toInt() and 0xFF; val src = readRM8(ctx, m); val cf = cfBit(ctx.cpu)
    when (reg) {
        0 -> { val r=(src+imm) and 0xFF;     setArithFlags8(ctx.cpu,r,src,imm,true);  writeRM8(ctx,m,r) }
        1 -> { val r=(src or imm) and 0xFF;  setLogicFlags8(ctx.cpu,r);               writeRM8(ctx,m,r) }
        2 -> { val r=(src+imm+cf) and 0xFF;  setAdcFlags8(ctx.cpu,r,src,imm,cf);     writeRM8(ctx,m,r) }
        3 -> { val r=(src-imm-cf) and 0xFF;  setSbbFlags8(ctx.cpu,r,src,imm,cf);     writeRM8(ctx,m,r) }
        4 -> { val r=(src and imm) and 0xFF; setLogicFlags8(ctx.cpu,r);               writeRM8(ctx,m,r) }
        5 -> { val r=(src-imm) and 0xFF;     setArithFlags8(ctx.cpu,r,src,imm,false); writeRM8(ctx,m,r) }
        6 -> { val r=(src xor imm) and 0xFF; setLogicFlags8(ctx.cpu,r);               writeRM8(ctx,m,r) }
        7 -> setArithFlags8(ctx.cpu, (src-imm) and 0xFF, src, imm, false)
    }
}

// ─── 8-bit r/m8 ↔ r8 executors ───────────────────────────────────────────────
private val AddRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d+s) and 0xFF; setArithFlags8(ctx.cpu,r,d,s,true); writeRM8(ctx,m,r) }
private val AddR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d+s) and 0xFF; setArithFlags8(ctx.cpu,r,d,s,true); Reg8.of(ri).write(ctx.cpu,r) }
private val OrRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d or s) and 0xFF; setLogicFlags8(ctx.cpu,r); writeRM8(ctx,m,r) }
private val OrR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d or s) and 0xFF; setLogicFlags8(ctx.cpu,r); Reg8.of(ri).write(ctx.cpu,r) }
private val AdcRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d+s+cf) and 0xFF; setAdcFlags8(ctx.cpu,r,d,s,cf); writeRM8(ctx,m,r) }
private val AdcR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d+s+cf) and 0xFF; setAdcFlags8(ctx.cpu,r,d,s,cf); Reg8.of(ri).write(ctx.cpu,r) }
private val SbbRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d-s-cf) and 0xFF; setSbbFlags8(ctx.cpu,r,d,s,cf); writeRM8(ctx,m,r) }
private val SbbR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d-s-cf) and 0xFF; setSbbFlags8(ctx.cpu,r,d,s,cf); Reg8.of(ri).write(ctx.cpu,r) }
private val AndRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d and s) and 0xFF; setLogicFlags8(ctx.cpu,r); writeRM8(ctx,m,r) }
private val AndR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d and s) and 0xFF; setLogicFlags8(ctx.cpu,r); Reg8.of(ri).write(ctx.cpu,r) }
private val SubRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d-s) and 0xFF; setArithFlags8(ctx.cpu,r,d,s,false); writeRM8(ctx,m,r) }
private val SubR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d-s) and 0xFF; setArithFlags8(ctx.cpu,r,d,s,false); Reg8.of(ri).write(ctx.cpu,r) }
private val XorRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    val r=(d xor s) and 0xFF; setLogicFlags8(ctx.cpu,r); writeRM8(ctx,m,r) }
private val XorR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    val r=(d xor s) and 0xFF; setLogicFlags8(ctx.cpu,r); Reg8.of(ri).write(ctx.cpu,r) }
private val CmpRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); val d=readRM8(ctx,m)
    setArithFlags8(ctx.cpu, (d-s) and 0xFF, d, s, false) }
private val CmpR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM8(ctx,m); val d=Reg8.of(ri).read(ctx.cpu)
    setArithFlags8(ctx.cpu, (d-s) and 0xFF, d, s, false) }

// ─── 32-bit OR/ADC/SBB/AND/XOR executors ─────────────────────────────────────
private val OrRm32R32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); val d=readRM32(ctx,m)
    val r=d or s; setLogicFlags32(ctx.cpu,r); writeRM32(ctx,m,r) }
private val OrR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM32(ctx,m); val d=Reg32.of(ri).read(ctx.cpu)
    val r=d or s; setLogicFlags32(ctx.cpu,r); Reg32.of(ri).write(ctx.cpu,r) }
private val AdcRm32R32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); val d=readRM32(ctx,m)
    val r=d+s+cf; setAdcFlags32(ctx.cpu,r,d,s,cf); writeRM32(ctx,m,r) }
private val AdcR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val ri=(m shr 3) and 7; val s=readRM32(ctx,m); val d=Reg32.of(ri).read(ctx.cpu)
    val r=d+s+cf; setAdcFlags32(ctx.cpu,r,d,s,cf); Reg32.of(ri).write(ctx.cpu,r) }
private val SbbRm32R32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); val d=readRM32(ctx,m)
    val r=d-s-cf; setSbbFlags32(ctx.cpu,r,d,s,cf); writeRM32(ctx,m,r) }
private val SbbR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val cf=cfBit(ctx.cpu); val ri=(m shr 3) and 7; val s=readRM32(ctx,m); val d=Reg32.of(ri).read(ctx.cpu)
    val r=d-s-cf; setSbbFlags32(ctx.cpu,r,d,s,cf); Reg32.of(ri).write(ctx.cpu,r) }
private val AndRm32R32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); val d=readRM32(ctx,m)
    val r=d and s; setLogicFlags32(ctx.cpu,r); writeRM32(ctx,m,r) }
private val AndR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM32(ctx,m); val d=Reg32.of(ri).read(ctx.cpu)
    val r=d and s; setLogicFlags32(ctx.cpu,r); Reg32.of(ri).write(ctx.cpu,r) }
private val XorRm32R32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); val d=readRM32(ctx,m)
    val r=d xor s; setLogicFlags32(ctx.cpu,r); writeRM32(ctx,m,r) }
private val XorR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val s=readRM32(ctx,m); val d=Reg32.of(ri).read(ctx.cpu)
    val r=d xor s; setLogicFlags32(ctx.cpu,r); Reg32.of(ri).write(ctx.cpu,r) }

// ─── ImmAcc executors ─────────────────────────────────────────────────────────
private val AddAlImm8Executor   = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a+b) and 0xFF; setArithFlags8(ctx.cpu,r,a,b,true);  ctx.cpu.al=r }
private val AddEaxImm32Executor = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a+b; setArithFlags(ctx.cpu,r,a,b,true);  ctx.cpu.eax=r }
private val OrAlImm8Executor    = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a or b) and 0xFF; setLogicFlags8(ctx.cpu,r);  ctx.cpu.al=r }
private val OrEaxImm32Executor  = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a or b; setLogicFlags32(ctx.cpu,r); ctx.cpu.eax=r }
private val AdcAlImm8Executor   = InstructionExecutor { ctx -> val cf=cfBit(ctx.cpu); val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a+b+cf) and 0xFF; setAdcFlags8(ctx.cpu,r,a,b,cf);  ctx.cpu.al=r }
private val AdcEaxImm32Executor = InstructionExecutor { ctx -> val cf=cfBit(ctx.cpu); val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a+b+cf; setAdcFlags32(ctx.cpu,r,a,b,cf); ctx.cpu.eax=r }
private val SbbAlImm8Executor   = InstructionExecutor { ctx -> val cf=cfBit(ctx.cpu); val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a-b-cf) and 0xFF; setSbbFlags8(ctx.cpu,r,a,b,cf);  ctx.cpu.al=r }
private val SbbEaxImm32Executor = InstructionExecutor { ctx -> val cf=cfBit(ctx.cpu); val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a-b-cf; setSbbFlags32(ctx.cpu,r,a,b,cf); ctx.cpu.eax=r }
private val AndAlImm8Executor   = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a and b) and 0xFF; setLogicFlags8(ctx.cpu,r);  ctx.cpu.al=r }
private val AndEaxImm32Executor = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a and b; setLogicFlags32(ctx.cpu,r); ctx.cpu.eax=r }
private val SubAlImm8Executor   = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a-b) and 0xFF; setArithFlags8(ctx.cpu,r,a,b,false); ctx.cpu.al=r }
private val SubEaxImm32Executor = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a-b; setArithFlags(ctx.cpu,r,a,b,false); ctx.cpu.eax=r }
private val XorAlImm8Executor   = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; val r=(a xor b) and 0xFF; setLogicFlags8(ctx.cpu,r);  ctx.cpu.al=r }
private val XorEaxImm32Executor = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); val r=a xor b; setLogicFlags32(ctx.cpu,r); ctx.cpu.eax=r }
private val CmpAlImm8Executor   = InstructionExecutor { ctx -> val a=ctx.cpu.al; val b=ctx.immediate.toInt() and 0xFF; setArithFlags8(ctx.cpu,(a-b) and 0xFF,a,b,false) }
private val CmpEaxImm32Executor = InstructionExecutor { ctx -> val a=ctx.cpu.eax; val b=ctx.immediate.toInt(); setArithFlags(ctx.cpu,a-b,a,b,false) }

// ─── TEST, XCHG executors ─────────────────────────────────────────────────────
private val TestRm8R8Executor   = InstructionExecutor { ctx -> val m=ctx.modRM!!; val s=Reg8.of((m shr 3) and 7).read(ctx.cpu); setLogicFlags8(ctx.cpu,  readRM8(ctx,m)  and s) }
private val TestRm32R32Executor = InstructionExecutor { ctx -> val m=ctx.modRM!!; val s=Reg32.of((m shr 3) and 7).read(ctx.cpu); setLogicFlags32(ctx.cpu, readRM32(ctx,m) and s) }
private val XchgR8Rm8Executor   = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val t=Reg8.of(ri).read(ctx.cpu)
    Reg8.of(ri).write(ctx.cpu, readRM8(ctx,m)); writeRM8(ctx,m,t) }
private val XchgR32Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val t=Reg32.of(ri).read(ctx.cpu)
    Reg32.of(ri).write(ctx.cpu, readRM32(ctx,m)); writeRM32(ctx,m,t) }

// ─── LEA executor ────────────────────────────────────────────────────────────
private val LeaR32MExecutor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, ea(ctx,m)) }

// ─── MOV r/m8 executors ──────────────────────────────────────────────────────
private val MovRm8R8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; writeRM8(ctx,m, Reg8.of((m shr 3) and 7).read(ctx.cpu)) }
private val MovR8Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg8.of((m shr 3) and 7).write(ctx.cpu, readRM8(ctx,m)) }
private val MovRm8Imm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; writeRM8(ctx,m, ctx.immediate.toInt() and 0xFF) }
private val MovRm32Imm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; writeRM32(ctx,m, ctx.immediate.toInt()) }
private val MovRm16Imm16Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val addr = ea(ctx, m)
    val mod = (m shr 6) and 3
    if (mod == 3) {
        val reg = Reg32.of(m and 7); reg.write(ctx.cpu, (reg.read(ctx.cpu) and -0x10000) or (ctx.immediate.toInt() and 0xFFFF))
    } else {
        ctx.memory.write16(addr, ctx.immediate.toInt() and 0xFFFF)
    }
}

// ─── IMUL executors ──────────────────────────────────────────────────────────
private val ImulR32Rm32Imm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val src=readRM32(ctx,m); val imm=ctx.immediate.toInt()
    val prod=src.toLong() * imm.toLong()
    Reg32.of(ri).write(ctx.cpu, prod.toInt())
    val fits = prod == prod.toInt().toLong()
    val cf = if (!fits) 1 else 0
    ctx.cpu.eflags = (ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
private val ImulR32Rm32Imm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val src=readRM32(ctx,m); val imm=ctx.immediate.toByte().toInt()
    val prod=src.toLong() * imm.toLong()
    Reg32.of(ri).write(ctx.cpu, prod.toInt())
    val fits = prod == prod.toInt().toLong()
    val cf = if (!fits) 1 else 0
    ctx.cpu.eflags = (ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
private val ImulR32Rm32Executor = InstructionExecutor { ctx ->  // 0F AF
    val m=ctx.modRM!!; val ri=(m shr 3) and 7; val src=readRM32(ctx,m); val dst=Reg32.of(ri).read(ctx.cpu)
    val prod=dst.toLong() * src.toLong()
    Reg32.of(ri).write(ctx.cpu, prod.toInt())
    val fits = prod == prod.toInt().toLong()
    val cf = if (!fits) 1 else 0
    ctx.cpu.eflags = (ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }

// ─── RET imm16 executor ───────────────────────────────────────────────────────
private val RetImmExecutor = InstructionExecutor { ctx ->
    ctx.cpu.eip = ctx.memory.read32(ctx.cpu.esp); ctx.cpu.esp += 4
    ctx.cpu.esp += ctx.immediate.toInt() and 0xFFFF }

// ─── XCHG EAX, r32 executor ──────────────────────────────────────────────────
private val XchgEaxR32Executor = InstructionExecutor { ctx ->
    val reg=Reg32.of(ctx.opcode - 0x90); val tmp=ctx.cpu.eax
    ctx.cpu.eax = reg.read(ctx.cpu); reg.write(ctx.cpu, tmp) }

// ─── Misc16 executors ─────────────────────────────────────────────────────────
private val CbwCwdeExecutor = InstructionExecutor { ctx ->
    ctx.cpu.eax = ctx.cpu.al.toByte().toInt() }  // sign-extend AL to EAX
private val CwdCdqExecutor  = InstructionExecutor { ctx ->
    ctx.cpu.edx = ctx.cpu.eax shr 31 }  // sign-extend EAX to EDX:EAX
private val PushfExecutor   = InstructionExecutor { ctx ->
    ctx.cpu.esp -= 4; ctx.memory.write32(ctx.cpu.esp, ctx.cpu.eflags) }
private val PopfExecutor    = InstructionExecutor { ctx ->
    ctx.cpu.eflags = ctx.memory.read32(ctx.cpu.esp); ctx.cpu.esp += 4 }
private val SahfExecutor    = InstructionExecutor { ctx ->
    ctx.cpu.eflags = (ctx.cpu.eflags and -0x100) or (ctx.cpu.ah and 0xD5) }
private val LahfExecutor    = InstructionExecutor { ctx ->
    ctx.cpu.ah = ctx.cpu.eflags and 0xFF }

// ─── Grp2 (shift/rotate) executor ────────────────────────────────────────────
private fun execShift32(ctx: DecodeContext, count: Int) {
    val m = ctx.modRM ?: return
    val reg = (m shr 3) and 7; val dst = readRM32(ctx, m)
    val cnt = count and 0x1F; if (cnt == 0) return
    val result: Int; val cfOut: Int; val ofOut: Int
    when (reg) {
        0 -> { result = dst.rotateLeft(cnt); cfOut = result and 1
               ofOut = if (cnt == 1) cfOut xor ((result ushr 31) and 1) else 0 }
        1 -> { result = dst.rotateRight(cnt); cfOut = (result ushr 31) and 1
               ofOut = if (cnt == 1) cfOut xor ((result ushr 30) and 1) else 0 }
        2 -> { // RCL 32-bit
            var v = dst.toLong() and 0xFFFFFFFFL; var c = cfBit(ctx.cpu).toLong()
            repeat(cnt) { val nc=(v ushr 31) and 1; v=((v shl 1) or c) and 0xFFFFFFFFL; c=nc }
            result=v.toInt(); cfOut=c.toInt()
            ofOut = if (cnt == 1) cfOut xor ((result ushr 31) and 1) else 0 }
        3 -> { // RCR 32-bit
            var v = dst.toLong() and 0xFFFFFFFFL; var c = cfBit(ctx.cpu).toLong()
            repeat(cnt) { val nc=v and 1; v=(v ushr 1) or (c shl 31); c=nc }
            result=v.toInt(); cfOut=c.toInt()
            ofOut = if (cnt == 1) ((result ushr 31) and 1) xor ((result ushr 30) and 1) else 0 }
        4, 6 -> { cfOut=(dst ushr (32-cnt)) and 1; result=dst shl cnt
                  ofOut = if (cnt == 1) cfOut xor ((result ushr 31) and 1) else 0 }
        5 -> { cfOut=(dst ushr (cnt-1)) and 1; result=dst ushr cnt
               ofOut = if (cnt == 1) (dst ushr 31) and 1 else 0 }
        7 -> { cfOut=(dst shr (cnt-1)) and 1; result=dst shr cnt; ofOut=0 }
        else -> return
    }
    writeRM32(ctx, m, result)
    val zf = if (result == 0) 1 else 0; val sf = (result ushr 31) and 1
    ctx.cpu.eflags = (ctx.cpu.eflags and ARITH_CLEAR) or cfOut or (zf shl 6) or (sf shl 7) or (ofOut shl 11)
}
private fun execShift8(ctx: DecodeContext, count: Int) {
    val m = ctx.modRM ?: return
    val reg = (m shr 3) and 7; val dst = readRM8(ctx, m)
    val cnt = count and 0x1F; if (cnt == 0) return
    val result8: Int; val cfOut: Int; val ofOut: Int
    when (reg) {
        0 -> { val eff = cnt and 7; val r = if (eff == 0) dst else ((dst shl eff) or (dst ushr (8-eff))) and 0xFF
               result8=r; cfOut=r and 1; ofOut = if (cnt==1) cfOut xor ((r ushr 7) and 1) else 0 }
        1 -> { val eff = cnt and 7; val r = if (eff == 0) dst else ((dst ushr eff) or (dst shl (8-eff))) and 0xFF
               result8=r; cfOut=(r ushr 7) and 1; ofOut = if (cnt==1) cfOut xor ((r ushr 6) and 1) else 0 }
        2 -> { // RCL 8-bit
            var v=dst; var c=cfBit(ctx.cpu)
            repeat(cnt and 0x1F) { val nc=(v ushr 7) and 1; v=((v shl 1) or c) and 0xFF; c=nc }
            result8=v; cfOut=c; ofOut = if (cnt==1) cfOut xor ((v ushr 7) and 1) else 0 }
        3 -> { // RCR 8-bit
            var v=dst; var c=cfBit(ctx.cpu)
            repeat(cnt and 0x1F) { val nc=v and 1; v=((v ushr 1) or (c shl 7)) and 0xFF; c=nc }
            result8=v; cfOut=c; ofOut = if (cnt==1) ((v ushr 7) and 1) xor ((v ushr 6) and 1) else 0 }
        4, 6 -> { if (cnt >= 8) { cfOut=0; result8=0 }
                  else { cfOut=(dst ushr (8-cnt)) and 1; result8=(dst shl cnt) and 0xFF }
                  ofOut = if (cnt==1) cfOut xor ((result8 ushr 7) and 1) else 0 }
        5 -> { if (cnt >= 8) { cfOut=0; result8=0 }
               else { cfOut=(dst ushr (cnt-1)) and 1; result8=(dst ushr cnt) and 0xFF }
               ofOut = if (cnt==1) (dst ushr 7) and 1 else 0 }
        7 -> { val se = if (dst and 0x80 != 0) dst or -0x100 else dst
               val c8 = minOf(cnt, 8); cfOut=(se shr (c8-1)) and 1; result8=(se shr c8) and 0xFF; ofOut=0 }
        else -> return
    }
    writeRM8(ctx, m, result8)
    val zf = if (result8 == 0) 1 else 0; val sf = (result8 ushr 7) and 1
    ctx.cpu.eflags = (ctx.cpu.eflags and ARITH_CLEAR) or cfOut or (zf shl 6) or (sf shl 7) or (ofOut shl 11)
}
private val Grp2Executor = InstructionExecutor { ctx ->
    val count = when (ctx.opcode) {
        0xC0, 0xC1 -> ctx.immediate.toInt() and 0xFF
        0xD0, 0xD1 -> 1
        else        -> ctx.cpu.cl and 0xFF
    }
    if (ctx.opcode in setOf(0xC0, 0xD0, 0xD2)) execShift8(ctx, count) else execShift32(ctx, count)
}

// ─── JMP rel32 executor ───────────────────────────────────────────────────────
private val JmpRel32Executor = InstructionExecutor { ctx -> ctx.cpu.eip += ctx.immediate.toInt() }

// ─── IN/OUT executors ─────────────────────────────────────────────────────────
private val InOutExecutor = InstructionExecutor { ctx ->
    when (ctx.opcode) {
        0xE4 -> ctx.cpu.al  = ctx.ports.in8(ctx.immediate.toInt() and 0xFF)
        0xE5 -> ctx.cpu.eax = ctx.ports.in8(ctx.immediate.toInt() and 0xFF)
        0xE6 -> ctx.ports.out8(ctx.immediate.toInt() and 0xFF, ctx.cpu.al)
        0xE7 -> ctx.ports.out8(ctx.immediate.toInt() and 0xFF, ctx.cpu.eax)
        0xEC -> ctx.cpu.al  = ctx.ports.in8(ctx.cpu.dx)
        0xED -> ctx.cpu.eax = ctx.ports.in8(ctx.cpu.dx)
        0xEE -> ctx.ports.out8(ctx.cpu.dx, ctx.cpu.al)
        0xEF -> ctx.ports.out8(ctx.cpu.dx, ctx.cpu.eax)
    }
}

// ─── Flags manipulation executors ────────────────────────────────────────────
private val CliExecutor = InstructionExecutor { ctx -> ctx.cpu.eflags = ctx.cpu.eflags and (1 shl 9).inv() }
private val StiExecutor = InstructionExecutor { ctx -> ctx.cpu.eflags = ctx.cpu.eflags or  (1 shl 9) }
private val CldExecutor = InstructionExecutor { ctx -> ctx.cpu.eflags = ctx.cpu.eflags and (1 shl 10).inv() }
private val StdExecutor = InstructionExecutor { ctx -> ctx.cpu.eflags = ctx.cpu.eflags or  (1 shl 10) }

// ─── String ops executor ──────────────────────────────────────────────────────
private val StringOpExecutor = InstructionExecutor { ctx ->
    val op = ctx.opcode; val inc = if (dfBit(ctx.cpu) == 0) 1 else -1
    val rep = 0xF3 in ctx.prefixes; val repne = 0xF2 in ctx.prefixes
    val isCmpsScas = op in setOf(0xA6, 0xA7, 0xAE, 0xAF)
    fun doOp() { when (op) {
        0xA4 -> { ctx.memory.write8(ctx.cpu.edi, ctx.memory.read8(ctx.cpu.esi)); ctx.cpu.esi += inc; ctx.cpu.edi += inc }
        0xA5 -> { ctx.memory.write32(ctx.cpu.edi, ctx.memory.read32(ctx.cpu.esi)); ctx.cpu.esi += inc*4; ctx.cpu.edi += inc*4 }
        0xA6 -> { val a=ctx.memory.read8(ctx.cpu.esi); val b=ctx.memory.read8(ctx.cpu.edi); setArithFlags8(ctx.cpu,(a-b) and 0xFF,a,b,false); ctx.cpu.esi += inc; ctx.cpu.edi += inc }
        0xA7 -> { val a=ctx.memory.read32(ctx.cpu.esi); val b=ctx.memory.read32(ctx.cpu.edi); setArithFlags(ctx.cpu,a-b,a,b,false); ctx.cpu.esi += inc*4; ctx.cpu.edi += inc*4 }
        0xAA -> { ctx.memory.write8(ctx.cpu.edi, ctx.cpu.al); ctx.cpu.edi += inc }
        0xAB -> { ctx.memory.write32(ctx.cpu.edi, ctx.cpu.eax); ctx.cpu.edi += inc*4 }
        0xAC -> { ctx.cpu.al = ctx.memory.read8(ctx.cpu.esi); ctx.cpu.esi += inc }
        0xAD -> { ctx.cpu.eax = ctx.memory.read32(ctx.cpu.esi); ctx.cpu.esi += inc*4 }
        0xAE -> { val a=ctx.cpu.al; val b=ctx.memory.read8(ctx.cpu.edi); setArithFlags8(ctx.cpu,(a-b) and 0xFF,a,b,false); ctx.cpu.edi += inc }
        0xAF -> { val a=ctx.cpu.eax; val b=ctx.memory.read32(ctx.cpu.edi); setArithFlags(ctx.cpu,a-b,a,b,false); ctx.cpu.edi += inc*4 }
    } }
    if (rep || repne) {
        while (ctx.cpu.ecx != 0) {
            doOp(); ctx.cpu.ecx -= 1
            if (isCmpsScas) {
                val zf = (ctx.cpu.eflags shr 6) and 1
                if (rep   && zf == 0) break
                if (repne && zf == 1) break
            }
        }
    } else doOp()
}

// ─── Grp3 executors ───────────────────────────────────────────────────────────
private val Grp3Rm8Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val reg=(m shr 3) and 7; val dst=readRM8(ctx,m)
    when (reg) {
        0 -> setLogicFlags8(ctx.cpu, dst and (ctx.immediate.toInt() and 0xFF))
        2 -> writeRM8(ctx, m, dst.inv() and 0xFF)
        3 -> { val r=(-dst) and 0xFF; setArithFlags8(ctx.cpu,r,0,dst,false); writeRM8(ctx,m,r) }
        4 -> { val prod=(ctx.cpu.al and 0xFF)*(dst and 0xFF); ctx.cpu.ax=prod and 0xFFFF
               val cf=if((prod shr 8) and 0xFF != 0) 1 else 0; ctx.cpu.eflags=(ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
        5 -> { val prod=ctx.cpu.al.toByte().toInt() * dst.toByte().toInt(); ctx.cpu.ax=prod and 0xFFFF
               val hi=(prod shr 8) and 0xFF; val hiOk=if(ctx.cpu.al.toByte()<0) hi==0xFF else hi==0
               val cf=if(!hiOk) 1 else 0; ctx.cpu.eflags=(ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
        6 -> { if(dst==0) throw ArithmeticException("DIV by zero"); val ax=ctx.cpu.ax and 0xFFFF
               ctx.cpu.al=(ax/dst) and 0xFF; ctx.cpu.ah=(ax%dst) and 0xFF }
        7 -> { if(dst==0) throw ArithmeticException("IDIV by zero")
               val ax=ctx.cpu.ax.toShort().toInt(); val d=dst.toByte().toInt()
               ctx.cpu.al=(ax/d) and 0xFF; ctx.cpu.ah=(ax%d) and 0xFF }
    }
}
private val Grp3Rm32Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val reg=(m shr 3) and 7; val dst=readRM32(ctx,m)
    when (reg) {
        0 -> setLogicFlags32(ctx.cpu, dst and ctx.immediate.toInt())
        2 -> writeRM32(ctx, m, dst.inv())
        3 -> { val r=-dst; setArithFlags(ctx.cpu,r,0,dst,false); writeRM32(ctx,m,r) }
        4 -> { val prod=(ctx.cpu.eax.toLong() and 0xFFFFFFFFL)*(dst.toLong() and 0xFFFFFFFFL)
               ctx.cpu.eax=prod.toInt(); ctx.cpu.edx=(prod ushr 32).toInt()
               val cf=if(ctx.cpu.edx!=0) 1 else 0; ctx.cpu.eflags=(ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
        5 -> { val prod=ctx.cpu.eax.toLong()*dst.toLong()
               ctx.cpu.eax=prod.toInt(); ctx.cpu.edx=(prod ushr 32).toInt()
               val hi=ctx.cpu.edx; val fits=if(ctx.cpu.eax<0) hi==-1 else hi==0
               val cf=if(!fits) 1 else 0; ctx.cpu.eflags=(ctx.cpu.eflags and ARITH_CLEAR) or cf or (cf shl 11) }
        6 -> { if(dst==0) throw ArithmeticException("DIV by zero")
               val dvd=((ctx.cpu.edx.toLong() and 0xFFFFFFFFL) shl 32) or (ctx.cpu.eax.toLong() and 0xFFFFFFFFL)
               ctx.cpu.eax=(dvd/(dst.toLong() and 0xFFFFFFFFL)).toInt()
               ctx.cpu.edx=(dvd%(dst.toLong() and 0xFFFFFFFFL)).toInt() }
        7 -> { if(dst==0) throw ArithmeticException("IDIV by zero")
               val dvd=(ctx.cpu.edx.toLong() shl 32) or (ctx.cpu.eax.toLong() and 0xFFFFFFFFL)
               ctx.cpu.eax=(dvd/dst).toInt(); ctx.cpu.edx=(dvd%dst).toInt() }
    }
}

// ─── Grp4 (INC/DEC r/m8) executor ────────────────────────────────────────────
private val Grp4Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val reg=(m shr 3) and 7
    when (reg) {
        0 -> { val old=readRM8(ctx,m); val r=(old+1) and 0xFF; setIncDecFlags8(ctx.cpu,r,old,true);  writeRM8(ctx,m,r) }
        1 -> { val old=readRM8(ctx,m); val r=(old-1) and 0xFF; setIncDecFlags8(ctx.cpu,r,old,false); writeRM8(ctx,m,r) }
        else -> throw UnsupportedOperationException(String.format("Grp4(FEh) /reg=%d not implemented", reg))
    }
}

// ─── Grp5 (INC/DEC/CALL/JMP/PUSH r/m32) executor ─────────────────────────────
private val Grp5Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; val reg=(m shr 3) and 7
    when (reg) {
        0 -> { val old=readRM32(ctx,m); val r=old+1; setIncDecFlags(ctx.cpu,r,old,true);  writeRM32(ctx,m,r) }
        1 -> { val old=readRM32(ctx,m); val r=old-1; setIncDecFlags(ctx.cpu,r,old,false); writeRM32(ctx,m,r) }
        2 -> { val tgt=readRM32(ctx,m); ctx.cpu.esp-=4; ctx.memory.write32(ctx.cpu.esp,ctx.cpu.eip); ctx.cpu.eip=tgt }
        4 -> { ctx.cpu.eip=readRM32(ctx,m) }
        6 -> { val v=readRM32(ctx,m); ctx.cpu.esp-=4; ctx.memory.write32(ctx.cpu.esp,v) }
        else -> throw UnsupportedOperationException(String.format("Grp5(FFh) /reg=%d not implemented", reg))
    }
}

// ─── 0F extended executors ────────────────────────────────────────────────────
private val JccRel32Executor = InstructionExecutor { ctx ->
    val rel=ctx.immediate.toInt(); val f=ctx.cpu.eflags
    val cf=f and 1; val zf=(f shr 6) and 1; val sf=(f shr 7) and 1
    val pf=(f shr 2) and 1; val of=(f shr 11) and 1
    val taken = when (ctx.subOpcode) {
        0x80->of==1; 0x81->of==0; 0x82->cf==1; 0x83->cf==0
        0x84->zf==1; 0x85->zf==0; 0x86->cf==1||zf==1; 0x87->cf==0&&zf==0
        0x88->sf==1; 0x89->sf==0; 0x8A->pf==1; 0x8B->pf==0
        0x8C->sf!=of; 0x8D->sf==of; 0x8E->zf==1||sf!=of; else->zf==0&&sf==of
    }
    if (taken) ctx.cpu.eip += rel
}
private val MovzxR32Rm8Executor  = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, readRM8(ctx,m) and 0xFF) }
private val MovzxR32Rm16Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, readRM16(ctx,m) and 0xFFFF) }
private val MovsxR32Rm8Executor  = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, readRM8(ctx,m).toByte().toInt()) }
private val MovsxR32Rm16Executor = InstructionExecutor { ctx ->
    val m=ctx.modRM!!; Reg32.of((m shr 3) and 7).write(ctx.cpu, readRM16(ctx,m).toShort().toInt()) }

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
