package io.github.jwyoon1220.pvm.hardware.decode

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory
import io.github.jwyoon1220.pvm.hardware.interrupt.InterruptDispatcher

class DecoderPipeline(
    private val opcodeHandler: OpcodeHandler = defaultOpcodeChain(),
    internal val interruptDispatcher: InterruptDispatcher? = null
) {
    fun run(cpu: CPU, memory: Memory, collectTrace: Boolean): List<String> {
        val context = DecodeContext(cpu, memory, collectTrace, opcodeHandler, interruptDispatcher)

        var state: DecodeState? = PrefixState
        while (state != null) {
            state = state.advance(context)
        }
        return context.trace
    }
}

private fun defaultOpcodeChain(): OpcodeHandler {
    val unsupported = UnsupportedOpcodeHandler()
    val int_ = IntHandler(unsupported)
    val grp1Imm32 = Grp1Rm32Imm32Handler(int_)
    val grp1Imm8 = Grp1Rm32Imm8Handler(grp1Imm32)
    val hlt = HltHandler(grp1Imm8)
    val ret = RetHandler(hlt)
    val callRel32 = CallRel32Handler(ret)
    val jmpRel8 = JmpRel8Handler(callRel32)
    val jcc = JccRel8Handler(jmpRel8)
    val cmpRm32R32 = CmpRm32R32Handler(jcc)
    val cmpR32Rm32 = CmpR32Rm32Handler(cmpRm32R32)
    val subRm32R32 = SubRm32R32Handler(cmpR32Rm32)
    val subR32Rm32 = SubR32Rm32Handler(subRm32R32)
    val addRm32R32 = AddRm32R32Handler(subR32Rm32)
    val addR32Rm32 = AddR32Rm32Handler(addRm32R32)
    val decR32 = DecR32Handler(addR32Rm32)
    val incR32 = IncR32Handler(decR32)
    val movRegImm = MovRegImmHandler(incR32)
    val movRm32R32 = MovRm32R32Handler(movRegImm)
    val movR32Rm32 = MovR32Rm32Handler(movRm32R32)
    val pushImm8Se = PushImm8SeHandler(movR32Rm32)
    val pushImm32 = PushImm32Handler(pushImm8Se)
    val popR32 = PopR32Handler(pushImm32)
    val pushR32 = PushR32Handler(popR32)
    val nop = NopHandler(pushR32)
    return nop
}

// ─── Decode Context ──────────────────────────────────────────────────────────

class DecodeContext(
    val cpu: CPU,
    val memory: Memory,
    private val collectTrace: Boolean,
    val opcodeHandler: OpcodeHandler,
    val interruptDispatcher: InterruptDispatcher?
) {
    val prefixes: MutableList<Int> = mutableListOf()
    var opcode: Int = 0
    var modRM: Int? = null
    var sib: Int? = null
    var displacement: Int = 0
    var immediate: Long = 0
    lateinit var instruction: InstructionDescriptor

    val trace: MutableList<String> = mutableListOf()

    fun log(message: String) {
        if (collectTrace) trace.add(message)
    }

    fun fetch8(): Int = memory.read8(cpu.eip).also { cpu.eip += 1 }
    fun fetch16(): Int = memory.read16(cpu.eip).also { cpu.eip += 2 }
    fun fetch32(): Int = memory.read32(cpu.eip).also { cpu.eip += 4 }
}

// ─── Decode State Machine ────────────────────────────────────────────────────

private interface DecodeState {
    fun advance(context: DecodeContext): DecodeState?
}

private object PrefixState : DecodeState {
    private val knownPrefixes = setOf(0x66, 0x67, 0x2E, 0x3E, 0x26, 0x64, 0x65, 0xF0, 0xF2, 0xF3)

    override fun advance(context: DecodeContext): DecodeState {
        while (true) {
            val candidate = context.memory.read8(context.cpu.eip)
            if (candidate !in knownPrefixes) break
            context.prefixes.add(candidate)
            context.cpu.eip += 1
        }
        context.log("PrefixState")
        return OpcodeState
    }
}

private object OpcodeState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        context.opcode = context.fetch8()
        context.instruction = context.opcodeHandler.resolve(context.opcode, context)
        context.log(
            String.format(
                "OpcodeState(opcode=%02X, mnemonic=%s)",
                context.opcode,
                context.instruction.name
            )
        )

        if (context.instruction.requiresModRM) return ModRmState
        if (context.instruction.immediateBytes > 0) return ImmediateState
        return ExecuteState
    }
}

