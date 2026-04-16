package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.api.VmOutput
import io.github.jwyoon1220.pvm.core.VM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [DosHleAddon] (INT 21h).
 */
class DosHleAddonTest {

    /** Run a single INT 21h + HLT with the given register preset. */
    private fun runInt21(setup: VM.() -> Unit): VM {
        val vm = VM()
        vm.registerAddon(DosHleAddon())
        vm.memory.write8(0, 0xCD); vm.memory.write8(1, 0x21); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.setup()
        vm.run()
        return vm
    }

    private fun runInt21WithOutput(setup: VM.() -> Unit): Pair<VM, String> {
        val buf = StringBuilder()
        val vm  = VM(vmOutput = VmOutput { buf.append(it) })
        vm.registerAddon(DosHleAddon())
        vm.memory.write8(0, 0xCD); vm.memory.write8(1, 0x21); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.setup()
        vm.run()
        return vm to buf.toString()
    }

    // ── AH=02h ── Write character ─────────────────────────────────────────────

    @Test fun `AH=02h writes character to output`() {
        val (_, out) = runInt21WithOutput { cpu.ah = 0x02; cpu.dl = 'H'.code }
        assertEquals("H", out)
    }

    // ── AH=09h ── Write '$'-terminated string ────────────────────────────────

    @Test fun `AH=09h writes string up to dollar sign`() {
        val (vm, out) = runInt21WithOutput {
            val msg = "Hello\$"
            msg.forEachIndexed { i, c -> memory.write8(0x100 + i, c.code) }
            cpu.ah  = 0x09
            cpu.edx = 0x100
        }
        assertEquals("Hello", out)
        vm.close()
    }

    // ── AH=4Ch ── Exit ───────────────────────────────────────────────────────

    @Test fun `AH=4Ch halts the VM`() {
        val vm = runInt21 { cpu.ah = 0x4C; cpu.al = 42 }
        assertTrue(vm.cpu.halted)
        vm.close()
    }

    // ── AH=30h ── Get DOS version ────────────────────────────────────────────

    @Test fun `AH=30h returns DOS version 3 dot 30`() {
        val vm = runInt21 { cpu.ah = 0x30 }
        assertEquals(3,  vm.cpu.al, "major version")
        assertEquals(30, vm.cpu.ah, "minor version")
        vm.close()
    }

    // ── AH=0Bh ── Check input status ─────────────────────────────────────────

    @Test fun `AH=0Bh returns 0x00 when no input available`() {
        val vm = runInt21 { cpu.ah = 0x0B }
        assertEquals(0x00, vm.cpu.al)
        vm.close()
    }

    // ── AH=19h ── Get current drive ──────────────────────────────────────────

    @Test fun `AH=19h returns default drive C`() {
        val vm = runInt21 { cpu.ah = 0x19 }
        assertEquals(2, vm.cpu.al, "drive C = 2")
        vm.close()
    }

    // ── AH=2Ah ── Get date ────────────────────────────────────────────────────

    @Test fun `AH=2Ah returns valid calendar date`() {
        val vm = runInt21 { cpu.ah = 0x2A }
        assertTrue(vm.cpu.cx >= 2024,  "year >= 2024")
        assertTrue(vm.cpu.dh in 1..12, "month in 1..12")
        assertTrue(vm.cpu.dl in 1..31, "day in 1..31")
        vm.close()
    }

    // ── AH=2Ch ── Get time ────────────────────────────────────────────────────

    @Test fun `AH=2Ch returns valid time of day`() {
        val vm = runInt21 { cpu.ah = 0x2C }
        assertTrue(vm.cpu.ch in 0..23, "hour in 0..23")
        assertTrue(vm.cpu.cl in 0..59, "minute in 0..59")
        assertTrue(vm.cpu.dh in 0..59, "second in 0..59")
        vm.close()
    }

    // ── AH=48h ── Allocate memory ─────────────────────────────────────────────

    @Test fun `AH=48h allocates a valid segment above conventional memory base`() {
        val vm = runInt21 { cpu.ah = 0x48; cpu.bx = 0x10 }   // 16 paragraphs = 256 bytes
        assertEquals(0, vm.cpu.eflags and 1, "CF=0 (success)")
        assertTrue(vm.cpu.ax >= 0x1000, "returned segment >= 0x1000")
        vm.close()
    }

    @Test fun `AH=48h returns error when memory exhausted`() {
        val vm = runInt21 { cpu.ah = 0x48; cpu.bx = 0xFFFF }  // request more than available
        assertEquals(1, vm.cpu.eflags and 1, "CF=1 (out of memory)")
        vm.close()
    }

    // ── AH=0Ah ── Buffered input (smoke test with a mock) ────────────────────

    @Test fun `AH=0Ah reads characters into the buffer struct`() {
        // Provide pre-loaded input via a custom VM with a mock VmInput
        val inputBytes = "Hi\r".toByteArray()
        var pos = 0
        val vm = VM(
            vmInput = object : io.github.jwyoon1220.pvm.api.VmInput {
                override fun read(): Int = if (pos < inputBytes.size) inputBytes[pos++].toInt() and 0xFF else -1
                override fun hasInput() = pos < inputBytes.size
            }
        )
        vm.registerAddon(DosHleAddon())
        vm.memory.write8(0, 0xCD); vm.memory.write8(1, 0x21); vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0

        // Buffer at 0x200: byte[0]=max(10), byte[1]=actualCount(out)
        vm.memory.write8(0x200, 10)   // max length = 10
        vm.cpu.ah  = 0x0A
        vm.cpu.edx = 0x200

        vm.run()

        val actualCount = vm.memory.read8(0x201)
        assertEquals(2, actualCount, "should have read 'H' and 'i'")
        assertEquals('H'.code, vm.memory.read8(0x202), "first char")
        assertEquals('i'.code, vm.memory.read8(0x203), "second char")
        vm.close()
    }
}
