package io.github.jwyoon1220.pvm.addons

import io.github.jwyoon1220.pvm.core.VM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [BiosSystemAddon] (INT 15h, INT 1Ah, INT 19h).
 */
class BiosSystemAddonTest {

    private fun runInt(vector: Int, setup: VM.() -> Unit): VM {
        val vm = VM()
        vm.registerAddon(BiosSystemAddon())
        vm.memory.write8(0, 0xCD)
        vm.memory.write8(1, vector)
        vm.memory.write8(2, 0xF4)
        vm.cpu.eip = 0
        vm.setup()
        vm.run()
        return vm
    }

    // ── INT 15h ── System services ────────────────────────────────────────────

    @Test fun `INT 15h AH=88h reports extended memory`() {
        val vm = runInt(0x15) { cpu.ah = 0x88 }
        assertTrue(vm.cpu.ax > 0,            "extended memory > 0 KB")
        assertEquals(0, vm.cpu.eflags and 1, "CF=0")
        vm.close()
    }

    @Test fun `INT 15h unsupported function sets CF`() {
        val vm = runInt(0x15) { cpu.ah = 0xFF }
        assertTrue(vm.cpu.eflags and 1 != 0, "CF=1 for unsupported function")
        vm.close()
    }

    @Test fun `INT 15h AH=E8h AL=01h returns E801 memory sizes`() {
        val vm = runInt(0x15) { cpu.ah = 0xE8; cpu.al = 0x01 }
        assertEquals(0, vm.cpu.eflags and 1, "CF=0")
        assertTrue(vm.cpu.ax > 0,            "AX = KB in first region > 0")
        vm.close()
    }

    // ── INT 19h ── Bootstrap loader ───────────────────────────────────────────

    @Test fun `INT 19h halts the VM`() {
        val vm = runInt(0x19) { /* no setup needed */ }
        assertTrue(vm.cpu.halted, "VM must be halted after INT 19h")
        vm.close()
    }

    // ── INT 1Ah ── Timer / RTC ────────────────────────────────────────────────

    @Test fun `INT 1Ah AH=00h returns non-negative timer ticks`() {
        val vm = runInt(0x1A) { cpu.ah = 0x00 }
        assertTrue(vm.cpu.dx >= 0, "low word of ticks >= 0")
        vm.close()
    }

    @Test fun `INT 1Ah AH=02h returns valid BCD time`() {
        val vm = runInt(0x1A) { cpu.ah = 0x02 }
        assertEquals(0, vm.cpu.eflags and 1, "CF=0")

        val hour   = (vm.cpu.ch shr 4 and 0xF) * 10 + (vm.cpu.ch and 0xF)
        val minute = (vm.cpu.cl shr 4 and 0xF) * 10 + (vm.cpu.cl and 0xF)
        val second = (vm.cpu.dh shr 4 and 0xF) * 10 + (vm.cpu.dh and 0xF)

        assertTrue(hour   in 0..23, "hour $hour must be 0-23")
        assertTrue(minute in 0..59, "minute $minute must be 0-59")
        assertTrue(second in 0..59, "second $second must be 0-59")
        vm.close()
    }

    @Test fun `INT 1Ah AH=04h returns valid BCD date`() {
        val vm = runInt(0x1A) { cpu.ah = 0x04 }
        assertEquals(0, vm.cpu.eflags and 1, "CF=0")

        val century = (vm.cpu.ch shr 4 and 0xF) * 10 + (vm.cpu.ch and 0xF)
        val yearLo  = (vm.cpu.cl shr 4 and 0xF) * 10 + (vm.cpu.cl and 0xF)
        val month   = (vm.cpu.dh shr 4 and 0xF) * 10 + (vm.cpu.dh and 0xF)
        val day     = (vm.cpu.dl shr 4 and 0xF) * 10 + (vm.cpu.dl and 0xF)

        assertTrue(century in 19..21,  "century $century plausible")
        assertTrue(month   in 1..12,   "month $month in 1-12")
        assertTrue(day     in 1..31,   "day $day in 1-31")
        vm.close()
    }
}