private object ModRmState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        context.modRM = context.fetch8()
        context.log(String.format("ModRmState(modRM=%02X)", context.modRM))

        if (requiresSib(context.modRM!!)) return SibState
        return DisplacementState
    }

    private fun requiresSib(modRM: Int): Boolean {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07
        return mod != 3 && rm == 4
    }
}

private object SibState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        context.sib = context.fetch8()
        context.log(String.format("SibState(sib=%02X)", context.sib))
        return DisplacementState
    }
}

private object DisplacementState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        val modRM = context.modRM ?: return afterDisplacement(context)
        val size = displacementSize(modRM, context.sib)
        context.displacement = readDisplacement(context, size)
        context.log("DisplacementState(displacement=${context.displacement})")
        return afterDisplacement(context)
    }

    private fun afterDisplacement(context: DecodeContext): DecodeState {
        if (context.instruction.immediateBytes > 0) return ImmediateState
        return ExecuteState
    }

    private fun displacementSize(modRM: Int, sib: Int?): Int {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        if (mod == 0) {
            if (rm == 5) return 4
            if (rm == 4 && sib != null && (sib and 0x07) == 5) return 4
            return 0
        }
        if (mod == 1) return 1
        if (mod == 2) return 4
        return 0
    }

    private fun readDisplacement(context: DecodeContext, size: Int): Int {
        if (size == 1) return context.fetch8().toByte().toInt()
        if (size == 4) return context.fetch32()
        return 0
    }
}

private object ImmediateState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        val size = context.instruction.immediateBytes
        context.immediate = readImmediate(context, size)
        context.log("ImmediateState(immediate=${context.immediate})")
        return ExecuteState
    }

    private fun readImmediate(context: DecodeContext, size: Int): Long {
        if (size == 1) return context.fetch8().toLong() and 0xFFL
        if (size == 2) return context.fetch16().toLong() and 0xFFFFL
        if (size == 4) return context.fetch32().toLong() and 0xFFFF_FFFFL
        return 0
    }
}

private object ExecuteState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState? {
        context.log("ExecuteState(${context.instruction.name})")
        context.instruction.executor.execute(context)
        return null
    }
}

// ─── Opcode Handler Chain ─────────────────────────────────────────────────────

interface OpcodeHandler {
    fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor
}

private abstract class OpcodeHandlerLink(
    private val next: OpcodeHandler
) : OpcodeHandler {
    final override fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor {
        return tryResolve(opcode, context) ?: next.resolve(opcode, context)
    }

    protected abstract fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor?
}

// ─── Handlers ─────────────────────────────────────────────────────────────────

private class NopHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x90) return InstructionDescriptor("NOP", false, 0, NopExecutor)
        return null
    }
}

private class PushR32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode in 0x50..0x57) return InstructionDescriptor("PUSH r32", false, 0, PushR32Executor)
        return null
    }
}

private class PopR32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode in 0x58..0x5F) return InstructionDescriptor("POP r32", false, 0, PopR32Executor)
        return null
    }
}

private class PushImm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x68) return InstructionDescriptor("PUSH imm32", false, 4, PushImm32Executor)
        return null
    }
}

private class PushImm8SeHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x6A) return InstructionDescriptor("PUSH imm8", false, 1, PushImm8SeExecutor)
        return null
    }
}

private class AddRm32R32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x01) return InstructionDescriptor("ADD r/m32, r32", true, 0, AddRm32R32Executor)
        return null
    }
}

private class AddR32Rm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x03) return InstructionDescriptor("ADD r32, r/m32", true, 0, AddR32Rm32Executor)
        return null
    }
}

private class SubRm32R32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x29) return InstructionDescriptor("SUB r/m32, r32", true, 0, SubRm32R32Executor)
        return null
    }
}

private class SubR32Rm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x2B) return InstructionDescriptor("SUB r32, r/m32", true, 0, SubR32Rm32Executor)
        return null
    }
}

private class CmpRm32R32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x39) return InstructionDescriptor("CMP r/m32, r32", true, 0, CmpRm32R32Executor)
        return null
    }
}

private class CmpR32Rm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x3B) return InstructionDescriptor("CMP r32, r/m32", true, 0, CmpR32Rm32Executor)
        return null
    }
}

