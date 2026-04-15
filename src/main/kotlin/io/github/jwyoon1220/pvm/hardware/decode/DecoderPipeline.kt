package io.github.jwyoon1220.pvm.hardware.decode

import io.github.jwyoon1220.pvm.hardware.CPU
import io.github.jwyoon1220.pvm.hardware.Memory

class DecoderPipeline(
    private val opcodeHandler: OpcodeHandler = defaultOpcodeChain()
) {
    fun run(cpu: CPU, memory: Memory, collectTrace: Boolean): List<String> {
        val context = DecodeContext(cpu, memory, collectTrace, opcodeHandler)

        var state: DecodeState? = PrefixState
        while (state != null) {
            state = state.advance(context)
        }
        return context.trace
    }

}

private fun defaultOpcodeChain(): OpcodeHandler {
    val unsupported = UnsupportedOpcodeHandler()
    val hlt = HltHandler(unsupported)
    val jmpRel8 = JmpRel8Handler(hlt)
    val movRegImm32 = MovRegImm32Handler(jmpRel8)
    val movR32Rm32 = MovR32Rm32Handler(movRegImm32)
    return NopHandler(movR32Rm32)
}

class DecodeContext(
    val cpu: CPU,
    val memory: Memory,
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

    fun log(message: String) {
        if (collectTrace) {
            trace.add(message)
        }
    }

    fun fetch8(): Int = memory.read8(cpu.eip).also { cpu.eip += 1 }

    fun fetch32(): Int = memory.read32(cpu.eip).also { cpu.eip += 4 }
}

private interface DecodeState {
    fun advance(context: DecodeContext): DecodeState?
}

private object PrefixState : DecodeState {
    private val knownPrefixes = setOf(0x66, 0x67, 0x2E, 0x3E, 0x26, 0x64, 0x65, 0xF0, 0xF2, 0xF3)

    override fun advance(context: DecodeContext): DecodeState {
        while (true) {
            val candidate = context.memory.read8(context.cpu.eip)
            if (!knownPrefixes.contains(candidate)) {
                break
            }
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

        if (context.instruction.requiresModRM) {
            return ModRmState
        }
        if (context.instruction.immediateBytes > 0) {
            return ImmediateState
        }
        return ExecuteState
    }
}

private object ModRmState : DecodeState {
    override fun advance(context: DecodeContext): DecodeState {
        context.modRM = context.fetch8()
        context.log(String.format("ModRmState(modRM=%02X)", context.modRM))

        if (requiresSib(context.modRM!!)) {
            return SibState
        }
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
        if (context.instruction.immediateBytes > 0) {
            return ImmediateState
        }
        return ExecuteState
    }

    private fun displacementSize(modRM: Int, sib: Int?): Int {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        if (mod == 0) {
            if (rm == 5) {
                return 4
            }
            if (rm == 4 && sib != null && (sib and 0x07) == 5) {
                return 4
            }
            return 0
        }

        if (mod == 1) {
            return 1
        }

        if (mod == 2) {
            return 4
        }

        return 0
    }

    private fun readDisplacement(context: DecodeContext, size: Int): Int {
        if (size == 1) {
            return context.fetch8().toByte().toInt()
        }
        if (size == 4) {
            return context.fetch32()
        }
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
        if (size == 1) {
            return context.fetch8().toLong() and 0xFFL
        }
        if (size == 4) {
            return context.fetch32().toLong() and 0xFFFF_FFFFL
        }
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

interface OpcodeHandler {
    fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor
}

private abstract class OpcodeHandlerLink(
    private val next: OpcodeHandler
) : OpcodeHandler {
    final override fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor {
        val descriptor = tryResolve(opcode)
        if (descriptor != null) {
            return descriptor
        }
        return next.resolve(opcode, context)
    }

    protected abstract fun tryResolve(opcode: Int): InstructionDescriptor?
}

private class NopHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int): InstructionDescriptor? {
        if (opcode == 0x90) {
            return InstructionDescriptor("NOP", false, 0, NopExecutor)
        }
        return null
    }
}

private class MovR32Rm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int): InstructionDescriptor? {
        if (opcode == 0x8B) {
            return InstructionDescriptor("MOV r32, r/m32", true, 0, MovR32Rm32Executor)
        }
        return null
    }
}

