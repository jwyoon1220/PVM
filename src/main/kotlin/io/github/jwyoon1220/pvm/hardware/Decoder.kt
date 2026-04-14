package io.github.jwyoon1220.pvm.hardware

import it.unimi.dsi.fastutil.ints.IntArrayList

class Decoder(
    private val cpu: CPU,
    private val memory: Memory
) {
    // 현재 디코딩 중인 명령어의 정보를 담는 임시 구조체
    private class InstructionContext {
        var prefixes = IntArrayList()
        var opcode: Int = 0
        var modRM: Int? = null
        var sib: Int? = null
        var displacement: Int = 0
        var immediate: Long = 0
    }

    fun step() {
        val ctx = InstructionContext()

        // 1. Prefixes 탐색 (0x66, 0x67, 0xF0, 0xF2, 0xF3, 0x2E 등)
        fetchPrefixes(ctx)

        // 2. Opcode Fetch
        ctx.opcode = fetch8()

        // 3. Opcode에 따라 ModR/M이 필요한지 판단 후 Fetch
        if (requiresModRM(ctx.opcode)) {
            ctx.modRM = fetch8()

            // 4. ModR/M 결과에 따라 SIB가 필요한지 판단
            if (requiresSIB(ctx.modRM!!)) {
                ctx.sib = fetch8()
            }

            // 5. Displacement Fetch
            ctx.displacement = fetchDisplacement(ctx.modRM!!)
        }

        // 6. Execute (분리된 실행 유닛)
        execute(ctx)
    }

    private fun fetchPrefixes(ctx: InstructionContext) {
        while (true) {
            val p = memory.read8(cpu.eip)
            if (isPrefix(p)) {
                ctx.prefixes.add(p)
                cpu.eip++
            } else break
        }
    }

    private fun requiresModRM(opcode: Int): Boolean {
        // x86에서 ModR/M을 요구하는 대표적인 Opcode 대역 (앞으로 계속 추가될 예정)
        return when (opcode) {
            in 0x88..0x8F, // MOV (레지스터/메모리 간 이동)
            in 0x00..0x3F, // ADD, SUB, CMP, AND, OR, XOR 등 기본 산술/논리 연산의 일부
            0x84, 0x85,    // TEST
            0x8D,          // LEA (Load Effective Address)
            0xC6, 0xC7,    // MOV (메모리/레지스터에 상수 넣기)
            0xFE, 0xFF     // INC, DEC, JMP, CALL (ModR/M에 따라 동작이 달라지는 그룹)
                -> true
            else -> false
        }
    }
    private fun requiresSIB(modRM: Int): Boolean {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        // Mod가 11(레지스터 전용)이 아니고, R/M 필드가 100(4)일 때만 SIB가 존재합니다.
        // 예: [ESP], [EAX + EBX * 4] 같은 주소 모드
        return mod != 3 && rm == 4
    }

    private fun fetchDisplacement(modRM: Int, sib: Int? = null): Int {
        val mod = (modRM shr 6) and 0x03
        val rm = modRM and 0x07

        return when (mod) {
            0 -> {
                // Mod 00: 원칙적으로 변위(Displacement)가 없음.
                // 단, 예외적으로 R/M이 101(5)이거나, SIB Base가 5인 경우는 순수 32비트 메모리 주소임
                if (rm == 5 || (rm == 4 && sib != null && (sib and 0x07) == 5)) {
                    fetch32()
                } else {
                    0
                }
            }
            1 -> {
                // Mod 01: 8비트 변위 (부호 있는 확장)
                // 예: [EAX + 0x10]
                fetch8().toByte().toInt()
            }
            2 -> {
                // Mod 10: 32비트 변위
                // 예: [EAX + 0x12345678]
                fetch32()
            }
            3 -> {
                // Mod 11: 레지스터 직접 접근이므로 변위 없음
                0
            }
            else -> 0
        }
    }

    private fun execute(ctx: InstructionContext) {
        when (ctx.opcode) {
            0x90 -> {
                // NOP: 아무것도 하지 않음
            }

            in 0xB8..0xBF -> {
                // MOV reg32, imm32 (ModR/M이 없는 특수 케이스)
                val regIndex = ctx.opcode - 0xB8
                val immediate = fetch32() // 상수를 읽어옴

                // 앞서 만든 CPU 레지스터에 접근
                when (regIndex) {
                    0 -> cpu.eax = immediate
                    1 -> cpu.ecx = immediate
                    2 -> cpu.edx = immediate
                    3 -> cpu.ebx = immediate
                    4 -> cpu.esp = immediate
                    5 -> cpu.ebp = immediate
                    6 -> cpu.esi = immediate
                    7 -> cpu.edi = immediate
                }
            }

            0xEB -> {
                // JMP rel8 (짧은 점프)
                val rel = fetch8().toByte().toInt()
                cpu.eip += rel
            }

            0xF4 -> {
                // HLT: 프로세서 정지 (테스트용)
                println("CPU Executed HLT (Halt).")
                throw Exception("HLT Executed")
            }

            else -> {
                throw UnsupportedOperationException(
                    String.format("Not implemented opcode: %02X at EIP: %08X", ctx.opcode, cpu.eip - 1)
                )
            }
        }
    }

    private fun isPrefix(b: Int): Boolean = b in arrayOf(0x66, 0x67, 0x2E, 0x3E, 0x26, 0x64, 0x65, 0xF0, 0xF2, 0xF3)

    private fun fetch8(): Int = memory.read8(cpu.eip).also { cpu.eip += 1 }

    private fun fetch16(): Int = memory.read16(cpu.eip).also { cpu.eip += 2 }

    private fun fetch32(): Int = memory.read32(cpu.eip).also { cpu.eip += 4 }
}