private class IncR32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode in 0x40..0x47) return InstructionDescriptor("INC r32", false, 0, IncR32Executor)
        return null
    }
}

private class DecR32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode in 0x48..0x4F) return InstructionDescriptor("DEC r32", false, 0, DecR32Executor)
        return null
    }
}

private class MovRm32R32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x89) return InstructionDescriptor("MOV r/m32, r32", true, 0, MovRm32R32Executor)
        return null
    }
}

private class MovR32Rm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x8B) return InstructionDescriptor("MOV r32, r/m32", true, 0, MovR32Rm32Executor)
        return null
    }
}

/**
 * MOV reg, imm — handles 8-bit (0xB0–0xB7), 32-bit (0xB8–0xBF), and 16-bit with 0x66 prefix.
 */
private class MovRegImmHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode in 0xB0..0xB7) {
            return InstructionDescriptor("MOV reg8, imm8", false, 1, MovReg8Imm8Executor)
        }
        if (opcode in 0xB8..0xBF) {
            val is16Bit = 0x66 in context.prefixes
            return if (is16Bit) {
                InstructionDescriptor("MOV reg16, imm16", false, 2, MovReg16Imm16Executor)
            } else {
                InstructionDescriptor("MOV reg32, imm32", false, 4, MovReg32Imm32Executor)
            }
        }
        return null
    }
}

private class JccRel8Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode !in 0x70..0x7F) return null
        val mnemonic = when (opcode) {
            0x70 -> "JO rel8";  0x71 -> "JNO rel8"
            0x72 -> "JB rel8";  0x73 -> "JAE rel8"
            0x74 -> "JE rel8";  0x75 -> "JNE rel8"
            0x76 -> "JBE rel8"; 0x77 -> "JA rel8"
            0x78 -> "JS rel8";  0x79 -> "JNS rel8"
            0x7A -> "JP rel8";  0x7B -> "JNP rel8"
            0x7C -> "JL rel8";  0x7D -> "JGE rel8"
            0x7E -> "JLE rel8"; else -> "JG rel8"
        }
        return InstructionDescriptor(mnemonic, false, 1, JccRel8Executor)
    }
}

private class JmpRel8Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0xEB) return InstructionDescriptor("JMP rel8", false, 1, JmpRel8Executor)
        return null
    }
}

private class CallRel32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0xE8) return InstructionDescriptor("CALL rel32", false, 4, CallRel32Executor)
        return null
    }
}

private class RetHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0xC3) return InstructionDescriptor("RET", false, 0, RetExecutor)
        return null
    }
}

private class HltHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0xF4) return InstructionDescriptor("HLT", false, 0, HltExecutor)
        return null
    }
}

private class Grp1Rm32Imm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x81) return InstructionDescriptor("Grp1 r/m32, imm32", true, 4, Grp1Rm32Imm32Executor)
        return null
    }
}

private class Grp1Rm32Imm8Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0x83) return InstructionDescriptor("Grp1 r/m32, imm8", true, 1, Grp1Rm32Imm8Executor)
        return null
    }
}

private class IntHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int, context: DecodeContext): InstructionDescriptor? {
        if (opcode == 0xCD) return InstructionDescriptor("INT imm8", false, 1, IntExecutor)
        return null
    }
}

private class UnsupportedOpcodeHandler : OpcodeHandler {
    override fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor {
        throw UnsupportedOperationException(
            String.format("Unsupported opcode: %02Xh at EIP=%08Xh", opcode, context.cpu.eip - 1)
        )
    }
}

// ─── Instruction Descriptor ──────────────────────────────────────────────────

data class InstructionDescriptor(
    val name: String,
    val requiresModRM: Boolean,
    val immediateBytes: Int,
    val executor: InstructionExecutor
)

interface InstructionExecutor {
    fun execute(context: DecodeContext)
}

// ─── r/m32 Helpers ───────────────────────────────────────────────────────────

private fun effectiveAddress(context: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 0x03
    val rm = modRM and 0x07

    if (rm == 4) {
        val sib = context.sib ?: error("SIB byte required but not present")
        val scale = 1 shl ((sib shr 6) and 0x03)
        val indexField = (sib shr 3) and 0x07
        val baseField = sib and 0x07
        val index = if (indexField == 4) 0 else RegisterBank.byIndex(indexField).read(context.cpu)
        val base = if (baseField == 5 && mod == 0) 0 else RegisterBank.byIndex(baseField).read(context.cpu)
        return base + (index * scale) + context.displacement
    }

    val base = if (rm == 5 && mod == 0) 0 else RegisterBank.byIndex(rm).read(context.cpu)
    return base + context.displacement
}

