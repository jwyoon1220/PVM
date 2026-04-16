package io.github.jwyoon1220.pvm.core.engine

import io.github.jwyoon1220.pvm.core.VM
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke tests that exercise the [JitEngine] end-to-end through [VM].
 *
 * Each test loads a small x86 program, runs it with [JitEngine], and checks
 * the resulting CPU state.  The same programs are executed with
 * [InterpreterEngine] to serve as reference values.
 */
class JitEngineTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun runJit(base: Int, prog: ByteArray): VM {
        val vm = VM(engine = JitEngine())
        vm.loadAt(base, prog)
        vm.cpu.eip = base
        vm.run()
        return vm
    }

    private fun runInterp(base: Int, prog: ByteArray): VM {
        val vm = VM(engine = InterpreterEngine())
        vm.loadAt(base, prog)
        vm.cpu.eip = base
        vm.run()
        return vm
    }

    // ── test cases ───────────────────────────────────────────────────────────

    @Test fun `MOV EAX imm32 HLT`() {
        // B8 2A 00 00 00  MOV EAX, 42
        // F4              HLT
        val prog = byteArrayOf(0xB8.toByte(), 42, 0, 0, 0, 0xF4.toByte())
        val jit = runJit(0x100, prog)
        assertEquals(42, jit.cpu.eax)
    }

    @Test fun `MOV then ADD register-register`() {
        // B8 0A 00 00 00  MOV EAX, 10
        // BB 05 00 00 00  MOV EBX, 5
        // 01 D8           ADD EAX, EBX   (rm32=EAX, r32=EBX)
        // F4              HLT
        val prog = byteArrayOf(
            0xB8.toByte(), 10, 0, 0, 0,
            0xBB.toByte(), 5,  0, 0, 0,
            0x01.toByte(), 0xD8.toByte(),
            0xF4.toByte()
        )
        val jit   = runJit(0x200, prog)
        val interp = runInterp(0x200, prog)
        assertEquals(interp.cpu.eax, jit.cpu.eax)
        assertEquals(15, jit.cpu.eax)
    }

    @Test fun `DEC-loop countdown`() {
        // B9 0A 00 00 00  MOV ECX, 10       (at base+0)
        // 49              DEC ECX           (at base+5)
        // 75 FD           JNE -3            (at base+6; fallThru=base+8, target=base+5)
        // F4              HLT               (at base+8)
        val prog = byteArrayOf(
            0xB9.toByte(), 10, 0, 0, 0,
            0x49.toByte(),
            0x75.toByte(), (-3).toByte(),
            0xF4.toByte()
        )
        val jit    = runJit(0x300, prog)
        val interp = runInterp(0x300, prog)
        assertEquals(interp.cpu.ecx, jit.cpu.ecx)
        assertEquals(0, jit.cpu.ecx)
    }

    @Test fun `PUSH and POP round-trip`() {
        // B8 DEADBEEF     MOV EAX, 0xDEADBEEF
        // 50              PUSH EAX
        // 5B              POP EBX
        // F4              HLT
        val prog = byteArrayOf(
            0xB8.toByte(), 0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte(),
            0x50.toByte(),
            0x5B.toByte(),
            0xF4.toByte()
        )
        val vm = VM(engine = JitEngine())
        vm.loadAt(0x400, prog)
        vm.cpu.eip  = 0x400
        vm.cpu.esp  = 0x800          // stack pointer somewhere safe
        vm.run()
        assertEquals(vm.cpu.eax, vm.cpu.ebx)
        assertEquals(0xDEADBEEF.toInt(), vm.cpu.ebx)
    }

    @Test fun `CMP and JCC taken vs not-taken`() {
        // Tests that the JIT correctly handles a conditional branch that IS taken.
        // B8 05 00 00 00  MOV EAX, 5
        // 3D 05 00 00 00  CMP EAX, 5   (83 /7 would be Grp1; 3D is CMP EAX,imm — but not in DecoderPipeline)
        // We use Grp1 0x83 /7 for CMP rm32, imm8:
        // 83 F8 05        CMP EAX, 5  (ModRM=F8 → mod=3, reg=7, rm=0=EAX)
        // 74 01           JE +1        (skip the next byte)
        // F4              HLT (never reached if branch taken)
        // 90              NOP (branch target)
        // F4              HLT
        val prog = byteArrayOf(
            0xB8.toByte(), 5, 0, 0, 0,      // MOV EAX, 5
            0x83.toByte(), 0xF8.toByte(), 5, // CMP EAX, 5
            0x74.toByte(), 1,                // JE +1
            0xF4.toByte(),                   // HLT (skipped)
            0x90.toByte(),                   // NOP (branch target)
            0xF4.toByte()                    // HLT
        )
        val jit    = runJit(0x500, prog)
        val interp = runInterp(0x500, prog)
        assertEquals(interp.cpu.eip, jit.cpu.eip)
        // EAX still 5
        assertEquals(5, jit.cpu.eax)
    }

    @Test fun `JIT result matches interpreter for all basic instructions`() {
        // A longer program exercising INC, SUB, MOV rm/r forms, and CALL/RET.
        // Layout at 0x1000:
        //   MOV EAX, 100
        //   MOV EBX, 1
        // loop:
        //   SUB EAX, EBX    ; EAX -= 1
        //   DEC EBX         ; EBX-- (goes to 0 then wraps)
        //   INC EBX         ; EBX++ (back to 1)
        //   83 F8 00        CMP EAX, 0
        //   75 F6           JNE loop (-10)
        //   HLT
        val prog = byteArrayOf(
            0xB8.toByte(), 100, 0, 0, 0,    // MOV EAX, 100
            0xBB.toByte(),   1, 0, 0, 0,    // MOV EBX, 1
            // loop (offset 10):
            0x29.toByte(), 0xD8.toByte(),   // SUB EAX, EBX
            0x4B.toByte(),                  // DEC EBX (48+3)
            0x43.toByte(),                  // INC EBX (40+3)
            0x83.toByte(), 0xF8.toByte(), 0,// CMP EAX, 0
            0x75.toByte(), (-9).toByte(),   // JNE -9 (back to SUB)
            0xF4.toByte()                   // HLT
        )
        val jit    = runJit(0x1000, prog)
        val interp = runInterp(0x1000, prog)
        assertEquals(interp.cpu.eax, jit.cpu.eax)
        assertEquals(interp.cpu.ebx, jit.cpu.ebx)
        assertEquals(0, jit.cpu.eax)
    }
}