private class MovRegImm32Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int): InstructionDescriptor? {
        if (opcode in 0xB8..0xBF) {
            return InstructionDescriptor("MOV reg32, imm32", false, 4, MovRegImm32Executor)
        }
        return null
    }
}

private class JmpRel8Handler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int): InstructionDescriptor? {
        if (opcode == 0xEB) {
            return InstructionDescriptor("JMP rel8", false, 1, JmpRel8Executor)
        }
        return null
    }
}

private class HltHandler(next: OpcodeHandler) : OpcodeHandlerLink(next) {
    override fun tryResolve(opcode: Int): InstructionDescriptor? {
        if (opcode == 0xF4) {
            return InstructionDescriptor("HLT", false, 0, HltExecutor)
        }
        return null
    }
}

private class UnsupportedOpcodeHandler : OpcodeHandler {
    override fun resolve(opcode: Int, context: DecodeContext): InstructionDescriptor {
        throw UnsupportedOperationException(
            String.format("Not implemented opcode: %02X at EIP: %08X", opcode, context.cpu.eip - 1)
        )
    }
}

data class InstructionDescriptor(
    val name: String,
    val requiresModRM: Boolean,
    val immediateBytes: Int,
    val executor: InstructionExecutor
)

interface InstructionExecutor {
    fun execute(context: DecodeContext)
}

private object NopExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        // Intentionally empty.
    }
}

private object MovRegImm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val regIndex = context.opcode - 0xB8
        RegisterBank.byIndex(regIndex).write(context.cpu, context.immediate.toInt())
    }
}

private object JmpRel8Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val rel = context.immediate.toByte().toInt()
        context.cpu.eip += rel
    }
}

private object HltExecutor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        println("CPU Executed HLT (Halt).")
        throw IllegalStateException("HLT Executed")
    }
}

private object MovR32Rm32Executor : InstructionExecutor {
    override fun execute(context: DecodeContext) {
        val modRM = context.modRM ?: error("ModR/M is required for MOV r32, r/m32")
        val targetRegister = (modRM shr 3) and 0x07
        val sourceValue = readSourceValue(context, modRM)
        RegisterBank.byIndex(targetRegister).write(context.cpu, sourceValue)
    }

    private fun readSourceValue(context: DecodeContext, modRM: Int): Int {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        if (mod == 3) {
            return RegisterBank.byIndex(rm).read(context.cpu)
        }

        val address = effectiveAddress(context, modRM)
        return context.memory.read32(address)
    }

    private fun effectiveAddress(context: DecodeContext, modRM: Int): Int {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        if (rm == 4) {
            val sib = context.sib ?: error("SIB byte is required")
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
}

private interface Register32 {
    fun read(cpu: CPU): Int
    fun write(cpu: CPU, value: Int)
}

private object RegisterBank {
    private val table: List<Register32> = listOf(
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.eax
            override fun write(cpu: CPU, value: Int) {
                cpu.eax = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.ecx
            override fun write(cpu: CPU, value: Int) {
                cpu.ecx = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.edx
            override fun write(cpu: CPU, value: Int) {
                cpu.edx = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.ebx
            override fun write(cpu: CPU, value: Int) {
                cpu.ebx = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.esp
            override fun write(cpu: CPU, value: Int) {
                cpu.esp = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.ebp
            override fun write(cpu: CPU, value: Int) {
                cpu.ebp = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.esi
            override fun write(cpu: CPU, value: Int) {
                cpu.esi = value
            }
        },
        object : Register32 {
            override fun read(cpu: CPU): Int = cpu.edi
            override fun write(cpu: CPU, value: Int) {
                cpu.edi = value
            }
        }
    )

    fun byIndex(index: Int): Register32 = table[index and 0x07]
}