private fun readRm32(context: DecodeContext, modRM: Int): Int {
    val mod = (modRM shr 6) and 0x03
    val rm = modRM and 0x07
    if (mod == 3) return RegisterBank.byIndex(rm).read(context.cpu)
    return context.memory.read32(effectiveAddress(context, modRM))
}

private fun writeRm32(context: DecodeContext, modRM: Int, value: Int) {
    val mod = (modRM shr 6) and 0x03
    val rm = modRM and 0x07
    if (mod == 3) {
        RegisterBank.byIndex(rm).write(context.cpu, value)
    } else {
        context.memory.write32(effectiveAddress(context, modRM), value)
    }
}

// ─── EFLAGS Helpers ───────────────────────────────────────────────────────────

// Bits: CF=0, ZF=6, SF=7, OF=11
private const val FLAGS_MASK_ARITH = -0x8C2   // clears CF(0), ZF(6), SF(7), OF(11)
private const val FLAGS_MASK_INCDEC = -0x8C1  // clears ZF(6), SF(7), OF(11); leaves CF unchanged

/** Updates CF, ZF, SF, OF for an ADD or SUB result. */
private fun updateArithFlags(cpu: CPU, result: Int, op1: Int, op2: Int, isAdd: Boolean) {
    val cf: Int
    val of: Int
    if (isAdd) {
        val sum = (op1.toLong() and 0xFFFFFFFFL) + (op2.toLong() and 0xFFFFFFFFL)
        cf = (sum ushr 32).toInt() and 1
        of = (((op1 xor result) and (op2 xor result)) ushr 31) and 1
    } else {
        cf = if (Integer.compareUnsigned(op1, op2) < 0) 1 else 0
        of = (((op1 xor op2) and (op1 xor result)) ushr 31) and 1
    }
    val zf = if (result == 0) 1 else 0
    val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and FLAGS_MASK_ARITH) or cf or (zf shl 6) or (sf shl 7) or (of shl 11)
}

/** Updates ZF, SF, OF for INC or DEC. CF is intentionally left unchanged. */
private fun updateIncDecFlags(cpu: CPU, result: Int, op1: Int, isInc: Boolean) {
    val of: Int = if (isInc) {
        (((op1 xor result) and (1 xor result)) ushr 31) and 1
    } else {
        (((op1 xor 1) and (op1 xor result)) ushr 31) and 1
    }
    val zf = if (result == 0) 1 else 0
    val sf = (result ushr 31) and 1
    cpu.eflags = (cpu.eflags and FLAGS_MASK_INCDEC) or (zf shl 6) or (sf shl 7) or (of shl 11)
}

// ─── Executors ────────────────────────────────────────────────────────────────

private object NopExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        // intentionally empty
    }
}

private object PushR32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val value = RegisterBank.byIndex(context.opcode - 0x50).read(context.cpu)
        context.cpu.esp -= 4
        context.memory.write32(context.cpu.esp, value)
    }
}

private object PopR32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val value = context.memory.read32(context.cpu.esp)
        context.cpu.esp += 4
        RegisterBank.byIndex(context.opcode - 0x58).write(context.cpu, value)
    }
}

private object PushImm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        context.cpu.esp -= 4
        context.memory.write32(context.cpu.esp, context.immediate.toInt())
    }
}

private object PushImm8SeExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        context.cpu.esp -= 4
        context.memory.write32(context.cpu.esp, context.immediate.toByte().toInt())
    }
}

private object AddRm32R32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for ADD r/m32, r32")
        val src = RegisterBank.byIndex((modRM shr 3) and 0x07).read(context.cpu)
        val dst = readRm32(context, modRM)
        val result = dst + src
        updateArithFlags(context.cpu, result, dst, src, isAdd = true)
        writeRm32(context, modRM, result)
    }
}

private object AddR32Rm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for ADD r32, r/m32")
        val regField = (modRM shr 3) and 0x07
        val src = readRm32(context, modRM)
        val dst = RegisterBank.byIndex(regField).read(context.cpu)
        val result = dst + src
        updateArithFlags(context.cpu, result, dst, src, isAdd = true)
        RegisterBank.byIndex(regField).write(context.cpu, result)
    }
}

private object SubRm32R32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for SUB r/m32, r32")
        val src = RegisterBank.byIndex((modRM shr 3) and 0x07).read(context.cpu)
        val dst = readRm32(context, modRM)
        val result = dst - src
        updateArithFlags(context.cpu, result, dst, src, isAdd = false)
        writeRm32(context, modRM, result)
    }
}

private object SubR32Rm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for SUB r32, r/m32")
        val regField = (modRM shr 3) and 0x07
        val src = readRm32(context, modRM)
        val dst = RegisterBank.byIndex(regField).read(context.cpu)
        val result = dst - src
        updateArithFlags(context.cpu, result, dst, src, isAdd = false)
        RegisterBank.byIndex(regField).write(context.cpu, result)
    }
}

private object CmpRm32R32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for CMP r/m32, r32")
        val src = RegisterBank.byIndex((modRM shr 3) and 0x07).read(context.cpu)
        val dst = readRm32(context, modRM)
        updateArithFlags(context.cpu, dst - src, dst, src, isAdd = false)
    }
}

private object CmpR32Rm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for CMP r32, r/m32")
        val regField = (modRM shr 3) and 0x07
        val src = readRm32(context, modRM)
        val dst = RegisterBank.byIndex(regField).read(context.cpu)
        updateArithFlags(context.cpu, dst - src, dst, src, isAdd = false)
    }
}

private object IncR32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val reg = RegisterBank.byIndex(context.opcode - 0x40)
        val old = reg.read(context.cpu)
        val result = old + 1
        updateIncDecFlags(context.cpu, result, old, isInc = true)
        reg.write(context.cpu, result)
    }
}

private object DecR32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val reg = RegisterBank.byIndex(context.opcode - 0x48)
        val old = reg.read(context.cpu)
        val result = old - 1
        updateIncDecFlags(context.cpu, result, old, isInc = false)
        reg.write(context.cpu, result)
    }
}

private object MovRm32R32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for MOV r/m32, r32")
        val src = RegisterBank.byIndex((modRM shr 3) and 0x07).read(context.cpu)
        writeRm32(context, modRM, src)
    }
}

private object MovR32Rm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for MOV r32, r/m32")
        val targetReg = (modRM shr 3) and 0x07
        RegisterBank.byIndex(targetReg).write(context.cpu, readRm32(context, modRM))
    }
}

private object MovReg32Imm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        RegisterBank.byIndex(context.opcode - 0xB8).write(context.cpu, context.immediate.toInt())
    }
}

private object MovReg16Imm16Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val reg = RegisterBank.byIndex(context.opcode - 0xB8)
        val current = reg.read(context.cpu)
        reg.write(context.cpu, (current and -0x10000) or (context.immediate.toInt() and 0xFFFF))
    }
}

private object MovReg8Imm8Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        Register8Bank.byIndex(context.opcode - 0xB0).write(context.cpu, context.immediate.toInt() and 0xFF)
    }
}

private object JccRel8Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val rel = context.immediate.toByte().toInt()
        val f = context.cpu.eflags
        val cf = f and 1
        val zf = (f shr 6) and 1
        val sf = (f shr 7) and 1
        val pf = (f shr 2) and 1
        val of = (f shr 11) and 1
        val taken = when (context.opcode) {
            0x70 -> of == 1
            0x71 -> of == 0
            0x72 -> cf == 1
            0x73 -> cf == 0
            0x74 -> zf == 1
            0x75 -> zf == 0
            0x76 -> cf == 1 || zf == 1
            0x77 -> cf == 0 && zf == 0
            0x78 -> sf == 1
            0x79 -> sf == 0
            0x7A -> pf == 1
            0x7B -> pf == 0
            0x7C -> sf != of
            0x7D -> sf == of
            0x7E -> zf == 1 || sf != of
            else -> zf == 0 && sf == of   // 0x7F JG
        }
        if (taken) context.cpu.eip += rel
    }
}

private object JmpRel8Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        context.cpu.eip += context.immediate.toByte().toInt()
    }
}

private object CallRel32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        context.cpu.esp -= 4
        context.memory.write32(context.cpu.esp, context.cpu.eip)
        context.cpu.eip += context.immediate.toInt()
    }
}

private object RetExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val returnAddress = context.memory.read32(context.cpu.esp)
        context.cpu.esp += 4
        context.cpu.eip = returnAddress
    }
}

private object HltExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        context.cpu.halted = true
    }
}

private object IntExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val intNum = context.immediate.toInt() and 0xFF
        context.interruptDispatcher?.dispatch(intNum, context.cpu, context.memory)
            ?: throw UnsupportedOperationException(
                String.format("INT %02Xh: no InterruptDispatcher configured", intNum)
            )
    }
}

private object Grp1Rm32Imm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for Grp1 r/m32, imm32")
        val regField = (modRM shr 3) and 0x07
        val imm = context.immediate.toInt()
        val src = readRm32(context, modRM)
        when (regField) {
            0 -> { val r = src + imm; updateArithFlags(context.cpu, r, src, imm, isAdd = true);  writeRm32(context, modRM, r) }
            5 -> { val r = src - imm; updateArithFlags(context.cpu, r, src, imm, isAdd = false); writeRm32(context, modRM, r) }
            7 -> { updateArithFlags(context.cpu, src - imm, src, imm, isAdd = false) }
            else -> throw UnsupportedOperationException(
                String.format("Grp1(81h) /reg=%d not implemented", regField)
            )
        }
    }
}

private object Grp1Rm32Imm8Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M required for Grp1 r/m32, imm8")
        val regField = (modRM shr 3) and 0x07
        val imm = context.immediate.toByte().toInt()   // sign-extend to 32 bits
        val src = readRm32(context, modRM)
        when (regField) {
            0 -> { val r = src + imm; updateArithFlags(context.cpu, r, src, imm, isAdd = true);  writeRm32(context, modRM, r) }
            5 -> { val r = src - imm; updateArithFlags(context.cpu, r, src, imm, isAdd = false); writeRm32(context, modRM, r) }
            7 -> { updateArithFlags(context.cpu, src - imm, src, imm, isAdd = false) }
            else -> throw UnsupportedOperationException(
                String.format("Grp1(83h) /reg=%d not implemented", regField)
            )
        }
    }
}

// ─── Register Banks ──────────────────────────────────────────────────────────

private interface Register32 {
    fun read(cpu: CPU): Int
    fun write(cpu: CPU, value: Int)
}

private object RegisterBank {
    private val table: List<Register32> = listOf(
        object : Register32 { override fun read(cpu: CPU) = cpu.eax; override fun write(cpu: CPU, v: Int) { cpu.eax = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.ecx; override fun write(cpu: CPU, v: Int) { cpu.ecx = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.edx; override fun write(cpu: CPU, v: Int) { cpu.edx = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.ebx; override fun write(cpu: CPU, v: Int) { cpu.ebx = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.esp; override fun write(cpu: CPU, v: Int) { cpu.esp = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.ebp; override fun write(cpu: CPU, v: Int) { cpu.ebp = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.esi; override fun write(cpu: CPU, v: Int) { cpu.esi = v } },
        object : Register32 { override fun read(cpu: CPU) = cpu.edi; override fun write(cpu: CPU, v: Int) { cpu.edi = v } }
    )

    fun byIndex(index: Int): Register32 = table[index and 0x07]
}

// 8-bit register encoding: AL=0, CL=1, DL=2, BL=3, AH=4, CH=5, DH=6, BH=7
private interface Register8 {
    fun read(cpu: CPU): Int
    fun write(cpu: CPU, value: Int)
}

private object Register8Bank {
    private val table: List<Register8> = listOf(
        object : Register8 { override fun read(cpu: CPU) = cpu.al; override fun write(cpu: CPU, v: Int) { cpu.al = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.cl; override fun write(cpu: CPU, v: Int) { cpu.cl = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.dl; override fun write(cpu: CPU, v: Int) { cpu.dl = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.bl; override fun write(cpu: CPU, v: Int) { cpu.bl = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.ah; override fun write(cpu: CPU, v: Int) { cpu.ah = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.ch; override fun write(cpu: CPU, v: Int) { cpu.ch = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.dh; override fun write(cpu: CPU, v: Int) { cpu.dh = v } },
        object : Register8 { override fun read(cpu: CPU) = cpu.bh; override fun write(cpu: CPU, v: Int) { cpu.bh = v } }
    )

    fun byIndex(index: Int): Register8 = table[index and 0x07]
}
